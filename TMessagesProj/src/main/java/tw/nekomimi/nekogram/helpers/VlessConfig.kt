package tw.nekomimi.nekogram.helpers

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds sing-box configuration JSON for the built-in proxy (generalized VLESS).
 *
 * The generated config listens on 127.0.0.1:[localPort] (mixed SOCKS/HTTP
 * inbound) and forwards all traffic through an outbound parsed from a standard
 * proxy link. Telegram is then pointed at the local inbound.
 *
 * The outbound type is recognized from the link scheme:
 * - `vless://`      -> sing-box "vless"
 * - `trojan://`     -> sing-box "trojan"
 * - `ss://`         -> sing-box "shadowsocks"
 * - `hysteria2://`  -> sing-box "hysteria2"
 *
 * The inbound / route template is identical for every type, so switching a node
 * is a pure hot reload of the outbound.
 */
object VlessConfig {

    /** Prefixes for which an outbound config can be produced. */
    private val SUPPORTED_PREFIXES = arrayOf(
        "vless://", "trojan://", "ss://", "hysteria2://"
    )

    /**
     * Build a full sing-box config JSON. Returns null when [link] is blank or
     * cannot be parsed / is not a supported proxy scheme.
     */
    @JvmStatic
    fun buildConfig(link: String?, localPort: Int): String? {
        if (link.isNullOrBlank()) return null
        val outbound = buildOutbound(link) ?: return null

        val config = JSONObject()
        config.put("log", JSONObject().put("level", "info").put("timestamp", true))

        val inbound = JSONObject()
        inbound.put("type", "mixed")
        inbound.put("tag", "mixed-in")
        inbound.put("listen", "127.0.0.1")
        inbound.put("listen_port", localPort)
        val inbounds = JSONArray()
        inbounds.put(inbound)
        config.put("inbounds", inbounds)

        val outbounds = JSONArray()
        outbounds.put(outbound)
        val direct = JSONObject()
        direct.put("type", "direct")
        direct.put("tag", "direct")
        outbounds.put(direct)
        config.put("outbounds", outbounds)

        val route = JSONObject()
        route.put("final", "proxy")
        config.put("route", route)

        return config.toString()
    }

    /** True when [link] names a proxy scheme this engine can carry. */
    @JvmStatic
    fun isSupportedProxy(link: String?): Boolean {
        if (link.isNullOrBlank()) return false
        val trimmed = link.trim()
        return SUPPORTED_PREFIXES.any { trimmed.startsWith(it, ignoreCase = true) }
    }

    /** Routes [link] to the matching outbound builder. */
    @JvmStatic
    fun buildOutbound(link: String?): JSONObject? {
        if (link.isNullOrBlank()) return null
        val trimmed = link.trim()
        return when {
            trimmed.startsWith("vless://", ignoreCase = true) -> parseVless(trimmed)
            trimmed.startsWith("trojan://", ignoreCase = true) -> buildTrojanOutbound(ProxyParse.parseTrojan(trimmed))
            trimmed.startsWith("ss://", ignoreCase = true) -> buildShadowsocksOutbound(ProxyParse.parseSs(trimmed))
            trimmed.startsWith("hysteria2://", ignoreCase = true) -> buildHysteria2Outbound(ProxyParse.parseHysteria2(trimmed))
            else -> null
        }
    }

    /**
     * Parse a `vless://` URI into a sing-box VLESS outbound (tag "proxy").
     * Supported query params: encryption, security (none/tls/reality), sni, fp,
     * pbk, sid, type (tcp/ws/grpc), flow, path, host, serviceName.
     */
    @JvmStatic
    fun parseVless(uri: String): JSONObject? {
        try {
            if (!uri.startsWith("vless://", ignoreCase = true)) return null
            val body = uri.substring("vless://".length)
            val fragmentIndex = body.indexOf('#')
            val noFrag = if (fragmentIndex >= 0) body.substring(0, fragmentIndex) else body
            val queryIndex = noFrag.indexOf('?')
            val authority = if (queryIndex >= 0) noFrag.substring(0, queryIndex) else noFrag
            val query = if (queryIndex >= 0) noFrag.substring(queryIndex + 1) else ""

            val at = authority.indexOf('@')
            if (at < 0) return null
            val uuid = authority.substring(0, at)
            val hostPort = authority.substring(at + 1)

            // host:port, tolerating bare IPv6 hosts like [2001:db8::1]:443
            var host: String
            var portStr: String
            if (hostPort.startsWith("[")) {
                val end = hostPort.indexOf(']')
                if (end < 0) return null
                host = hostPort.substring(1, end)
                val rest = hostPort.substring(end + 1)
                portStr = if (rest.startsWith(":")) rest.substring(1) else ""
            } else {
                val colon = hostPort.lastIndexOf(':')
                if (colon < 0) return null
                host = hostPort.substring(0, colon)
                portStr = hostPort.substring(colon + 1)
            }
            val port = portStr.toIntOrNull() ?: return null

            val params = parseQuery(query)

            val outbound = JSONObject()
            outbound.put("type", "vless")
            outbound.put("tag", "proxy")
            outbound.put("server", host)
            outbound.put("server_port", port)
            outbound.put("uuid", uuid)

            val flow = params["flow"]
            if (!flow.isNullOrBlank()) outbound.put("flow", flow)

            val security = (params["security"] ?: "none").lowercase()
            if (security != "none") {
                val tls = JSONObject()
                tls.put("enabled", true)
                val sni = params["sni"]
                if (!sni.isNullOrBlank()) tls.put("server_name", sni)
                val fp = params["fp"]
                if (!fp.isNullOrBlank()) {
                    val utls = JSONObject()
                    utls.put("enabled", true)
                    utls.put("fingerprint", fp)
                    tls.put("utls", utls)
                }
                if (security == "reality") {
                    val reality = JSONObject()
                    reality.put("enabled", true)
                    val pbk = params["pbk"]
                    if (!pbk.isNullOrBlank()) reality.put("public_key", pbk)
                    val sid = params["sid"]
                    if (!sid.isNullOrBlank()) reality.put("short_id", sid)
                    tls.put("reality", reality)
                }
                outbound.put("tls", tls)
            }

            val type = (params["type"] ?: "tcp").lowercase()
            if (type == "ws") {
                val transport = JSONObject()
                transport.put("type", "ws")
                val path = params["path"]
                if (!path.isNullOrBlank()) transport.put("path", path)
                val wsHost = params["host"]
                if (!wsHost.isNullOrBlank()) transport.put("headers", JSONObject().put("Host", wsHost))
                outbound.put("transport", transport)
            } else if (type == "grpc") {
                val transport = JSONObject()
                transport.put("type", "grpc")
                val serviceName = params["serviceName"]
                if (!serviceName.isNullOrBlank()) transport.put("service_name", serviceName)
                outbound.put("transport", transport)
            }

            return outbound
        } catch (e: Throwable) {
            return null
        }
    }

