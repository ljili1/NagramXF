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

    // --- Node list (NekoX-style management) ---

    /** Saved `vless://` nodes, oldest first. Empty when none have been added. */
    @JvmStatic
    fun getNodes(): ArrayList<String> {
        val raw = NekoConfig.vlessNodes.String()
        val list = ArrayList<String>()
        if (raw.isBlank()) return list
        try {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { list.add(it) }
            }
        } catch (e: Throwable) {
            FileLog.e(e)
        }
        return list
    }

    private fun saveNodes(nodes: List<String>) {
        try {
            val arr = org.json.JSONArray()
            nodes.forEach { arr.put(it) }
            NekoConfig.vlessNodes.setConfigString(arr.toString())
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    /** Adds a valid, not-yet-present node. Returns true when added. */
    @JvmStatic
    fun addNode(link: String): Boolean {
        val trimmed = link.trim()
        if (trimmed.isEmpty() || VlessConfig.parseVless(trimmed) == null) return false
        val nodes = getNodes()
        if (nodes.any { it == trimmed }) return false
        nodes.add(trimmed)
        saveNodes(nodes)
        if (!hasConfig()) {
            setVlessLink(trimmed)
        }
        return true
    }

    /** Removes a node. When the current selection is removed, selects the first remaining node. */
    @JvmStatic
    fun removeNode(link: String) {
        val nodes = getNodes()
        if (!nodes.remove(link)) return
        saveNodes(nodes)
        if (getVlessLink() == link) {
            if (nodes.isNotEmpty()) {
                setVlessLink(nodes[0])
            } else {
                setVlessLink("")
                if (isEnabled()) {
                    setEnabled(false)
                }
            }
        }
    }

    /** Selects [link] as the active node and makes sure the proxy is running. */
    @JvmStatic
    fun selectNode(link: String) {
        if (VlessConfig.parseVless(link) == null) return
        val nodes = getNodes()
        if (nodes.none { it == link }) {
            nodes.add(0, link)
            saveNodes(nodes)
        }
        setVlessLink(link)
        if (!isEnabled()) {
            setEnabled(true)
        } else {
            // Already enabled: (re)start the engine with the newly selected node.
            ensureServiceStarted()
        }
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
