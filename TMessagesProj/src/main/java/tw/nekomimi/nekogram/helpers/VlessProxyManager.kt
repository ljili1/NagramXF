package tw.nekomimi.nekogram.helpers

import android.content.Intent
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.SharedConfig
import tw.nekomimi.nekogram.NekoConfig
import tw.nekomimi.nekogram.VlessProxyService

/**
 * Manages the built-in VLESS proxy.
 *
 * The proxy is a first-class entry of the app's own settings (NekoSettings →
 * "VLESS 代理"), NOT a fake entry inside Telegram's native proxy list. Enabling
 * starts the sing-box foreground service and then points Telegram's proxy at
 * the local mixed inbound `127.0.0.1:[LOCAL_PORT]` through the ordinary
 * [SharedConfig.setCurrentProxy] path, so the selection is persisted.
 *
 * NOTE: the engine is started only when the user explicitly enables VLESS from
 * the settings page — it is deliberately NOT auto-started from
 * `ConnectionsManager.init()`, so a libbox runtime problem cannot crash the app
 * on launch.
 */
object VlessProxyManager {

    /** Local mixed (SOCKS5/HTTP) inbound port of the sing-box engine. */
    const val LOCAL_PORT = 6357

    @JvmStatic
    fun isEnabled(): Boolean = NekoConfig.vlessEnabled.Bool()

    /** Whether a usable `vless://` link has been configured. */
    @JvmStatic
    fun hasConfig(): Boolean = NekoConfig.vlessLink.String().isNotBlank()

    @JvmStatic
    fun getVlessLink(): String = NekoConfig.vlessLink.String()

    @JvmStatic
    fun setVlessLink(link: String) {
        NekoConfig.vlessLink.setConfigString(link)
    }

    /**
     * Enables or disables the built-in VLESS proxy.
     *
     * Enable: persist the flag, start the engine, then select
     * `127.0.0.1:LOCAL_PORT` as Telegram's current proxy.
     * Disable: stop the engine and clear Telegram's proxy.
     */
    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        if (enabled && !hasConfig()) {
            NekoConfig.vlessEnabled.setConfigBool(false)
            FileLog.d("VlessProxyManager: refusing to enable without a vless:// link")
            return
        }
        NekoConfig.vlessEnabled.setConfigBool(enabled)
        if (enabled) {
            ensureServiceStarted()
            applyLocalProxy()
        } else {
            stopService()
            if (SharedConfig.isProxyEnabled()) {
                SharedConfig.setProxyEnable(false)
            }
        }
    }

    /** Selects `127.0.0.1:LOCAL_PORT` as Telegram's current proxy (persisted). */
    private fun applyLocalProxy() {
        try {
            val info = SharedConfig.ProxyInfo("127.0.0.1", LOCAL_PORT, "", "", "")
            var found = false
            for (existing in SharedConfig.getProxyList()) {
                if (existing.address.equals(info.address, ignoreCase = true) && existing.port == info.port) {
                    found = true
                    break
                }
            }
            if (!found) {
                SharedConfig.addProxy(info)
            }
            SharedConfig.setCurrentProxy(info)
        } catch (e: Throwable) {
            FileLog.e(e)
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
