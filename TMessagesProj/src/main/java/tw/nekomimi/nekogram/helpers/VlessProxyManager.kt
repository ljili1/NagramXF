package tw.nekomimi.nekogram.helpers

import android.content.Intent
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import tw.nekomimi.nekogram.NekoConfig
import tw.nekomimi.nekogram.VlessProxyService

/**
 * Replaces the old WebSocket (Cloudflare) proxy helper.
 *
 * Telegram is pointed at [PROXY_SERVER] (a sentinel address); when it asks for
 * the proxy, [getLocalPort] returns the local port where the sing-box engine
 * (running inside [VlessProxyService]) listens, and lazily starts the service.
 */
object VlessProxyManager {
    const val PROXY_SERVER = "vless.nagramxf"
    const val LOCAL_PORT = 6357

    @JvmStatic
    fun isEnabled(): Boolean = NekoConfig.vlessEnabled.Bool()

    @JvmStatic
    fun getProxyAddress(): String = PROXY_SERVER

    /**
     * Local mixed SOCKS/HTTP inbound port. Returns -1 when no VLESS link is
     * configured (so Telegram falls back to direct connection).
     */
    @JvmStatic
    fun getLocalPort(): Int {
        if (NekoConfig.vlessLink.String().isBlank()) return -1
        ensureServiceStarted()
        return LOCAL_PORT
    }

    @JvmStatic
    fun getVlessLink(): String = NekoConfig.vlessLink.String()

    @JvmStatic
    fun setVlessLink(link: String) {
        NekoConfig.vlessLink.setConfigString(link)
    }

    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        NekoConfig.vlessEnabled.setConfigBool(enabled)
        if (enabled) {
            ensureServiceStarted()
        } else {
            stopService()
        }
    }

    @Synchronized
    private fun ensureServiceStarted() {
        try {
            val context = ApplicationLoader.applicationContext
            context.startForegroundService(Intent(context, VlessProxyService::class.java))
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    private fun stopService() {
        try {
            val context = ApplicationLoader.applicationContext
            context.stopService(Intent(context, VlessProxyService::class.java))
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }
}
