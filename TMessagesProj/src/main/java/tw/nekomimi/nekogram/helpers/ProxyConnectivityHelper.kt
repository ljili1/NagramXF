package tw.nekomimi.nekogram.helpers

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager

/**
 * Connectivity tester for sing-box powered node proxies (VLESS / VMess / Trojan /
 * Shadowsocks / Hysteria / Hysteria2 / TUIC).
 *
 * There is exactly **one** engine (the isolated `:singbox` process) and it runs a
 * single node, so probing a node means starting it there. Two rules follow, and
 * both are enforced here instead of being left to the callers:
 *
 * 1. **Never disturb the node that is in use.** When the active proxy is a node,
 *    only that very node may be probed: the engine answers a request for the
 *    already running link idempotently with its live port, so nothing is stopped
 *    or restarted. Every other node is skipped while a node is in use - probing
 *    one would stop the node Telegram is connected through and every connection
 *    would fail for the duration of the test (the "proxy cannot connect /
 *    reconnects several times in a few seconds" symptom). Skipped nodes keep
 *    their last measured state; they are never reported as unavailable.
 *
 * 2. **Never blame a node for an engine problem.** A failure of the engine
 *    process itself (bind refused, process died, no answer in time, probe
 *    watchdog) leaves the node's measured availability completely untouched - it
 *    only clears the "checking" flag. Only an engine that actually refuses the
 *    node configuration, or a probe that runs but cannot reach Telegram, marks
 *    the node as unavailable.
 *
 * Bookkeeping runs on the UI thread; engine and native calls are dispatched by
 * their own layers off-thread. Never throws into callers.
 */
object ProxyConnectivityHelper {

    /**
     * Per-node guard. Slightly above ProxyEngineClient's own start timeout so an
     * unanswered start is reported by the client (with a precise reason) first.
     */
    private const val NODE_TIMEOUT_MS = 20_000L

    /**
     * Deferral before a probe really starts.
     *
     * The engine is started here, and this class is reached from synchronous
     * notification dispatch - i.e. from inside UI event handlers (a "save" button
     * or a menu item). Starting an engine inside that stack means a failure in the
     * engine process looks like the button itself crashed the app. Every start is
     * therefore pushed to a later main-loop turn, where nothing of the caller is
     * on the stack anymore.
     */
    private const val PROBE_START_DELAY_MS = 200L

    /**
     * Gap between two probes. Each one stops the previous engine and starts a new
     * one, and giving libbox a moment to release the old one keeps the rapid
     * stop/start sequence from stressing the native engine.
     */
    private const val INTER_NODE_DELAY_MS = 350L

    /**
     * After an engine-level failure the tester backs off for a while: retrying
     * immediately would start the engine over and over for nothing.
     */
    private const val INFRA_BACKOFF_MS = 30_000L

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayList<SharedConfig.SingProxy>()
    private val completionCallbacks = ArrayList<Runnable>()

    private var busy = false
    private var current: SharedConfig.SingProxy? = null
    private var generation = 0

    /** When the last engine-level (infrastructure) failure happened. */
    private var lastInfrastructureFailureAt = 0L

    private val timeoutRunnable = Runnable {
        val node = current
        FileLog.e("proxy node test timed out: ${node?.link?.take(64)}")
        // The watchdog firing means the tester/engine did not answer in time, so
        // this says nothing about the node.
        if (node != null) {
            finishNode(node, -1, infrastructureFailure = true)
        }
    }

    /**
     * Enqueues node proxies for connectivity testing; still-fresh results are
     * reused unless [force] is true. [onComplete] runs on the UI thread once the
     * whole queue (including batches enqueued meanwhile) has drained.
     */
    @JvmStatic
    @JvmOverloads
    fun testNodes(
        nodes: List<SharedConfig.SingProxy>?,
        force: Boolean = false,
        onComplete: Runnable? = null
    ) {
        val added = ArrayList<SharedConfig.SingProxy>()
        synchronized(queue) {
            val now = SystemClock.elapsedRealtime()
            val backoff = !force && now - lastInfrastructureFailureAt < INFRA_BACKOFF_MS
            if (nodes != null) {
                for (node in nodes) {
                    if (queue.contains(node) || current == node) continue
                    if (node.checking) continue
                    // Safety rule 1: never stop the node that serves traffic.
                    if (!canProbe(node)) continue
                    if (backoff) continue
                    if (!force && node.availableCheckTime > 0) {
                        val age = now - node.availableCheckTime
                        // Kept in sync with ProxyListActivity.checkProxyList: a
                        // reachable node is not re-probed every refresh, an
                        // unreachable one is retried sooner.
                        val freshFor = if (node.available) 60_000L else 15_000L
                        // age < 0 covers values written by an older build with a
                        // different clock base: they are simply treated as stale.
                        if (age in 0..freshFor) continue
                    }
                    // The row flips to "Checking" via the proxyCheckDone posts below.
                    node.checking = true
                    queue.add(node)
                    added.add(node)
                }
            }
            if (onComplete != null) {
                completionCallbacks.add(onComplete)
            }
        }
        if (added.isNotEmpty()) {
            // Deferred to a later main-loop turn instead of posted inline. The caller
            // is normally ProxyListActivity.updateRows, which reaches this method
            // while it is still rebuilding its row list: an inline notification
            // re-entered that page's proxyCheckDone handler in the middle of the
            // rebuild, where the row lookup resolved to a holder of a different view
            // type and the unchecked cast threw a main-thread ClassCastException
            // (TextSettingsCell -> TextDetailProxyCell) that killed the app.
            // Nothing depends on inline delivery - these posts only flip the rows to
            // "Checking", which the next frame renders just as well.
            handler.post {
                for (node in added) {
                    // A cancel() or timeout may have cleared the flag meanwhile.
                    if (node.checking) {
                        NotificationCenter.getGlobalInstance()
                            .postNotificationName(NotificationCenter.proxyCheckDone, node)
                    }
                }
            }
        }
        if (!busy) {
            busy = true
            scheduleProcessNext(PROBE_START_DELAY_MS)
        }
    }

