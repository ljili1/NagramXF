package tw.nekomimi.nekogram.helpers

import android.content.Intent
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessagesController
import org.telegram.messenger.SharedConfig
import tw.nekomimi.nekogram.NekoConfig
import tw.nekomimi.nekogram.VlessProxyService

/**
 * Manages the built-in sing-box proxy (generalized from the original VLESS-only
 * manager; class name kept to avoid a whole-repo rename).
 *
 * Nodes are plain protocol link strings (`vless://`, `vmess://`, `trojan://`,
 * `ss://`) stored as a JSON array under the canonical NekoConfig keys
 * `proxyEnabled` / `proxyActiveLink` / `proxyNodes`. The legacy
 * `vlessEnabled` / `vlessLink` / `vlessNodes` keys are still readable and are
 * migrated (written back once) the first time the manager is used.
 *
 * Enabling starts the sing-box foreground service and then points Telegram's
 * proxy at the local mixed inbound `127.0.0.1:[LOCAL_PORT]` through the
 * ordinary [SharedConfig.setCurrentProxy] path, so the selection is persisted.
 */
object VlessProxyManager {

    /** Local mixed (SOCKS5/HTTP) inbound port of the sing-box engine. */
    const val LOCAL_PORT = 6357

    private const val KEY_ENABLED = "proxyEnabled"
    private const val KEY_ACTIVE_LINK = "proxyActiveLink"
    private const val KEY_NODES = "proxyNodes"

    // Legacy keys, still read when the canonical keys have not been written yet.
    private const val LEGACY_KEY_ENABLED = "vlessEnabled"
    private const val LEGACY_KEY_ACTIVE_LINK = "vlessLink"
    private const val LEGACY_KEY_NODES = "vlessNodes"

    @Volatile
    private var migrationAttempted = false

    private val schemeRegex = Regex(
        "(vless|vmess|vmess1|trojan|ss)://",
        RegexOption.IGNORE_CASE
    )

    /** Copies the legacy vless* prefs into the canonical proxy* keys once. */
    @Synchronized
    private fun ensureMigrated() {
        if (migrationAttempted) return
        try {
            val prefs = NekoConfig.getPreferences()
            val hasNew = prefs.contains(KEY_ENABLED) ||
                prefs.contains(KEY_ACTIVE_LINK) ||
                prefs.contains(KEY_NODES)
            if (hasNew) {
                migrationAttempted = true
                return
            }
            val hasOld = prefs.contains(LEGACY_KEY_ENABLED) ||
                prefs.contains(LEGACY_KEY_ACTIVE_LINK) ||
                prefs.contains(LEGACY_KEY_NODES)
            if (hasOld) {
                if (prefs.contains(LEGACY_KEY_ENABLED)) {
                    NekoConfig.proxyEnabled.setConfigBool(prefs.getBoolean(LEGACY_KEY_ENABLED, false))
                }
                if (prefs.contains(LEGACY_KEY_ACTIVE_LINK)) {
                    NekoConfig.proxyActiveLink.setConfigString(prefs.getString(LEGACY_KEY_ACTIVE_LINK, ""))
                }
                if (prefs.contains(LEGACY_KEY_NODES)) {
                    NekoConfig.proxyNodes.setConfigString(prefs.getString(LEGACY_KEY_NODES, ""))
                }
                FileLog.d("VlessProxyManager: migrated legacy vless* keys to proxy* keys")
            }
            migrationAttempted = true
        } catch (e: Throwable) {
            // Context may not be ready yet; retry on the next access.
            FileLog.e(e)
        }
    }

    @JvmStatic
    fun isEnabled(): Boolean {
        ensureMigrated()
        return NekoConfig.proxyEnabled.Bool()
    }

    /** Whether a usable proxy link has been configured. */
    @JvmStatic
    fun hasConfig(): Boolean = getVlessLink().isNotBlank()

