package tw.nekomimi.nekogram.helpers

import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Locale

/**
 * Link parsers for the built-in (sing-box) proxy nodes.
 *
 * Adapted from Nekogram X 9.3.3 (GPL-3.0) `proxy/ShadowsocksLoader.kt` and
 * `proxy/ShadowsocksRLoader.kt`. The parsing rules intentionally mirror the
 * upstream so pasted links / QR payloads behave the same way, but every
 * `cn.hutool.*` / v2ray-core dependency is removed and replaced with plain
 * `android.util.Base64` decoding. The Hysteria2 half follows the standard URI
 * grammar shared by v2rayN / subscription providers.
 *
 * Only the parsing half is ported: the sing-box engine (see [VlessConfig])
 * consumes the parsed beans; nothing here starts a sub-process.
 */
object ProxyParse {

    // --- Beans (lightweight, self-describing) -------------------------------

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

    /**
     * Hysteria2 node (`hysteria2://password@host:port?insecure=1&sni=...&obfs=...&obfs-password=...#name`).
     * `insecure` / `obfs` / `pinSHA256` are optional; the display name lives in the
     * `#` fragment.
     */
    data class Hysteria2Bean(
        var server: String = "",
        var serverPort: Int = 0,
        var password: String = "",
        var sni: String = "",
        var insecure: Boolean = false,
        var obfs: String = "",
        var obfsPassword: String = "",
        var pinSHA256: String = "",
        var remarks: String = ""
    )

    /**
     * VMess node (v2rayNG `vmess://base64(json)` payload). Covers the transport
     * and TLS fields the sing-box `vmess` outbound understands.
     */
    data class VmessBean(
        var address: String = "",
        var port: Int = 0,
        var uuid: String = "",
        var alterId: Int = 0,
        var security: String = "auto",
        /** tcp / ws / grpc / h2 / httpupgrade / quic */
        var network: String = "tcp",
        var host: String = "",
        var path: String = "",
        var tls: Boolean = false,
        var sni: String = "",
        var alpn: String = "",
        var fingerprint: String = "",
        var remarks: String = ""
    )

    /** TUIC v5 node (`tuic://uuid:password@host:port?congestion_control=…&alpn=…`). */
    data class TuicBean(
        var server: String = "",
        var serverPort: Int = 0,
        var uuid: String = "",
        var password: String = "",
        var congestionControl: String = "",
        var udpRelayMode: String = "",
        var sni: String = "",
        var alpn: String = "",
        var allowInsecure: Boolean = false,
        var remarks: String = ""
    )

    /**
     * Legacy Hysteria (v1) node
     * (`hysteria://host:port?auth=…&peer=…&upmbps=…&downmbps=…&obfs=…#name`).
     */
    data class HysteriaBean(
        var server: String = "",
        var serverPort: Int = 0,
        var authStr: String = "",
        var obfs: String = "",
        var sni: String = "",
        var alpn: String = "",
        var upMbps: Int = 0,
        var downMbps: Int = 0,
        var insecure: Boolean = false,
        var remarks: String = ""
    )

    // --- Scheme constants (kept identical to v2rayNG / Nekogram X) ----------

