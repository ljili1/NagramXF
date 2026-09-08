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
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog

/**
 * Runs the sing-box engine through the `libbox` AAR (in-process).
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

    @Synchronized
    fun start(context: Context, configJson: String): Boolean {
        stop()
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

            val server = Libbox.newCommandServer(ServerHandler(), PlatformStub())
            server.start()
            server.startOrReloadService(configJson, OverrideOptions())
            commandServer = server
            lastConfig = configJson
            FileLog.d("LibboxEngine: started (libbox ${Libbox.version()})")
            true
        } catch (e: Throwable) {
            FileLog.e(e)
            false
        }
    }

    /**
     * Reloads the running engine with a new config (e.g. after the user switches
     * the active node) without tearing the process down. Falls back to a full
     * [start] when no engine is currently up.
     */
    @Synchronized
    fun reload(configJson: String): Boolean {
        val server = commandServer
        return if (server != null) {
            try {
                server.startOrReloadService(configJson, OverrideOptions())
                lastConfig = configJson
                FileLog.d("LibboxEngine: reloaded config")
                true
            } catch (e: Throwable) {
                FileLog.e(e)
                start(ApplicationLoader.applicationContext, configJson)
            }
        } else {
            start(ApplicationLoader.applicationContext, configJson)
        }
    }

    @Synchronized
    fun stop() {
        val server = commandServer ?: return
        commandServer = null
        lastConfig = null
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

    @Synchronized
    fun isRunning(): Boolean = commandServer != null

    /** The most recent config handed to the engine, or null if stopped. */
    @Synchronized
    fun currentConfig(): String? = lastConfig

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
     * The VLESS proxy is exposed as a local mixed (SOCKS/HTTP) inbound — there is
     * no TUN/VPN and no per-app routing — so every TUN / interface-monitor /
     * Wi-Fi-state callback is never exercised and returns a neutral value.
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