    private val processNextRunnable = Runnable { processNext() }

    /**
     * Runs [processNext] on a later main-loop turn (or immediately when the caller
     * is already outside any UI handler chain). Removable, so a cancelled batch
     * cannot be resumed by a pending runnable.
     */
    private fun scheduleProcessNext(delayMs: Long) {
        handler.removeCallbacks(processNextRunnable)
        if (delayMs <= 0L) {
            processNextRunnable.run()
        } else {
            handler.postDelayed(processNextRunnable, delayMs)
        }
    }

    /**
     * True when probing [node] cannot hurt the proxy Telegram is currently using.
     *
     * Safe cases: the proxy is disabled (nothing is using the engine), the active
     * proxy is a native row (engine idle), or [node] **is** the active node (the
     * engine answers idempotently without restarting it).
     */
    private fun canProbe(node: SharedConfig.SingProxy): Boolean {
        return try {
            if (!SharedConfig.isProxyEnabled()) return true
            val active = SharedConfig.currentProxy
            if (active !is SharedConfig.SingProxy) return true
            active == node
        } catch (t: Throwable) {
            FileLog.e(t)
            false
        }
    }

    private fun processNext() {
        val node = synchronized(queue) {
            if (queue.isEmpty()) null else queue.removeAt(0)
        }
        if (node == null) {
            drain()
            return
        }
        current = node
        val gen = ++generation
        node.checking = true
        NotificationCenter.getGlobalInstance()
            .postNotificationName(NotificationCenter.proxyCheckDone, node)
        handler.postDelayed(timeoutRunnable, NODE_TIMEOUT_MS)
        try {
            ProxyEngineClient.start(
                ApplicationLoader.applicationContext,
                node.link,
                node.port,
                object : ProxyEngineClient.StartCallback {
                    override fun onStarted(port: Int) {
                        AndroidUtilities.runOnUIThread {
                            if (current != node || gen != generation) return@runOnUIThread
                            checkThroughInbound(node, port, gen)
                        }
                    }

                    override fun onError(error: String) {
                        AndroidUtilities.runOnUIThread {
                            if (current != node || gen != generation) return@runOnUIThread
                            FileLog.e("proxy node engine refused (${node.link.take(64)}): $error")
                            // An engine/binding failure is not a verdict about the
                            // node; a refused node configuration is.
                            val infrastructure = ProxyEngineClient.isInfrastructureError(error)
                            if (!infrastructure) {
                                // Keep the engine's own words: this is the only way the
                                // reason becomes visible on the device.
                                node.lastError = error
                            }
                            finishNode(node, -1, infrastructure)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            FileLog.e(t)
            finishNode(node, -1, infrastructureFailure = true)
        }
    }

    /** End-to-end probe: connect to Telegram through the node's local inbound. */
    private fun checkThroughInbound(node: SharedConfig.SingProxy, port: Int, gen: Int) {
        if (port <= 0) {
            finishNode(node, -1, infrastructureFailure = true)
            return
        }
        node.port = port
        try {
            ConnectionsManager.getInstance(UserConfig.selectedAccount)
                .checkProxy("127.0.0.1", port, "", "", "") { time ->
                    AndroidUtilities.runOnUIThread {
                        if (current != node || gen != generation) return@runOnUIThread
                        if (time >= 0) {
                            // The engine is up and Telegram is reachable through it.
                            finishNode(node, time, infrastructureFailure = false)
                            return@runOnUIThread
                        }
                        // The probe could not reach Telegram. Before blaming the node,
                        // make sure the engine carrying it is still alive: one that
                        // died mid-probe (libbox aborted, process reclaimed) is not a
                        // node verdict, and marking a working node unavailable is
                        // exactly how "the node works elsewhere but not here" happens.
                        ProxyEngineClient.probeRunning(ApplicationLoader.applicationContext) { running, _ ->
                            AndroidUtilities.runOnUIThread {
                                if (current != node || gen != generation) return@runOnUIThread
                                if (!running) {
                                    finishNode(node, -1, infrastructureFailure = true)
                                } else {
                                    node.lastError = "no Telegram reachability through the node"
                                    finishNode(node, -1, infrastructureFailure = false)
                                }
                            }
                        }
                    }
                }
        } catch (t: Throwable) {
            FileLog.e(t)
            finishNode(node, -1, infrastructureFailure = true)
        }
    }

    private fun finishNode(node: SharedConfig.SingProxy, time: Long, infrastructureFailure: Boolean) {
        handler.removeCallbacks(timeoutRunnable)
        if (current != node) {
            // Late result for a node the watchdog already dismissed.
            if (time >= 0 && node.checking) {
                applyResult(node, time, infrastructureFailure)
            }
            return
        }
        current = null
        applyResult(node, time, infrastructureFailure)
        NotificationCenter.getGlobalInstance()
            .postNotificationName(NotificationCenter.proxyCheckDone, node)
        // Small gap before the next node: the engine is stopped and restarted for
        // each probe, and libbox prefers not to be torn down and re-created back
        // to back. Also keeps the next start out of the current callback stack.
        scheduleProcessNext(INTER_NODE_DELAY_MS)
    }

    private fun applyResult(node: SharedConfig.SingProxy, time: Long, infrastructureFailure: Boolean) {
        node.checking = false
        if (infrastructureFailure) {
            // Do not touch `available` / `ping`: the engine (or the tester) failed,
            // not the node. Clearing the timestamp keeps the row out of the "fresh"
            // window, so it is retried later instead of being reported as dead.
            node.availableCheckTime = 0
            lastInfrastructureFailureAt = SystemClock.elapsedRealtime()
            return
        }
        node.availableCheckTime = SystemClock.elapsedRealtime()
        if (time < 0) {
            node.available = false
            node.ping = 0
        } else {
            node.available = true
            node.ping = time
            node.lastError = null
        }
        // Persist measured state so it survives list reloads / cold start.
        try {
            SharedConfig.saveProxyList()
        } catch (t: Throwable) {
            FileLog.e(t)
        }
    }

    private fun drain() {
        busy = false
        current = null
        handler.removeCallbacks(timeoutRunnable)
        restoreEngineState()
        val callbacks = synchronized(completionCallbacks) {
            val snapshot = ArrayList(completionCallbacks)
            completionCallbacks.clear()
            snapshot
        }
        for (callback in callbacks) {
            try {
                callback.run()
            } catch (t: Throwable) {
                FileLog.e(t)
            }
        }
        // A completion callback may have requested another batch.
        if (synchronized(queue) { queue.isNotEmpty() }) {
            busy = true
            scheduleProcessNext(PROBE_START_DELAY_MS)
        }
    }

    /**
     * Leaves the engine in the state the persisted selection implies.
     *
     * Probing a node started the engine; when nothing legitimately owns it
     * afterwards (the proxy is disabled, or the active proxy is a native row) it
     * must be stopped so no orphan engine keeps running. When the active proxy is
     * a node, the engine was only ever asked for that same node (idempotent) and is
     * deliberately left alone.
     */
    private fun restoreEngineState() {
        try {
            val keepRunning = SharedConfig.isProxyEnabled() && SharedConfig.currentProxy is SharedConfig.SingProxy
            if (!keepRunning) {
                ProxyEngineClient.stop(ApplicationLoader.applicationContext, null)
            }
        } catch (t: Throwable) {
            FileLog.e(t)
        }
    }

    /** True while a batch is still being processed. */
    @JvmStatic
    fun isBusy(): Boolean = busy

    /**
     * Drops the queue, e.g. when the list page goes away. Rows that were mid-check
     * are put back to "not checking" without touching their measured availability -
     * a cancelled probe says nothing about the node. Never throws.
     */
    @JvmStatic
    fun cancel() {
        val cleared = synchronized(queue) {
            val snapshot = ArrayList(queue)
            queue.clear()
            snapshot
        }
        val inFlight = current
        current = null
        generation++
        busy = false
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(processNextRunnable)
        synchronized(completionCallbacks) { completionCallbacks.clear() }
        for (node in cleared) {
            notifyNotChecking(node)
        }
        if (inFlight != null) {
            notifyNotChecking(inFlight)
        }
    }

    private fun notifyNotChecking(node: SharedConfig.SingProxy) {
        if (!node.checking) {
            return
        }
        node.checking = false
        NotificationCenter.getGlobalInstance()
            .postNotificationName(NotificationCenter.proxyCheckDone, node)
    }
}