    const val VLESS_PROTOCOL = "vless://"
    const val VMESS_PROTOCOL = "vmess://"
    const val SS_PROTOCOL = "ss://"
    const val SSR_PROTOCOL = "ssr://"
    const val TROJAN_PROTOCOL = "trojan://"
    const val HYSTERIA_PROTOCOL = "hysteria://"
    const val HYSTERIA2_PROTOCOL = "hysteria2://"
    const val TUIC_PROTOCOL = "tuic://"

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
            // Keep the sni empty when the link has none; the engine falls back
            // to the server address at build time (VlessConfig.buildTrojanOutbound).
            val sni = params["sni"] ?: ""
            TrojanBean(hp.first, hp.second, password, sni, fragment)
        } catch (e: Throwable) {
            null
        }
    }

    // --- Hysteria2 -----------------------------------------------------------

    /**
     * Parse a standard `hysteria2://` link into an [Hysteria2Bean]. Returns null
     * when the payload is not a recognized Hysteria2 form.
     *
     * Grammar (v2rayN / subscription providers):
     * `hysteria2://<password>@<host>:<port>?insecure=1&sni=<sni>&obfs=salamander&obfs-password=<x>&pinSHA256=...#<name>`
     */
    @JvmStatic
    fun parseHysteria2(link: String?): Hysteria2Bean? {
        if (link.isNullOrBlank()) return null
        return try {
            if (!link.startsWith(HYSTERIA2_PROTOCOL, ignoreCase = true)) return null
            var body = link.substring(HYSTERIA2_PROTOCOL.length)
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

            // The whole userinfo is the authentication password; tolerate a
            // client that left an unencoded ':' inside the password (same
            // leniency as the trojan parser).
            var password = urlDecode(rawUser)
            val colon = rawUser.indexOf(':')
            if (colon > 0) {
                val user = urlDecode(rawUser.substring(0, colon))
                val pass = urlDecode(rawUser.substring(colon + 1))
                password = if (pass.isNotBlank()) "$user:$pass" else user
            }
            val insecureRaw = params["insecure"]
            val obfs = params["obfs"] ?: ""
            Hysteria2Bean(
                server = hp.first,
                serverPort = hp.second,
                password = password,
                sni = params["sni"] ?: "",
                insecure = insecureRaw == "1" || insecureRaw?.equals("true", ignoreCase = true) == true,
                obfs = obfs,
                obfsPassword = params["obfs-password"] ?: params["obfs_password"] ?: "",
                pinSHA256 = params["pinSHA256"] ?: "",
                remarks = fragment
            )
        } catch (e: Throwable) {
            null
        }
    }

    /** Builds a canonical `hysteria2://` link from an [Hysteria2Bean]. The password
     * is URL-encoded; every query parameter is emitted only when set. */
    @JvmStatic
    fun toHysteria2Link(bean: Hysteria2Bean): String {
        val sb = StringBuilder()
        sb.append(HYSTERIA2_PROTOCOL)
        sb.append(urlEncode(bean.password)).append('@')
        appendHostPort(sb, bean.server, bean.serverPort)

        val params = StringBuilder()
        if (bean.insecure) {
            params.append("insecure=1")
        }
        if (bean.sni.isNotBlank()) {
            appendParam(params, "sni", bean.sni)
        }
        if (bean.obfs.isNotBlank() && !bean.obfs.equals("none", ignoreCase = true)) {
            appendParam(params, "obfs", bean.obfs)
            if (bean.obfsPassword.isNotBlank()) {
                appendParam(params, "obfs-password", bean.obfsPassword)
            }
        }
        if (bean.pinSHA256.isNotBlank()) {
            appendParam(params, "pinSHA256", bean.pinSHA256)
        }
        if (params.isNotEmpty()) {
            sb.append('?').append(params)
        }
        if (bean.remarks.isNotBlank()) {
            sb.append('#').append(urlEncode(bean.remarks))
        }
        return sb.toString()
    }

    // --- VMess ---------------------------------------------------------------

    /**
     * Parse a v2rayNG `vmess://` link: the payload after the scheme is base64 of
     * a JSON object with fields `add/port/id/aid/scy/net/type/host/path/tls/sni/alpn/fp`.
     * Returns null when the payload is not valid VMess JSON or lacks address/uuid.
     */
    @JvmStatic
    fun parseVmess(link: String?): VmessBean? {
        if (link.isNullOrBlank()) return null
        return try {
            if (!link.startsWith(VMESS_PROTOCOL, ignoreCase = true)) return null
            var body = link.substring(VMESS_PROTOCOL.length).trim()
            var fragment = ""
            val hash = body.indexOf('#')
            if (hash >= 0) {
                fragment = urlDecode(body.substring(hash + 1))
                body = body.substring(0, hash)
            }
            val queryIndex = body.indexOf('?')
            if (queryIndex >= 0) {
                body = body.substring(0, queryIndex)
            }
            val decoded = decodeBase64ToString(body) ?: return null
            val json = JSONObject(decoded)
            val address = json.optString("add").trim()
            val port = json.optString("port").toIntOrNull() ?: json.optInt("port", 0)
            val uuid = json.optString("id").trim()
            if (address.isEmpty() || uuid.isEmpty() || port <= 0 || port > 65535) return null
            val net = json.optString("net").ifBlank { "tcp" }
            VmessBean(
                address = address,
                port = port,
                uuid = uuid,
                alterId = json.optString("aid").toIntOrNull() ?: json.optInt("aid", 0),
                security = json.optString("scy").ifBlank { json.optString("security").ifBlank { "auto" } },
                network = net,
                host = json.optString("host"),
                path = json.optString("path"),
                tls = json.optString("tls").equals("tls", ignoreCase = true) || json.optString("tls") == "1",
                sni = json.optString("sni"),
                alpn = json.optString("alpn"),
                fingerprint = json.optString("fp"),
                remarks = fragment.ifBlank { json.optString("ps") }
            )
        } catch (e: Throwable) {
            null
        }
    }

    /** Builds a canonical v2rayNG `vmess://` link from a [VmessBean]. */
    @JvmStatic
    fun toVmessLink(bean: VmessBean): String {
        val json = JSONObject()
        json.put("v", "2")
        json.put("ps", bean.remarks)
        json.put("add", bean.address)
        json.put("port", bean.port.toString())
        json.put("id", bean.uuid)
        json.put("aid", bean.alterId.toString())
        json.put("scy", bean.security)
        json.put("net", bean.network)
        json.put("type", "none")
        json.put("host", bean.host)
        json.put("path", bean.path)
        json.put("tls", if (bean.tls) "tls" else "")
        json.put("sni", bean.sni)
        json.put("alpn", bean.alpn)
        json.put("fp", bean.fingerprint)
        val encoded = Base64.encodeToString(
            json.toString().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING
        )
        return VMESS_PROTOCOL + encoded
    }

    // --- TUIC ----------------------------------------------------------------

    /**
     * Parse a `tuic://uuid:password@host:port?congestion_control=…&udp_relay_mode=…&alpn=…&sni=…&allow_insecure=1#name`
     * link. Both `uuid:password@` and `uuid@…?password=` spellings are accepted.
     */
    @JvmStatic
    fun parseTuic(link: String?): TuicBean? {
        if (link.isNullOrBlank()) return null
        return try {
            if (!link.startsWith(TUIC_PROTOCOL, ignoreCase = true)) return null
            var body = link.substring(TUIC_PROTOCOL.length)
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
            val userInfo = authority.substring(0, at)
            val hp = parseHostPort(authority.substring(at + 1)) ?: return null

            var uuid = ""
            var password = ""
            val colon = userInfo.indexOf(':')
            if (colon >= 0) {
                uuid = urlDecode(userInfo.substring(0, colon))
                password = urlDecode(userInfo.substring(colon + 1))
            } else {
                uuid = urlDecode(userInfo)
            }
            if (password.isBlank()) password = params["password"] ?: ""
            if (uuid.isBlank()) uuid = params["uuid"] ?: ""
            if (uuid.isBlank()) return null

            val insecureRaw = params["allow_insecure"] ?: params["allowInsecure"] ?: params["insecure"]
            TuicBean(
                server = hp.first,
                serverPort = hp.second,
                uuid = uuid,
                password = password,
                congestionControl = params["congestion_control"] ?: params["congestion"] ?: "",
                udpRelayMode = params["udp_relay_mode"] ?: "",
                sni = params["sni"] ?: params["peer"] ?: "",
                alpn = params["alpn"] ?: "",
                allowInsecure = insecureRaw == "1" || insecureRaw?.equals("true", ignoreCase = true) == true,
                remarks = fragment
            )
        } catch (e: Throwable) {
            null
        }
    }

    // --- Hysteria (v1, legacy) -----------------------------------------------

    /**
     * Parse a legacy `hysteria://` link
     * (`hysteria://host:port?auth=…&peer=…&upmbps=…&downmbps=…&obfs=…&insecure=1#name`).
     * The auth string may also sit in the userinfo (`hysteria://auth@host:port`).
     * `hysteria2://` is handled by [parseHysteria2] and is not matched here.
     */
    @JvmStatic
    fun parseHysteria(link: String?): HysteriaBean? {
        if (link.isNullOrBlank()) return null
        return try {
            if (!link.startsWith(HYSTERIA_PROTOCOL, ignoreCase = true)) return null
            if (link.startsWith(HYSTERIA2_PROTOCOL, ignoreCase = true)) return null
            var body = link.substring(HYSTERIA_PROTOCOL.length)
            var fragment = ""
            val hash = body.indexOf('#')
            if (hash >= 0) {
                fragment = urlDecode(body.substring(hash + 1))
                body = body.substring(0, hash)
            }
            val queryIndex = body.indexOf('?')
            val authority = if (queryIndex >= 0) body.substring(0, queryIndex) else body
            val params = parseQuery(if (queryIndex >= 0) body.substring(queryIndex + 1) else "")

            var authFromUser = ""
            var hostPortRaw = authority
            val at = authority.lastIndexOf('@')
            if (at >= 0) {
                authFromUser = urlDecode(authority.substring(0, at))
                hostPortRaw = authority.substring(at + 1)
            }
            val hp = parseHostPort(hostPortRaw) ?: return null
            val auth = params["auth"] ?: params["auth_str"] ?: params["authStr"] ?: authFromUser
            val insecureRaw = params["insecure"] ?: params["allow_insecure"]
            HysteriaBean(
                server = hp.first,
                serverPort = hp.second,
                authStr = auth,
                obfs = params["obfs"] ?: "",
                sni = params["peer"] ?: params["sni"] ?: "",
                alpn = params["alpn"] ?: "",
                upMbps = params["upmbps"]?.toIntOrNull() ?: params["up_mbps"]?.toIntOrNull() ?: 0,
                downMbps = params["downmbps"]?.toIntOrNull() ?: params["down_mbps"]?.toIntOrNull() ?: 0,
                insecure = insecureRaw == "1" || insecureRaw?.equals("true", ignoreCase = true) == true,
                remarks = fragment
            )
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

    /** Appends `host:port`, bracketing IPv6 hosts. */
    private fun appendHostPort(sb: StringBuilder, host: String, port: Int) {
        if (host.contains(':') && !host.startsWith("[")) {
            sb.append('[').append(host).append(']')
        } else {
            sb.append(host)
        }
        sb.append(':').append(port)
    }

    /** Appends `&key=value` (no leading '&' for the first parameter). */
    private fun appendParam(sb: StringBuilder, key: String, value: String) {
        if (sb.isNotEmpty()) {
            sb.append('&')
        }
        sb.append(key).append('=').append(urlEncode(value))
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
