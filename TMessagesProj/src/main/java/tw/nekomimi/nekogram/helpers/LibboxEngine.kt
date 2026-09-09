package tw.nekomimi.nekogram.helpers

import android.content.Context
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import org.telegram.messenger.FileLog

/**
 * Runs the sing-box engine through the `libbox` AAR, following the upstream
 * sing-box / libbox lifecycle exactly. There is no self-invented wrapper layer,
 * no extra process and no Android Service:
 *
 *   1. `Libbox.setup(SetupOptions)` — **once per process**
 *      (basePath / workingPath / tempPath / fixAndroidStack).
 *   2. `Libbox.newCommandServer(CommandServerHandler, PlatformInterface)` +
 *      `CommandServer.start()` — **once per engine session**.
 *   3. `CommandServer.startOrReloadService(configJson, OverrideOptions())` —
 *      used for the first start *and* for every node switch. Switching the
 *      active node is therefore a hot reload: the command server is never torn
 *      down, so Telegram's connection to the local inbound is not interrupted.
 *   4. `CommandServer.closeService()` stops the engine; `CommandServer.close()`
 *      releases the server.
 *
 * The engine runs **inside the app process**, so there is no foreground service
 * and no persistent notification. Dropping the foreground service has one
 * consequence that is handled explicitly rather than worked around: the engine
 * lives and dies with the app process, so it is (re)started from explicit call
 * sites only — the proxy page (user action) and
 * [VlessProxyManager.maybeRestoreAfterColdStart] at the end of
 * `ConnectionsManager.init()` (cold start / process-restart recovery). Because
 * no Android component is ever started in the background, the Android 8+
 * background-start restrictions simply do not apply; there is nothing for the
 * system to refuse.
 *
 * API verified against proother/sing-box-lib v1.13.21 by dumping classes.jar
 * with javap — NOT guessed:
 *   Libbox.setup(SetupOptions)
 *   Libbox.newCommandServer(CommandServerHandler, PlatformInterface) -> CommandServer
 *   CommandServer.start()
 *   CommandServer.startOrReloadService(String config, OverrideOptions)
 *   CommandServer.closeService() / close()
 *
 * IMPORTANT: pin the AAR to v1.13.21. Newer sing-box releases grow
 * PlatformInterface (e.g. shell/bridge/auto-redirect callbacks), so upgrading
 * requires re-implementing [PlatformStub].
 */
object LibboxEngine {

    private var commandServer: CommandServer? = null
    private var setupDone = false

    /** Last config we successfully (re)loaded, so a reload can reuse it. */
    private var lastConfig: String? = null

    /**
     * Canonical libbox lifecycle entry: `setup` once, create and `start` the
     * command server once, then (re)load the configuration with
     * `startOrReloadService`.
     *
     * After the first successful call every subsequent call is a hot reload, so
     * switching the active node never restarts the command server.
     *
     * CRITICAL: any failure during [startOrReloadService] (e.g. an invalid
     * reality/ws combination in the config, or the engine rejecting the
     * outbound) leaves the command server in a state that the next call into
     * native code will not recover from. We therefore **always** release the
     * command server on failure — even when we reused an existing one — so the
     * next call creates a fresh server instead of driving a corrupted one into
     * a native abort.
     *
     * The libbox calls are native and block for seconds — callers MUST run this
     * off the UI thread (see `VlessProxyManager.engineExecutor`).
     */
    @Synchronized
    fun startOrReload(context: Context, configJson: String): Boolean {
        var server = commandServer
        return try {
            if (!setupDone) {
                val base = context.filesDir.absolutePath
                val setup = SetupOptions()
                setup.setBasePath(base)
                setup.setWorkingPath(base)
                setup.setTempPath(context.cacheDir.absolutePath)
                setup.setFixAndroidStack(true)
                Libbox.setup(setup)
                setupDone = true
            }

            if (server == null) {
                // Build + start the command server in its own scope so a throw
                // from `start()` cannot leak the half-initialised handle.
                val fresh = Libbox.newCommandServer(ServerHandler(), PlatformStub())
                try {
                    fresh.start()
                } catch (e: Throwable) {
                    try {
                        fresh.close()
                    } catch (ignore: Throwable) {
                    }
                    throw e
                }
                commandServer = fresh
                server = fresh
            }
            server.startOrReloadService(configJson, OverrideOptions())
            lastConfig = configJson
            FileLog.d("LibboxEngine: startOrReloadService ok (libbox ${Libbox.version()})")
            true
        } catch (e: Throwable) {
            FileLog.e(e)
            // Always tear down the server on failure (even when we reused the
            // existing one): a half-broken libbox will SIGABRT the next call
            // and bypass the Java try/catch — tearing down here keeps the
            // process alive at the cost of one extra setup on the next call.
            val broken = commandServer
            commandServer = null
            lastConfig = null
            if (broken != null) {
                release(broken)
            }
            false
        }
    }

    /** Stops the engine (`closeService`) and releases the server (`close`). */
    @Synchronized
    fun stop() {
        val server = commandServer ?: return
        commandServer = null
        lastConfig = null
        release(server)
    }

    @Synchronized
    fun isRunning(): Boolean = commandServer != null

    /** The most recent config handed to the engine, or null if stopped. */
    @Synchronized
    fun currentConfig(): String? = lastConfig

    private fun release(server: CommandServer) {
        try {
            server.closeService()
        } catch (e: Throwable) {
            FileLog.e(e)
        }
        try {
            server.close()
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    /** Callbacks the engine invokes on the host app. */
    private class ServerHandler : CommandServerHandler {
        override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()
        override fun serviceReload() {}
        override fun serviceStop() {}
        override fun setSystemProxyEnabled(enabled: Boolean) {}
        override fun writeDebugMessage(message: String) {
            FileLog.d("libbox: $message")
        }
    }

    /**
     * Minimal [PlatformInterface].
     *
     * The proxy is exposed as a local mixed (SOCKS/HTTP) inbound — there is no
     * TUN/VPN, no system proxy and no per-app routing (a TUN would need the VPN
     * authorization dialog and its own ongoing notification) — so every TUN /
     * interface-monitor / Wi-Fi-state callback is never exercised and returns a
     * neutral value.
     */
    private class PlatformStub : PlatformInterface {
        override fun useProcFS(): Boolean = false
        override fun usePlatformAutoDetectInterfaceControl(): Boolean = false
        override fun autoDetectInterfaceControl(fd: Int) {}
        override fun openTun(options: TunOptions?): Int = -1
        override fun localDNSTransport(): LocalDNSTransport? = null
        override fun underNetworkExtension(): Boolean = false
        override fun includeAllNetworks(): Boolean = false
        override fun clearDNSCache() {}
        override fun readWIFIState(): WIFIState? = null
        override fun sendNotification(notification: Notification?) {}
        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {}
        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {}
        override fun getInterfaces(): NetworkInterfaceIterator? = null
        override fun findConnectionOwner(
            ipProtocol: Int,
            sourceAddress: String?,
            sourcePort: Int,
            destinationAddress: String?,
            destinationPort: Int
        ): ConnectionOwner? = null
        override fun systemCertificates(): StringIterator? = null
    }
}
