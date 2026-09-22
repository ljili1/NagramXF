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
 * The isolated `:singbox` process runs a SINGLE engine at a time, so unlike the
 * native SOCKS5 / MTProto / ws rows (which ConnectionsManager probes in
 * parallel), node proxies MUST be tested one after another:
 *
 *   start the engine for the node on its local mixed inbound
 *     -> ConnectionsManager.checkProxy through 127.0.0.1:port (real end-to-end)
 *     -> record ping / availability
 *     -> next node
 *
 * When the whole queue has drained, the engine is put back where the persisted
 * state points (the currently enabled node is restarted; otherwise the engine is
 * stopped), so testing never leaves either a dead selection or an orphan engine.
 *
 * Bookkeeping runs on the UI thread; engine and native calls are dispatched by
 * their own layers off-thread. Never throws into callers.
 */
object ProxyConnectivityHelper {

    /** Per-node guard: engine start + end-to-end check must finish in time. */
    private const val NODE_TIMEOUT_MS = 20_000L

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
     * whole queue (including batches enqueued meanwhile) has drained and before
     * the engine state is restored.
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
                    if (!force && node.availableCheckTime > 0) {
                        val age = now - node.availableCheckTime
                        val freshFor = if (node.available) 20_000L else 5_000L
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
            drainAndRestore()
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
                            finishNode(node, -1)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            FileLog.e(t)
            finishNode(node, -1)
        }
    }

    /** End-to-end probe: connect to Telegram through the node's local inbound. */
    private fun checkThroughInbound(node: SharedConfig.SingProxy, port: Int, gen: Int) {
        if (port <= 0) {
            finishNode(node, -1)
            return
        }
        node.port = port
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

    private fun drainAndRestore() {
        busy = false
        current = null
        handler.removeCallbacks(timeoutRunnable)
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
            return
        }
        restoreEngineState()
    }

    /**
     * Puts the engine back in sync with the persisted selection: restart the
     * currently enabled node, or stop an orphan engine left by testing.
     */
    private fun restoreEngineState() {
        try {
            SharedConfig.loadProxyList()
            val current = SharedConfig.currentProxy
            if (SharedConfig.isProxyEnabled() && current is SharedConfig.SingProxy) {
                SharedConfig.startProxyAsync(current)
            } else {
                ProxyEngineClient.stop(ApplicationLoader.applicationContext, null)
            }
        } catch (t: Throwable) {
            FileLog.e(t)
        }
    }

    /** True while a batch is still being processed. */
    @JvmStatic
    fun isBusy(): Boolean = busy
}
