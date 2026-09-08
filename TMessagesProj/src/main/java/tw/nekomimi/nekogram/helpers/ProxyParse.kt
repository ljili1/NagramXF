package tw.nekomimi.nekogram.helpers

import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Locale

/**
 * Link parsers for the built-in (sing-box) proxy nodes.
 *
 * Adapted from Nekogram X 9.3.3 (GPL-3.0) `proxy/VmessLoader.kt`,
 * `proxy/ShadowsocksLoader.kt` and `proxy/ShadowsocksRLoader.kt`. The parsing
 * rules intentionally mirror the upstream so pasted links / QR payloads behave
 * the same way, but every `cn.hutool.*` / v2ray-core dependency is removed and
 * replaced with `android.util.Base64` + `org.json.JSONObject`.
 *
 * Only the parsing half is ported: the sing-box engine (see [VlessConfig])
 * consumes the parsed beans; nothing here starts a sub-process.
 */
object ProxyParse {

    // --- Beans (lightweight, self-describing) -------------------------------

    /** VMess node. Field names follow the `com.v2ray.ang.dto.AngConfig.VmessBean` DTO. */
    data class VmessBean(
        var address: String = "",
        var port: Int = 0,
        var id: String = "",
        var alterId: Int = 0,
        var security: String = "auto",
        var network: String = "tcp",
        var remarks: String = "",
        var headerType: String = "none",
        var requestHost: String = "",
        var path: String = "",
        var streamSecurity: String = "",
        var configVersion: Int = 2
    )

    /** Trojan node (the scheme carries the password as userinfo). */
    data class TrojanBean(
        var address: String = "",
        var port: Int = 0,
        var password: String = "",
        var sni: String = "",
        var remarks: String = ""
    )

    /** Shadowsocks node (SIP002, both ss-android and v2rayNG styles). */
    data class SsBean(
        var host: String = "",
        var remotePort: Int = 0,
        var password: String = "",
        var method: String = "aes-256-gcm",
        var plugin: String = "",
        var remarks: String = ""
    )

    /** ShadowsocksR node. Parsed for import diagnostics only — not runnable on sing-box. */
    data class SsrBean(
        var host: String = "",
        var remotePort: Int = 0,
        var password: String = "",
        var method: String = "aes-256-cfb",
        var protocol: String = "origin",
        var protocolParam: String = "",
        var obfs: String = "plain",
        var obfsParam: String = "",
        var remarks: String = ""
    )

    // --- Scheme constants (kept identical to v2rayNG / Nekogram X) ----------

    const val VLESS_PROTOCOL = "vless://"
    const val VMESS_PROTOCOL = "vmess://"
    const val VMESS1_PROTOCOL = "vmess1://"
    const val SS_PROTOCOL = "ss://"
    const val SSR_PROTOCOL = "ssr://"
    const val TROJAN_PROTOCOL = "trojan://"

    // --- VMess ---------------------------------------------------------------

