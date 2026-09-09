package tw.nekomimi.nekogram.helpers

import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessagesController
import org.telegram.messenger.SharedConfig
import tw.nekomimi.nekogram.NekoConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manages the built-in sing-box proxy (generalized from the original VLESS-only
 * manager; class name kept to avoid a whole-repo rename).
 *
 * Nodes are plain protocol link strings (`vless://`, `trojan://`, `ss://`,
 * `hysteria2://`) stored as a JSON array under the canonical NekoConfig keys
 * `proxyEnabled` / `proxyActiveLink` / `proxyNodes`. The legacy
 * `vlessEnabled` / `vlessLink` / `vlessNodes` keys are still readable and are
 * migrated (written back once) the first time the manager is used.
 *
 * The engine (libbox) runs **inside the app process** — there is no foreground
 * service and no persistent notification, matching Nekogram X 9.3.3. Every
 * libbox call (setup / start / reload / stop) is dispatched onto
 * [engineExecutor]: the calls are native and can block for seconds, so they must
 * never run on the UI thread. Enabling also points Telegram's proxy at the
 * local mixed inbound `127.0.0.1:[LOCAL_PORT]` through the ordinary
 * [SharedConfig.setCurrentProxy] path, so the selection is persisted.
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

    /** Single background thread that owns every libbox call. */
    private val engineExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        val thread = Thread(runnable, "singbox-engine")
        thread.isDaemon = true
        thread
    }

    private val schemeRegex = Regex(
        "(vless|trojan|ss|hysteria2)://",
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
        return try {
            ensureMigrated()
            NekoConfig.proxyEnabled.Bool()
        } catch (e: Throwable) {
            FileLog.e(e)
            false
        }
    }

    /** Whether a usable proxy link has been configured. */
    @JvmStatic
    fun hasConfig(): Boolean = getVlessLink().isNotBlank()

    /** The active (currently selected) node link. Name kept for backward compatibility. */
    @JvmStatic
    fun getVlessLink(): String {
        return try {
            ensureMigrated()
            NekoConfig.proxyActiveLink.String()
        } catch (e: Throwable) {
            FileLog.e(e)
            ""
        }
    }

    /** Alias of [getVlessLink] expressing the generalized node semantics. */
    @JvmStatic
    fun getActiveLink(): String = getVlessLink()

    @JvmStatic
    fun setVlessLink(link: String) {
        try {
            ensureMigrated()
            NekoConfig.proxyActiveLink.setConfigString(link)
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    /** Alias of [setVlessLink] expressing the generalized node semantics. */
    @JvmStatic
    fun setActiveLink(link: String) {
        setVlessLink(link)
    }

    // --- Display helpers (proxy list / node manager UI) ---

    /** Human name carried in the link fragment, or "" when absent. */
    @JvmStatic
    fun nodeName(link: String): String = safe { ProxyTypes.nodeName(link) } ?: ""

    /** `host:port` from a valid link, or the raw link when unparsable. */
    @JvmStatic
    fun nodeServerPort(link: String): String = safe { ProxyTypes.nodeServerPort(link) } ?: link

    /** Row title for a node: fragment name, falling back to host:port. */
    @JvmStatic
    fun nodeTitle(link: String): String = safe { ProxyTypes.nodeTitle(link) } ?: ""

    /** `[type] name` style row title. */
    @JvmStatic
    fun taggedTitle(link: String): String = safe { ProxyTypes.taggedTitle(link) } ?: ""

    /** True when [link] is the node the engine is currently using. */
    @JvmStatic
    fun isActiveNode(link: String?): Boolean {
        if (link.isNullOrBlank()) return false
        return isEnabled() && getVlessLink() == link
    }

    /** Runs [block], returning null instead of letting an exception escape into the UI. */
    private inline fun <T> safe(block: () -> T): T? {
        return try {
            block()
        } catch (e: Throwable) {
            FileLog.e(e)
            null
        }
    }

    /**
     * TCP connect latency to the node's server:port. Returns the round-trip time
     * in ms, or -1 when the host is unreachable / the link is unparsable.
     */
    @JvmStatic
    fun pingNode(link: String): Long {
        if (link.isBlank()) return -1
        return try {
            val serverPort = nodeServerPort(link)
            val colon = serverPort.lastIndexOf(':')
            if (colon < 0) return -1
            var host = serverPort.substring(0, colon)
            val port = serverPort.substring(colon + 1).toIntOrNull() ?: return -1
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length - 1)
            val start = System.currentTimeMillis()
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress(host, port), 5000)
                    System.currentTimeMillis() - start
                }
            } catch (e: Throwable) {
                -1
            }
        } catch (e: Throwable) {
            FileLog.e(e)
            -1
        }
    }

    // --- Ping cache (proxy list shows the last measured latency) ---

    private val pingCache = HashMap<String, Long>()

    /** Cached TCP latency in ms, or -1 when the node has not been measured yet. */
    @JvmStatic
    @Synchronized
    fun getPing(link: String?): Long {
        if (link == null) return -1
        return pingCache[link] ?: -1
    }

    /** Stores [ping] for [link]; a negative value clears the cached entry. */
    @JvmStatic
    @Synchronized
    fun setPing(link: String?, ping: Long) {
        if (link == null) return
        if (ping < 0) {
            pingCache.remove(link)
        } else {
            pingCache[link] = ping
        }
    }

    // --- Node list (generalized NekoX-style management) ---

    /**
     * Saved node links, oldest first. Empty when none have been added.
     *
     * This is also the load-time cleanup point for unsupported nodes: links the
     * engine can no longer carry (e.g. legacy `vmess://` entries left by older
     * builds) are filtered out and persisted once, and the active link is moved
     * to a surviving node when it pointed at a dropped one.
     *
     * Never throws: the proxy page builds its rows from this call, so a parse or
     * storage failure must degrade to an empty list instead of killing the page.
     */
    @JvmStatic
    fun getNodes(): ArrayList<String> {
        val list = ArrayList<String>()
        try {
            ensureMigrated()
            val raw = NekoConfig.proxyNodes.String()
            if (raw.isNotBlank()) {
                val arr = org.json.JSONArray(raw)
                var dropped = false
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i)
                    if (s.isBlank() || !VlessConfig.isSupportedProxy(s)) {
                        dropped = true
                        continue
                    }
                    list.add(s)
                }
                if (dropped) {
                    FileLog.d("VlessProxyManager: dropped ${arr.length() - list.size} unsupported node(s) on load")
                    // Persist the cleanup so the unsupported nodes never resurface.
                    saveNodes(list)
                }
            }
            val active = NekoConfig.proxyActiveLink.String()
            if (active.isNotBlank() && !VlessConfig.isSupportedProxy(active)) {
                // The active node was dropped (or is unsupported on its own).
                // Fall back to the first surviving node, or clear the selection.
                FileLog.d("VlessProxyManager: active node is unsupported, re-selecting")
                setVlessLink(if (list.isNotEmpty()) list[0] else "")
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
    private fun isValidNode(link: String?): Boolean {
        if (link.isNullOrBlank()) return false
        if (!VlessConfig.isSupportedProxy(link)) return false
        return VlessConfig.buildConfig(link, LOCAL_PORT) != null
    }

    /** Adds a valid, not-yet-present node. Returns true when added. */
    @JvmStatic
    fun addNode(link: String?): Boolean {
        val trimmed = link?.trim() ?: return false
        if (!isValidNode(trimmed)) return false
        return try {
            val nodes = getNodes()
            if (nodes.any { it == trimmed }) return false
            nodes.add(trimmed)
            saveNodes(nodes)
            if (!hasConfig()) {
                setVlessLink(trimmed)
            }
            true
        } catch (e: Throwable) {
            FileLog.e(e)
            false
        }
    }

    /**
     * Parses a block of text (pasted links or a fetched subscription body) into
     * nodes. Every supported proxy link occurrence is validated and added once.
     * @return number of nodes newly added
     */
    @JvmStatic
    fun importFromText(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        var added = 0
        try {
            text.lineSequence().forEach { line ->
                val match = schemeRegex.find(line) ?: return@forEach
                val candidate = line.substring(match.range.first).trim()
                val end = candidate.indexOfAny(charArrayOf(' ', '\t'))
                val link = if (end >= 0) candidate.substring(0, end) else candidate
                if (addNode(link)) added++
            }
        } catch (e: Throwable) {
            FileLog.e(e)
        }
        return added
    }

    /** Removes a node. When the current selection is removed, hot-switches to the first remaining node. */
    @JvmStatic
    fun removeNode(link: String?) {
        if (link.isNullOrBlank()) return
        try {
            val nodes = getNodes()
            if (!nodes.remove(link)) return
            saveNodes(nodes)
            if (getVlessLink() == link) {
                if (nodes.isNotEmpty()) {
                    setVlessLink(nodes[0])
                    if (isEnabled()) {
                        // The engine was running the removed node: hot-reload it
                        // onto the next node so Telegram stays connected.
                        startEngineAsync()
                    }
                } else {
                    setVlessLink("")
                    if (isEnabled()) {
                        setEnabled(false)
                    }
                }
            }
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    /**
     * Replaces [oldLink] with [newLink] in the node list. When the edited node
     * was active, the engine keeps running with the new link so Telegram is not
     * disconnected. Returns true when the edit was saved (a no-op when both
     * links are identical).
     */
    @JvmStatic
    fun replaceNode(oldLink: String?, newLink: String?): Boolean {
        if (oldLink.isNullOrBlank() || newLink.isNullOrBlank()) return false
        val trimmed = newLink.trim()
        if (oldLink == trimmed) return true
        if (!addNode(trimmed)) return false
        return try {
            val wasActive = isActiveNode(oldLink)
            removeNode(oldLink)
            if (wasActive) {
                selectNode(trimmed)
            }
            true
        } catch (e: Throwable) {
            FileLog.e(e)
            false
        }
    }

    /** Selects [link] as the active node and makes sure the proxy is running. */
    @JvmStatic
    fun selectNode(link: String?) {
        if (!isValidNode(link)) {
            FileLog.e("VlessProxyManager: selectNode called with an unusable link")
            return
        }
        val node = link!!.trim()
        try {
            val nodes = getNodes()
            if (nodes.none { it == node }) {
                nodes.add(0, node)
                saveNodes(nodes)
            }
            setVlessLink(node)
            if (!isEnabled()) {
                setEnabled(true)
            } else {
                // Already enabled: hot-reload the engine with the newly selected
                // node so the existing connection to Telegram is not interrupted.
                startEngineAsync()
                applyLocalProxy()
            }
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    // --- Engine lifecycle (in-process, background thread) -------------------

    /**
     * (Re)starts the in-process sing-box engine for the active node.
     *
     * The whole libbox interaction (setup / new command server / start-or-reload)
     * is native and can block for seconds, so it always runs on
     * [engineExecutor] — never on the caller (UI) thread.
     */
    @JvmStatic
    fun startEngineAsync() {
        try {
            engineExecutor.execute { startEngineInternal() }
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    /** Stops the in-process engine on [engineExecutor]. */
    @JvmStatic
    fun stopEngineAsync() {
        try {
            engineExecutor.execute {
                try {
                    LibboxEngine.stop()
                    FileLog.d("VlessProxyManager: engine stopped")
                } catch (e: Throwable) {
                    FileLog.e(e)
                }
            }
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    private fun startEngineInternal() {
        try {
            val link = getVlessLink()
            if (link.isBlank()) {
                FileLog.e("VlessProxyManager: engine start skipped, no active node")
                return
            }
            val config = VlessConfig.buildConfig(link, LOCAL_PORT)
            if (config == null) {
                FileLog.e("VlessProxyManager: engine start skipped, config build failed")
                return
            }
            val context = ApplicationLoader.applicationContext
            if (context == null) {
                FileLog.e("VlessProxyManager: engine start skipped, no application context")
                return
            }
            val ok = if (LibboxEngine.isRunning()) {
                LibboxEngine.reload(config)
            } else {
                LibboxEngine.start(context, config)
            }
            FileLog.d("VlessProxyManager: engine start/reload ok=$ok (nodes=${getNodes().size})")
            if (!ok) {
                FileLog.e("VlessProxyManager: sing-box failed to start; Telegram will fall back to direct")
            }
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    /**
     * Cold-start recovery hook invoked at the end of
     * [org.telegram.tgnet.ConnectionsManager.init]. When Telegram's persisted
     * proxy is our local mixed inbound (proxy_ip == 127.0.0.1 and
     * proxy_port == LOCAL_PORT), a node is configured and the sing-box engine is
     * not running yet (the process was restarted), this restarts the engine so
     * the connection resumes without the user toggling the switch again.
     *
     * The hook never enables the proxy by itself and never throws, so connection
     * init is unaffected when the engine cannot be started.
     */
    @JvmStatic
    fun maybeRestoreAfterColdStart() {
        try {
            if (!isEnabled()) return
            if (!hasConfig()) return
            if (LibboxEngine.isRunning()) return
            val prefs = MessagesController.getGlobalMainSettings()
            if (!prefs.getBoolean("proxy_enabled", false)) return
            val address = prefs.getString("proxy_ip", "")
            val port = prefs.getInt("proxy_port", 0)
            if (!"127.0.0.1".equals(address, ignoreCase = true) || port != LOCAL_PORT) return
            FileLog.d("VlessProxyManager: cold-start restore, starting sing-box engine in-process")
            startEngineAsync()
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    /**
     * Enables or disables the built-in proxy.
     *
     * Enable: persist the flag, kick the engine off on a background thread, then
     * select `127.0.0.1:LOCAL_PORT` as Telegram's current proxy.
     * Disable: stop the engine and clear Telegram's proxy.
     */
    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        try {
            if (enabled && !hasConfig()) {
                NekoConfig.proxyEnabled.setConfigBool(false)
                FileLog.d("VlessProxyManager: refusing to enable without a configured node")
                return
            }
            ensureMigrated()
            NekoConfig.proxyEnabled.setConfigBool(enabled)
            if (enabled) {
                startEngineAsync()
                applyLocalProxy()
            } else {
                stopEngineAsync()
                try {
                    // Clear the stored proxy so the "Use proxy" master switch
                    // cannot later point Telegram at the stopped local engine.
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
        } catch (e: Throwable) {
            FileLog.e(e)
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
}