    /** Build a sing-box "trojan" outbound. Trojan always speaks TLS. */
    @JvmStatic
    fun buildTrojanOutbound(bean: ProxyParse.TrojanBean?): JSONObject? {
        if (bean == null) return null
        if (bean.address.isBlank() || bean.port <= 0 || bean.password.isBlank()) return null
        val outbound = JSONObject()
        outbound.put("type", "trojan")
        outbound.put("tag", "proxy")
        outbound.put("server", bean.address)
        outbound.put("server_port", bean.port)
        outbound.put("password", bean.password)
        val tls = JSONObject()
        tls.put("enabled", true)
        tls.put("server_name", bean.sni.ifBlank { bean.address })
        outbound.put("tls", tls)
        return outbound
    }

    /** Build a sing-box "shadowsocks" outbound. Plugin fields are not supported by sing-box. */
    @JvmStatic
    fun buildShadowsocksOutbound(bean: ProxyParse.SsBean?): JSONObject? {
        if (bean == null) return null
        if (bean.host.isBlank() || bean.remotePort <= 0) return null
        val outbound = JSONObject()
        outbound.put("type", "shadowsocks")
        outbound.put("tag", "proxy")
        outbound.put("server", bean.host)
        outbound.put("server_port", bean.remotePort)
        outbound.put("method", ProxyParse.normalizeMethod(bean.method))
        outbound.put("password", bean.password)
        return outbound
    }

    /**
     * Build a sing-box "hysteria2" outbound from a parsed [ProxyParse.Hysteria2Bean].
     *
     * Hysteria2 always speaks QUIC+TLS: the `sni`/`insecure` options map onto the
     * tls object (an empty sni lets the engine fall back to the server address).
     * A salamander obfuscator, when requested, is emitted as the optional `obfs`
     * object.
     */
    @JvmStatic
    fun buildHysteria2Outbound(bean: ProxyParse.Hysteria2Bean?): JSONObject? {
        if (bean == null) return null
        if (bean.server.isBlank() || bean.serverPort <= 0 || bean.password.isBlank()) return null
        val outbound = JSONObject()
        outbound.put("type", "hysteria2")
        outbound.put("tag", "proxy")
        outbound.put("server", bean.server)
        outbound.put("server_port", bean.serverPort)
        outbound.put("password", bean.password)

        val tls = JSONObject()
        tls.put("enabled", true)
        if (bean.sni.isNotBlank()) {
            tls.put("server_name", bean.sni)
        }
        if (bean.insecure) {
            tls.put("insecure", true)
        }
        outbound.put("tls", tls)

        if (bean.obfs.isNotBlank() && !bean.obfs.equals("none", ignoreCase = true)) {
            val obfs = JSONObject()
            obfs.put("type", bean.obfs.lowercase())
            if (bean.obfsPassword.isNotBlank()) {
                obfs.put("password", bean.obfsPassword)
            }
            outbound.put("obfs", obfs)
        }
        return outbound
    }

    private fun parseQuery(query: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        if (query.isBlank()) return map
        query.split('&').forEach { pair ->
            val eq = pair.indexOf('=')
            if (eq >= 0) {
                map[decode(pair.substring(0, eq))] = decode(pair.substring(eq + 1))
            } else if (pair.isNotEmpty()) {
                map[decode(pair)] = ""
            }
        }
        return map
    }

    private fun decode(s: String): String {
        return try {
            java.net.URLDecoder.decode(s, "UTF-8")
        } catch (e: Throwable) {
            s
        }
    }
}