    /** The active (currently selected) node link. Name kept for backward compatibility. */
    @JvmStatic
    fun getVlessLink(): String {
        ensureMigrated()
        return NekoConfig.proxyActiveLink.String()
    }

    /** Alias of [getVlessLink] expressing the generalized node semantics. */
    @JvmStatic
    fun getActiveLink(): String = getVlessLink()

    @JvmStatic
    fun setVlessLink(link: String) {
        ensureMigrated()
        NekoConfig.proxyActiveLink.setConfigString(link)
    }

    /** Alias of [setVlessLink] expressing the generalized node semantics. */
    @JvmStatic
    fun setActiveLink(link: String) {
        setVlessLink(link)
    }

    // --- Display helpers (proxy list / node manager UI) ---

    /** Human name carried in the link fragment, or "" when absent. */
    @JvmStatic
    fun nodeName(link: String): String = ProxyTypes.nodeName(link)

    /** `host:port` from a valid link, or the raw link when unparsable. */
    @JvmStatic
    fun nodeServerPort(link: String): String = ProxyTypes.nodeServerPort(link)

    /** Row title for a node: fragment name, falling back to host:port. */
    @JvmStatic
    fun nodeTitle(link: String): String = ProxyTypes.nodeTitle(link)

    /** `[type] name` style row title. */
    @JvmStatic
    fun taggedTitle(link: String): String = ProxyTypes.taggedTitle(link)

    /** True when [link] is the node the engine is currently using. */
    @JvmStatic
    fun isActiveNode(link: String): Boolean = isEnabled() && getVlessLink() == link

