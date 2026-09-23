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
 * The native SOCKS5 / MTProto rows can be probed in parallel by
 * ConnectionsManager, but a node needs a running engine first, so each node is
 * started, probed end-to-end and torn down one after another.
 *
 * The engine used for that is the **throwaway `:singbox_test` process**
 * ([ProxyTestEngineClient]), never the live `:singbox` engine: probing another
 * node with the live engine would stop the node Telegram is connected through
 * and every connection would fail for the duration of the test (and the engine
 * would be restarted right after, i.e. a reconnect storm). Because the test
 * engine is disposable:
 *
 *  * testing never touches the live node, so the current proxy keeps working and
 *    the row can be refreshed at any time;
 *  * every row — including nodes other than the selected one — can show a real
 *    availability result even while a node is in use;
 *  * a node that makes libbox abort only kills the throwaway process.
 *
 * Bookkeeping runs on the UI thread; engine and native calls are dispatched by
 * their own layers off-thread. Never throws into callers.
 */
object ProxyConnectivityHelper {

    /**
     * Per-node guard: engine start (possibly a cold process spawn) plus the
     * end-to-end check must finish in time.
     */
    private const val NODE_TIMEOUT_MS = 15_000L

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayList<SharedConfig.SingProxy>()
    private val completionCallbacks = ArrayList<Runnable>()

    private var busy = false
    private var current: SharedConfig.SingProxy? = null
    private var generation = 0

    private val timeoutRunnable = Runnable {
        val node = current
        FileLog.e("proxy node test timed out: ${node?.link?.take(64)}")
        if (node != null) {
            finishNode(node, -1)
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
            if (nodes != null) {
                val now = SystemClock.elapsedRealtime()
                for (node in nodes) {
                    if (queue.contains(node) || current == node) continue
                    if (node.checking) continue
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
        for (node in added) {
            NotificationCenter.getGlobalInstance()
                .postNotificationName(NotificationCenter.proxyCheckDone, node)
        }
        if (!busy) {
            busy = true
            processNext()
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
            ProxyTestEngineClient.start(
                ApplicationLoader.applicationContext,
                node.link,
                node.port,
                object : ProxyTestEngineClient.StartCallback {
                    override fun onResult(started: Boolean, port: Int, error: String?) {
                        AndroidUtilities.runOnUIThread {
                            if (current != node || gen != generation) return@runOnUIThread
                            if (!started || port <= 0) {
                                if (!error.isNullOrBlank()) {
                                    FileLog.e("proxy node engine refused (${node.link.take(64)}): $error")
                                }
                                finishNode(node, -1)
                                return@runOnUIThread
                            }
                            checkThroughInbound(node, port, gen)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            FileLog.e(t)
            finishNode(node, -1)
        }
    }

    /**
     * End-to-end probe: connect to Telegram through the node's local inbound in
     * the test engine.
     */
    private fun checkThroughInbound(node: SharedConfig.SingProxy, port: Int, gen: Int) {
        if (port <= 0) {
            finishNode(node, -1)
            return
        }
        // The test engine binds its own port; for the node currently in use that
        // port is NOT the live endpoint, so it must never be written back into
        // the saved node (doing so would re-point Telegram on the next start).
        if (!isLiveNode(node)) {
            node.port = port
        }
        try {
            ConnectionsManager.getInstance(UserConfig.selectedAccount)
                .checkProxy("127.0.0.1", port, "", "", "") { time ->
                    AndroidUtilities.runOnUIThread {
                        if (current != node || gen != generation) return@runOnUIThread
                        finishNode(node, time)
                    }
                }
        } catch (t: Throwable) {
            FileLog.e(t)
            finishNode(node, -1)
        }
    }

    private fun isLiveNode(node: SharedConfig.SingProxy): Boolean {
        return try {
            SharedConfig.isProxyEnabled() && SharedConfig.currentProxy == node
        } catch (t: Throwable) {
            FileLog.e(t)
            false
        }
    }

    private fun finishNode(node: SharedConfig.SingProxy, time: Long) {
        handler.removeCallbacks(timeoutRunnable)
        if (current != node) {
            // Late result for a node the watchdog already dismissed.
            if (time >= 0 && node.checking) {
                applyResult(node, time)
            }
            return
        }
        current = null
        applyResult(node, time)
        NotificationCenter.getGlobalInstance()
            .postNotificationName(NotificationCenter.proxyCheckDone, node)
        processNext()
    }

    private fun applyResult(node: SharedConfig.SingProxy, time: Long) {
        node.checking = false
        node.availableCheckTime = SystemClock.elapsedRealtime()
        if (time < 0) {
            node.available = false
            node.ping = 0
        } else {
            node.available = true
            node.ping = time
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
        // The throwaway engine is not needed anymore: stop its node and drop the
        // binding so the process can be reclaimed. The live engine is untouched.
        try {
            ProxyTestEngineClient.release()
        } catch (t: Throwable) {
            FileLog.e(t)
        }
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
            processNext()
        }
    }

    /** True while a batch is still being processed. */
    @JvmStatic
    fun isBusy(): Boolean = busy

    /**
     * Drops the queue and releases the test engine, e.g. when the user disables
     * the proxy or the list page goes away. Rows that were mid-check are put back
     * to "not checking" without touching their measured availability — a cancelled
     * probe says nothing about the node. Never throws.
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
        synchronized(completionCallbacks) { completionCallbacks.clear() }
        for (node in cleared) {
            notifyNotChecking(node)
        }
        if (inFlight != null) {
            notifyNotChecking(inFlight)
        }
        try {
            ProxyTestEngineClient.release()
        } catch (t: Throwable) {
            FileLog.e(t)
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
