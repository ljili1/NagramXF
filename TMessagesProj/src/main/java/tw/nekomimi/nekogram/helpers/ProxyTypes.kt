package tw.nekomimi.nekogram.helpers

import java.net.URLDecoder

/**
 * Type detection and display helpers for built-in proxy node links.
 *
 * Adapted from Nekogram X 9.3.3 (GPL-3.0) display helpers that originally lived
 * on the VLESS node manager; generalized to any link that the sing-box engine
 * can carry (vless / vmess / trojan / shadowsocks).
 *
 * A node is stored as its original self-describing link string, so the runtime
 * "type" is recognized purely from the scheme prefix.
 */
object ProxyTypes {

    /** Schemes that the sing-box engine is able to carry as outbounds. */
    private val SUPPORTED_PREFIXES = arrayOf(
        ProxyParse.VLESS_PROTOCOL,
        ProxyParse.VMESS_PROTOCOL,
        ProxyParse.VMESS1_PROTOCOL,
        ProxyParse.TROJAN_PROTOCOL,
        ProxyParse.SS_PROTOCOL
    )

    /** Scheme used only for the parseProxies extraction regex / QR routing. */
    private val EXTRACT_PREFIXES = arrayOf(
        "vless://", "vmess://", "vmess1://", "trojan://", "ss://",
        "ssr://", "socks://", "ws://", "wss://"
    )

    private val EXTRACT_REGEX = Regex(
        "(vless|vmess1?|trojan|ss|ssr|socks|ws|wss)://",
        RegexOption.IGNORE_CASE
    )

    /** Scheme prefix (with `://`) of [link], or "" when it is not a known proxy link. */
    @JvmStatic
    fun scheme(link: String?): String {
        if (link.isNullOrBlank()) return ""
        for (prefix in EXTRACT_PREFIXES) {
            if (link.startsWith(prefix, ignoreCase = true)) {
                return prefix
            }
        }
        return ""
    }

    /** Protocol family of [link]: vless / vmess / trojan / ss / ssr / socks / ws / wss / "". */
    @JvmStatic
    fun kind(link: String?): String {
        val prefix = scheme(link)
        if (prefix.isEmpty()) return ""
        return when (prefix) {
            "vless://" -> "vless"
            "vmess://", "vmess1://" -> "vmess"
            "trojan://" -> "trojan"
            "ss://" -> "ss"
            "ssr://" -> "ssr"
            "socks://" -> "socks"
            "ws://" -> "ws"
            "wss://" -> "wss"
            else -> ""
        }
    }

    /** Short type tag shown in row titles, e.g. `vless` / `vmess` / `trojan` / `ss`. */
    @JvmStatic
    fun typeTag(link: String?): String = kind(link)

    /** True when [link] is one of the schemes the sing-box engine can run. */
    @JvmStatic
    fun isSupported(link: String?): Boolean {
        val prefix = scheme(link)
        if (prefix.isEmpty()) return false
        return SUPPORTED_PREFIXES.any { it.equals(prefix, ignoreCase = true) }
    }

    /** True when [text] contains at least one supported node link. */
    @JvmStatic
    fun hasSupportedLink(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return EXTRACT_REGEX.findAll(text).any { match ->
            isSupported(match.value + text.substring(match.range.last + 1))
        }
    }

    /** Human name carried by the link fragment, or "" when absent. */
    @JvmStatic
    fun nodeName(link: String): String {
        if (link.isBlank()) return ""
        val hash = link.lastIndexOf('#')
        val fragmentName = if (hash >= 0 && hash < link.length - 1) {
            urlDecode(link.substring(hash + 1)).trim()
        } else {
            ""
        }
        if (fragmentName.isNotBlank()) return fragmentName
        // Classic vmess:// stores its name inside the base64 JSON ("ps") — no fragment.
        return when (kind(link)) {
            "vmess" -> ProxyParse.parseVmess(link)?.remarks?.trim() ?: ""
            "trojan" -> ProxyParse.parseTrojan(link)?.remarks?.trim() ?: ""
            "ss" -> ProxyParse.parseSs(link)?.remarks?.trim() ?: ""
            "ssr" -> ProxyParse.parseSsr(link)?.remarks?.trim() ?: ""
            else -> ""
        }
    }

    /** `host:port` from a valid link, or the raw link when unparsable. */
    @JvmStatic
    fun nodeServerPort(link: String): String {
        if (link.isBlank()) return link
        val server = when (kind(link)) {
            "vmess" -> {
                val b = ProxyParse.parseVmess(link)
                if (b != null) hostPort(b.address, b.port) else ""
            }
            "trojan" -> {
                val b = ProxyParse.parseTrojan(link)
                if (b != null) hostPort(b.address, b.port) else ""
            }
            "ss" -> {
                val b = ProxyParse.parseSs(link)
                if (b != null) hostPort(b.host, b.remotePort) else ""
            }
            "ssr" -> {
                val b = ProxyParse.parseSsr(link)
                if (b != null) hostPort(b.host, b.remotePort) else ""
            }
            else -> plainAuthorityServerPort(link)
        }
        return server.ifBlank { link }
    }

    /** Row title for a node: fragment name, falling back to host:port. */
    @JvmStatic
    fun nodeTitle(link: String): String = nodeName(link).ifBlank { nodeServerPort(link) }

    /** `[type] name` style title shown by the proxy list node rows. */
    @JvmStatic
    fun taggedTitle(link: String): String {
        val tag = typeTag(link)
        if (tag.isEmpty()) return nodeTitle(link)
        return "[$tag] " + nodeTitle(link)
    }

    /**
     * For vless (no bean parser): strip scheme/fragment/query, take the part
     * after the last '@', then the host:port tail.
     */
    private fun plainAuthorityServerPort(link: String): String {
        var body = link
        val schemePrefix = scheme(link)
        if (schemePrefix.isNotEmpty()) {
            body = link.substring(schemePrefix.length)
        }
        val hash = body.indexOf('#')
        if (hash >= 0) body = body.substring(0, hash)
        val q = body.indexOf('?')
        if (q >= 0) body = body.substring(0, q)
        val at = body.lastIndexOf('@')
        if (at >= 0) body = body.substring(at + 1)
        return body.trim()
    }

    private fun hostPort(host: String, port: Int): String {
        if (host.isBlank() || port <= 0) return ""
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "$h:$port"
    }

    private fun urlDecode(s: String): String {
        return try {
            URLDecoder.decode(s, "UTF-8")
        } catch (e: Throwable) {
            s
        }
    }
}
