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
import android.util.Log

/**
 * sing-box `libbox` engine handle for one proxy node.
 *
 * Model follows Nekogram X 9.3.3: **each proxy object owns its own engine
 * instance** (start / stop are per-proxy operations, exactly like the
 * per-proxy `loader` inside `SharedConfig.VmessProxy` in the 9.3.3 blueprint).
 * There is no shared "global engine" and no hot-reload indirection: switching
 * the current proxy stops the previous node's engine and starts the selected
 * one.
 *
 * One libbox process-wide prerequisite is [Libbox.setup] which is performed
 * exactly once per process. Every node then gets its own command server:
 *
 *   `Libbox.newCommandServer(handler, platformStub)` + `CommandServer.start()`
 *   + `CommandServer.startOrReloadService(configJson, OverrideOptions())`
 *
 * and is released with `closeService()` + `close()`.
 *
 * CRITICAL constraints (verified against proother/sing-box-lib v1.13.21):
 *   * The native calls block for seconds — every entry point here MUST be
 *     called off the UI thread (the owning proxy object dispatches onto a
 *     background executor).
 *   * Any failure inside `startOrReloadService` can leave the command server
 *     in a state a later native call cannot recover from (SIGABRT bypasses the
 *     Java try/catch), so a failed start is always torn down completely and
 *     surfaced as an exception for the caller to report.
 *   * Keep the AAR pinned to v1.13.21. Newer sing-box releases grow
 *     [PlatformInterface] (shell/bridge/auto-redirect callbacks) and require
 *     re-implementing [PlatformStub].
 */
object LibboxEngine {

    private val setupLock = Any()
    private var setupDone = false

    /**
     * Starts a fresh engine for [configJson] and returns its handle.
     * The caller owns the handle and MUST release it through [stop] once the
     * node is stopped. Throws [Exception] when the engine cannot start (the
     * failed server is torn down internally before the exception propagates).
     */
    @JvmStatic
    fun start(context: Context, configJson: String): CommandServer {
        ensureSetup(context)
        // Build + start the command server in its own scope so a throw from
        // start()/startOrReloadService() cannot leak a half-initialised handle.
        val server = Libbox.newCommandServer(ServerHandler(), PlatformStub())
        try {
            server.start()
            server.startOrReloadService(configJson, OverrideOptions())
            Log.i("LibboxEngine", "node engine up (libbox ${Libbox.version()})")
            return server
        } catch (e: Throwable) {
            Log.e("LibboxEngine", "engine failure", e)
            release(server)
            throw IllegalStateException("sing-box failed to start: ${e.message}", e)
        }
    }

    /** Releases a node engine handle. Safe to call with null. Never throws. */
    @JvmStatic
    fun stop(server: CommandServer?) {
        if (server == null) return
        release(server)
        Log.i("LibboxEngine", "node engine stopped")
    }

    private fun ensureSetup(context: Context) {
        synchronized(setupLock) {
            if (setupDone) return
            val base = context.filesDir.absolutePath
            val setup = SetupOptions()
            setup.setBasePath(base)
            setup.setWorkingPath(base)
            setup.setTempPath(context.cacheDir.absolutePath)
            setup.setFixAndroidStack(true)
            Libbox.setup(setup)
            setupDone = true
        }
    }

    private fun release(server: CommandServer) {
        try {
            server.closeService()
        } catch (e: Throwable) {
            Log.e("LibboxEngine", "engine failure", e)
        }
        try {
            server.close()
        } catch (e: Throwable) {
            Log.e("LibboxEngine", "engine failure", e)
        }
    }

    /** Callbacks the engine invokes on the host app (neutral — no TUN/VPN). */
    private class ServerHandler : CommandServerHandler {
        override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()
        override fun serviceReload() {}
        override fun serviceStop() {}
        override fun setSystemProxyEnabled(enabled: Boolean) {}
        override fun writeDebugMessage(message: String) {
            Log.d("LibboxEngine", "libbox: $message")
        }
    }

    /**
     * Minimal [PlatformInterface]. Every node exposes a local mixed
     * (SOCKS/HTTP) inbound — there is no TUN/VPN, no system proxy and no
     * per-app routing — so every TUN / interface-monitor / Wi-Fi-state
     * callback is never exercised and returns a neutral value.
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