    /**
     * TCP connect latency to the node's server:port. Returns the round-trip time
     * in ms, or -1 when the host is unreachable / the link is unparsable.
     */
    @JvmStatic
    fun pingNode(link: String): Long {
        val serverPort = nodeServerPort(link)
        val colon = serverPort.lastIndexOf(':')
        if (colon < 0) return -1
        var host = serverPort.substring(0, colon)
        val port = runCatching { serverPort.substring(colon + 1).toInt() }.getOrNull() ?: return -1
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length - 1)
        val start = System.currentTimeMillis()
        return try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(host, port), 5000)
                System.currentTimeMillis() - start
            }
        } catch (e: Throwable) {
            -1
        }
    }

    // --- Ping cache (proxy list shows the last measured latency) ---

    private val pingCache = HashMap<String, Long>()

    /** Cached TCP latency in ms, or -1 when the node has not been measured yet. */
    @JvmStatic
    fun getPing(link: String): Long = pingCache[link] ?: -1

    /** Stores [ping] for [link]; a negative value clears the cached entry. */
    @JvmStatic
    fun setPing(link: String, ping: Long) {
        if (ping < 0) {
            pingCache.remove(link)
        } else {
            pingCache[link] = ping
        }
    }

    // --- Node list (generalized NekoX-style management) ---

    /** Saved node links, oldest first. Empty when none have been added. */
    @JvmStatic
    fun getNodes(): ArrayList<String> {
        ensureMigrated()
        val raw = NekoConfig.proxyNodes.String()
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
            NekoConfig.proxyNodes.setConfigString(arr.toString())
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    /** True when [link] is parseable into a supported outbound config. */
    private fun isValidNode(link: String): Boolean {
        if (link.isBlank()) return false
        if (!VlessConfig.isSupportedProxy(link)) return false
        return VlessConfig.buildConfig(link, LOCAL_PORT) != null
    }

    /** Adds a valid, not-yet-present node. Returns true when added. */
    @JvmStatic
    fun addNode(link: String): Boolean {
        val trimmed = link.trim()
        if (!isValidNode(trimmed)) return false
        val nodes = getNodes()
        if (nodes.any { it == trimmed }) return false
        nodes.add(trimmed)
        saveNodes(nodes)
        if (!hasConfig()) {
            setVlessLink(trimmed)
        }
        return true
    }

    /**
     * Parses a block of text (pasted links or a fetched subscription body) into
     * nodes. Every supported proxy link occurrence is validated and added once.
     * @return number of nodes newly added
     */
    @JvmStatic
    fun importFromText(text: String): Int {
        var added = 0
        text.lineSequence().forEach { line ->
            val match = schemeRegex.find(line) ?: return@forEach
            val candidate = line.substring(match.range.first).trim()
            val end = candidate.indexOfAny(charArrayOf(' ', '\t'))
            val link = if (end >= 0) candidate.substring(0, end) else candidate
            if (addNode(link)) added++
        }
        return added
    }

    /** Removes a node. When the current selection is removed, hot-switches to the first remaining node. */
    @JvmStatic
    fun removeNode(link: String) {
        val nodes = getNodes()
        if (!nodes.remove(link)) return
        saveNodes(nodes)
        if (getVlessLink() == link) {
            if (nodes.isNotEmpty()) {
                setVlessLink(nodes[0])
                if (isEnabled()) {
                    // The engine was running the removed node: hot-reload it onto
                    // the next node so Telegram stays connected.
                    val config = VlessConfig.buildConfig(nodes[0], LOCAL_PORT)
                    if (config != null && LibboxEngine.reload(config)) {
                        applyLocalProxy()
                    } else {
                        ensureServiceStarted()
                    }
                }
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
        if (!isValidNode(link)) return
        val nodes = getNodes()
        if (nodes.none { it == link }) {
            nodes.add(0, link)
            saveNodes(nodes)
        }
        setVlessLink(link)
        if (!isEnabled()) {
            setEnabled(true)
        } else {
            // Already enabled: hot-reload the engine with the newly selected node
            // so the existing connection to Telegram is not interrupted.
            val config = VlessConfig.buildConfig(link, LOCAL_PORT)
            if (config != null && LibboxEngine.reload(config)) {
                applyLocalProxy()
            } else {
                ensureServiceStarted()
            }
        }
    }

    /**
     * Enables or disables the built-in proxy.
     *
     * Enable: persist the flag, start the engine, then select
     * `127.0.0.1:LOCAL_PORT` as Telegram's current proxy.
     * Disable: stop the engine and clear Telegram's proxy.
     */
    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        if (enabled && !hasConfig()) {
            NekoConfig.proxyEnabled.setConfigBool(false)
            FileLog.d("VlessProxyManager: refusing to enable without a configured node")
            return
        }
        ensureMigrated()
        NekoConfig.proxyEnabled.setConfigBool(enabled)
        if (enabled) {
            ensureServiceStarted()
            applyLocalProxy()
        } else {
            stopService()
            try {
                // Clear the stored proxy so the "Use proxy" master switch cannot
                // later point Telegram at the stopped local engine.
                MessagesController.getGlobalMainSettings().edit()
                    .remove("proxy_ip")
                    .remove("proxy_port")
                    .putBoolean("proxy_enabled", false)
                    .apply()
            } catch (e: Throwable) {
                FileLog.e(e)
            }
            SharedConfig.setCurrentProxy(null)
        }
    }

    /**
     * Selects `127.0.0.1:LOCAL_PORT` as Telegram's current proxy and persists
     * the full native selection (proxy_ip/proxy_port + proxy_enabled), so that
     * [org.telegram.tgnet.ConnectionsManager.init] re-applies it after a restart
     * the same way a user-tapped proxy row would.
     */
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
            // Persist proxy_ip/proxy_port exactly like ProxyListActivity does when
            // the user enables a proxy row. Without these, ConnectionsManager.init()
            // would not know the local port after a process restart.
            MessagesController.getGlobalMainSettings().edit()
                .putString("proxy_ip", info.address)
                .putInt("proxy_port", info.port)
                .putString("proxy_user", "")
                .putString("proxy_pass", "")
                .putString("proxy_secret", "")
                .putBoolean("proxy_enabled", true)
                .apply()
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