    /**
     * Parse a `vmess://` or `vmess1://` link into a [VmessBean]. Returns null
     * when the payload cannot be decoded / is not a recognized VMess form.
     */
    @JvmStatic
    fun parseVmess(link: String?): VmessBean? {
        if (link.isNullOrBlank()) return null
        return try {
            when {
                link.startsWith(VMESS1_PROTOCOL, ignoreCase = true) -> parseVmess1(link)
                link.startsWith(VMESS_PROTOCOL, ignoreCase = true) -> parseVmessClassic(link)
                else -> null
            }
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Classic v2rayN style: `vmess://` + base64(JSON). Some exporters produce a
     * bare `user@host:port` base64 blob followed by a query string, so that form
     * is accepted as a fallback too.
     */
    private fun parseVmessClassic(link: String): VmessBean? {
        var body = link.substring(VMESS_PROTOCOL.length)
        var fragment = ""
        val hash = body.indexOf('#')
        if (hash >= 0) {
            fragment = urlDecode(body.substring(hash + 1))
            body = body.substring(0, hash)
        }
        val queryIndex = body.indexOf('?')
        if (queryIndex > 0) {
            val rawBase = body.substring(0, queryIndex)
            val decoded = decodeBase64ToString(rawBase)
            if (decoded == null) return null
            return parseSimpleVmess(decoded, fragment)
        }
        val decoded = decodeBase64ToString(body) ?: return null
        if (decoded.contains("= vmess")) {
            return parseIosCsvVmess(decoded, fragment)
        }
        val json = JSONObject(decoded)
        if (json.optString("add").isBlank() || json.optString("id").isBlank()) return null
        val bean = VmessBean(
            address = json.optString("add", ""),
            port = json.optInt("port", 0),
            id = json.optString("id", ""),
            alterId = json.optInt("aid", 0),
            security = json.optString("scy", "auto").ifBlank { "auto" },
            network = json.optString("net", "tcp").ifBlank { "tcp" },
            remarks = json.optString("ps", "").ifBlank { fragment },
            headerType = json.optString("type", "none").ifBlank { "none" },
            requestHost = json.optString("host", ""),
            path = json.optString("path", ""),
            streamSecurity = json.optString("tls", ""),
            configVersion = json.optInt("v", 2)
        )
        if (bean.configVersion < 2) {
            upgradeVmessBean(bean)
        }
        return bean
    }

    /** vmess1://uuid@host:port/path?network=ws&tls=true&header=none#remarks */
    private fun parseVmess1(link: String): VmessBean? {
        var body = link.substring(VMESS1_PROTOCOL.length)
        var fragment = ""
        val hash = body.indexOf('#')
        if (hash >= 0) {
            fragment = urlDecode(body.substring(hash + 1))
            body = body.substring(0, hash)
        }
        val queryIndex = body.indexOf('?')
        val pathAndAuthority = if (queryIndex >= 0) body.substring(0, queryIndex) else body
        val query = if (queryIndex >= 0) body.substring(queryIndex + 1) else ""
        val params = parseQuery(query)

        val pathStart = pathAndAuthority.indexOf('/')
        val authority = if (pathStart >= 0) pathAndAuthority.substring(0, pathStart) else pathAndAuthority
        val urlPath = if (pathStart >= 0) pathAndAuthority.substring(pathStart) else ""

        val at = authority.lastIndexOf('@')
        if (at < 0) return null
        val id = urlDecode(authority.substring(0, at))
        val hp = parseHostPort(authority.substring(at + 1)) ?: return null

        val bean = VmessBean(
            address = hp.first,
            port = hp.second,
            id = id,
            alterId = 0,
            security = params["security"] ?: "auto",
            network = params["network"] ?: "tcp",
            remarks = fragment,
            headerType = params["header"] ?: params["type"] ?: "none",
            requestHost = params["host"] ?: params["sni"] ?: "",
            path = params["path"] ?: "",
            streamSecurity = if (params["tls"] == "true" || params["security"] == "tls") "tls" else "",
            configVersion = 2
        )
        if (bean.network == "ws" || bean.network == "http" || bean.network == "h2") {
            if (bean.path.isBlank()) bean.path = urlPath
        }
        if (bean.remarks.isBlank() && fragment.isNotBlank()) bean.remarks = fragment
        return bean
    }

    /** Legacy v1 JSON used a single `host` field "path;host" for ws/h2. */
    private fun upgradeVmessBean(bean: VmessBean) {
        if ((bean.network == "ws" || bean.network == "h2" || bean.network == "http") &&
            bean.requestHost.contains(';') && bean.path.isBlank()
        ) {
            val parts = bean.requestHost.split(';')
            bean.path = parts[0].trim()
            if (parts.size > 1) {
                bean.requestHost = parts[1].trim()
            }
        }
    }

    /** Bare `security:id@host:port` base64 form used by some simple exporters. */
    private fun parseSimpleVmess(raw: String, fragment: String): VmessBean {
        val bean = VmessBean(remarks = fragment, network = "tcp", headerType = "none", alterId = 0)
        val arr = raw.split('@')
        if (arr.size == 2) {
            val cred = arr[0].split(':')
            val hp = arr[1].split(':')
            if (cred.size == 2 && hp.size == 2) {
                bean.security = "chacha20-poly1305"
                bean.id = cred[1]
                bean.address = hp[0]
                bean.port = hp[1].toIntOrNull() ?: 0
            }
        }
        return bean
    }

    /** "= vmess" CSV form emitted by some iOS clients. */
    private fun parseIosCsvVmess(csv: String, fragment: String): VmessBean? {
        val args = csv.split(",")
        if (args.size < 5) return null
        val bean = VmessBean(
            address = args[1].trim(),
            port = args[2].trim().toIntOrNull() ?: 0,
            security = args[3].trim(),
            id = args[4].trim().replace("\"", ""),
            network = "tcp",
            headerType = "none",
            remarks = fragment
        )
        for (i in 5 until args.size) {
            val arg = args[i].trim()
            when {
                arg == "over-tls=true" -> bean.streamSecurity = "tls"
                arg.startsWith("tls-host=") -> bean.requestHost = arg.substringAfter("=")
                arg.startsWith("obfs=") -> bean.network = arg.substringAfter("=")
                arg.startsWith("obfs-path=") -> {
                    bean.path = arg.substringAfter("obfs-path=\"").substringBefore("\"")
                }
                arg.contains("Host:") -> {
                    bean.requestHost = arg.substringAfter("Host:").substringBefore("[").trim()
                }
            }
        }
        return bean
    }

    // --- Trojan --------------------------------------------------------------

    /** Parse a `trojan://password@host:port?sni=...#remarks` link. */
    @JvmStatic
    fun parseTrojan(link: String?): TrojanBean? {
        if (link.isNullOrBlank()) return null
        return try {
            if (!link.startsWith(TROJAN_PROTOCOL, ignoreCase = true)) return null
            var body = link.substring(TROJAN_PROTOCOL.length)
            var fragment = ""
            val hash = body.indexOf('#')
            if (hash >= 0) {
                fragment = urlDecode(body.substring(hash + 1))
                body = body.substring(0, hash)
            }
            val queryIndex = body.indexOf('?')
            val authority = if (queryIndex >= 0) body.substring(0, queryIndex) else body
            val params = parseQuery(if (queryIndex >= 0) body.substring(queryIndex + 1) else "")

            val at = authority.lastIndexOf('@')
            if (at < 0) return null
            val rawUser = authority.substring(0, at)
            val hp = parseHostPort(authority.substring(at + 1)) ?: return null

            // Some clients split an encoded ':' inside the password into user/pass.
            var password = urlDecode(rawUser)
            val colon = rawUser.indexOf(':')
            if (colon > 0) {
                val user = urlDecode(rawUser.substring(0, colon))
                val pass = urlDecode(rawUser.substring(colon + 1))
                password = if (pass.isNotBlank()) "$user:$pass" else user
            }
            val sni = params["sni"]?.ifBlank { null } ?: hp.first
            TrojanBean(hp.first, hp.second, password, sni, fragment)
        } catch (e: Throwable) {
            null
        }
    }

    // --- Shadowsocks ---------------------------------------------------------

    /**
     * Parse a `ss://` link (SIP002). Supports both the ss-android userinfo style
     * (`method:password@host` / `base64(method:password)@host`) and the v2rayNG
     * whole-body base64 style (`base64(method:password@host:port)`).
     */
    @JvmStatic
    fun parseSs(link: String?): SsBean? {
        if (link.isNullOrBlank()) return null
        return try {
            if (!link.startsWith(SS_PROTOCOL, ignoreCase = true)) return null
            var body = link.substring(SS_PROTOCOL.length)
            var fragment = ""
            val hash = body.indexOf('#')
            if (hash >= 0) {
                fragment = urlDecode(body.substring(hash + 1))
                body = body.substring(0, hash)
            }
            val queryIndex = body.indexOf('?')
            val main = if (queryIndex >= 0) body.substring(0, queryIndex) else body
            val query = if (queryIndex >= 0) body.substring(queryIndex + 1) else ""
            val params = parseQuery(query)
            val plugin = params["plugin"] ?: ""

            if (main.contains('@')) {
                val userInfo = main.substring(0, main.lastIndexOf('@'))
                val hpRaw = main.substring(main.lastIndexOf('@') + 1)
                val hp = parseHostPort(hpRaw) ?: return null
                var method = ""
                var password = ""
                if (userInfo.contains(':')) {
                    // ss-android plaintext "method:password@host:port"
                    method = urlDecode(userInfo.substringBefore(':'))
                    password = urlDecode(userInfo.substringAfter(':'))
                } else {
                    val decoded = decodeBase64ToString(userInfo)
                    if (decoded != null && decoded.contains(':')) {
                        method = decoded.substringBefore(':')
                        password = decoded.substringAfter(':')
                    } else {
                        return null
                    }
                }
                return SsBean(hp.first, hp.second, password, method.ifBlank { "none" }, plugin, fragment)
            }

            // v2rayNG style: whole body base64 encodes "method:password@host:port".
            val decoded = decodeBase64ToString(main) ?: return null
            val at = decoded.lastIndexOf('@')
            if (at < 0) return null
            val cred = decoded.substring(0, at)
            val hp = parseHostPort(decoded.substring(at + 1)) ?: return null
            if (!cred.contains(':')) return null
            val method = cred.substringBefore(':')
            val password = cred.substringAfter(':')
            SsBean(hp.first, hp.second, password, method.ifBlank { "none" }, plugin, fragment)
        } catch (e: Throwable) {
            null
        }
    }

    /** Builds a canonical SIP002 `ss://` link from a [SsBean]. */
    @JvmStatic
    fun toSsLink(bean: SsBean): String {
        val sb = StringBuilder()
        sb.append(SS_PROTOCOL)
        val userInfo = encodeUrlSafeBase64("${bean.method}:${bean.password}")
        sb.append(userInfo).append('@')
        sb.append(bean.host).append(':').append(bean.remotePort)
        if (bean.plugin.isNotBlank()) {
            sb.append("?plugin=").append(urlEncode(bean.plugin))
        }
        if (bean.remarks.isNotBlank()) {
            sb.append('#').append(urlEncode(bean.remarks))
        }
        return sb.toString()
    }

    // --- ShadowsocksR (diagnostics only) -------------------------------------

    /** Parse `ssr://base64(host:port:protocol:method:obfs:base64(password)/?params)` */
    @JvmStatic
    fun parseSsr(link: String?): SsrBean? {
        if (link.isNullOrBlank()) return null
        return try {
            if (!link.startsWith(SSR_PROTOCOL, ignoreCase = true)) return null
            val body = link.substring(SSR_PROTOCOL.length)
            val hash = body.indexOf('#')
            val raw = if (hash >= 0) body.substring(0, hash) else body
            val fragment = if (hash >= 0) urlDecode(body.substring(hash + 1)) else ""
            val decoded = decodeBase64ToString(raw) ?: return null
            val main = decoded.substringBefore("/")
            val paramsPart = decoded.substringAfter("/", "")
            val parts = main.split(':')
            if (parts.size < 6) return null
            val bean = SsrBean(
                host = parts[0],
                remotePort = parts[1].toIntOrNull() ?: 0,
                protocol = parts[2],
                method = parts[3],
                obfs = parts[4],
                password = decodeBase64ToString(parts[5]) ?: "",
                remarks = fragment
            )
            if (paramsPart.isNotBlank()) {
                parseQuery(paramsPart).forEach { (k, v) ->
                    when (k) {
                        "obfsparam" -> bean.obfsParam = decodeBase64ToString(v) ?: v
                        "protoparam" -> bean.protocolParam = decodeBase64ToString(v) ?: v
                        "remarks" -> {
                            val r = decodeBase64ToString(v) ?: v
                            if (r.isNotBlank()) bean.remarks = r
                        }
                    }
                }
            }
            bean
        } catch (e: Throwable) {
            null
        }
    }

    // --- Small helpers -------------------------------------------------------

    private fun parseQuery(query: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        if (query.isBlank()) return map
        query.split('&').forEach { pair ->
            val eq = pair.indexOf('=')
            if (eq >= 0) {
                map[urlDecode(pair.substring(0, eq))] = urlDecode(pair.substring(eq + 1))
            } else if (pair.isNotEmpty()) {
                map[urlDecode(pair)] = ""
            }
        }
        return map
    }

    /** host:port with IPv6 bracket support. Returns null when malformed. */
    private fun parseHostPort(raw: String): Pair<String, Int>? {
        var s = raw.trim()
        var host: String
        var portStr: String
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return null
            host = s.substring(1, end)
            val rest = s.substring(end + 1)
            portStr = if (rest.startsWith(":")) rest.substring(1) else ""
        } else {
            val colon = s.lastIndexOf(':')
            if (colon < 0) return null
            host = s.substring(0, colon)
            portStr = s.substring(colon + 1)
        }
        if (host.isBlank()) return null
        val port = portStr.toIntOrNull() ?: return null
        if (port <= 0 || port > 65535) return null
        return host to port
    }

    private fun decodeBase64ToString(s: String): String? {
        val bytes = decodeBase64(s) ?: return null
        return try {
            String(bytes, Charsets.UTF_8)
        } catch (e: Throwable) {
            null
        }
    }

    private fun decodeBase64(s: String): ByteArray? {
        if (s.isBlank()) return null
        val candidates = arrayOf(
            Base64.DEFAULT,
            Base64.NO_PADDING,
            Base64.URL_SAFE,
            Base64.URL_SAFE or Base64.NO_PADDING,
            Base64.NO_WRAP
        )
        for (flags in candidates) {
            try {
                val bytes = Base64.decode(s, flags)
                // A decode that produced nothing is not useful for text payloads.
                if (bytes.isNotEmpty() || s.length < 4) return bytes
            } catch (ignored: Throwable) {
            }
        }
        return null
    }

    private fun encodeUrlSafeBase64(s: String): String {
        return Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun urlEncode(s: String): String {
        return try {
            java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        } catch (e: Throwable) {
            s
        }
    }

    private fun urlDecode(s: String): String {
        return try {
            URLDecoder.decode(s, "UTF-8")
        } catch (e: Throwable) {
            s
        }
    }

    /** Locale helpers kept for parity with upstream string lists (compile-time use). */
    @JvmStatic
    fun normalizeMethod(method: String?): String {
        val m = method?.lowercase(Locale.US) ?: return "aes-256-gcm"
        return when (m) {
            "plain" -> "none"
            else -> m
        }
    }
}
