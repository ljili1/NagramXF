package tw.nekomimi.nekogram.helpers

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds sing-box configuration JSON for the built-in VLESS proxy.
 *
 * The generated config listens on 127.0.0.1:[localPort] (mixed SOCKS/HTTP
 * inbound) and forwards all traffic through a VLESS outbound parsed from a
 * `vless://` link. Telegram is then pointed at the local inbound.
 */
object VlessConfig {

    /**
     * Build a full sing-box config JSON. Returns null when [link] is blank or
     * cannot be parsed.
     */
    @JvmStatic
    fun buildConfig(link: String?, localPort: Int): String? {
        if (link.isNullOrBlank()) return null
        val outbound = parseVless(link) ?: return null

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
            val colon = hostPort.lastIndexOf(':')
            if (colon < 0) return null
            val host = hostPort.substring(0, colon)
            val port = hostPort.substring(colon + 1).toIntOrNull() ?: return null

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
