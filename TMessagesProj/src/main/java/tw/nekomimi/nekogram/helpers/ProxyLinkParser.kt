package tw.nekomimi.nekogram.helpers

import android.util.Base64
import java.net.URLDecoder

/**
 * Unified parser for every flavour of proxy link / inline proxy text the user
 * can paste, scan as QR or pull from a subscription body.
 *
 * Recognised forms (in roughly this order):
 *  - sing-box node links:  `vless://`  `vmess://`  `trojan://`  `ss://`
 *                          `hysteria://`  `hysteria2://` (alias `hy2://`)  `tuic://`
 *  - native SOCKS5:        `socks5://…`  `tg://socks?…`  `https?://t.me/socks?…`
 *  - native MTProto:       `tg://proxy?…`  `https?://t.me/proxy?…`
 *  - bare `host:port` (optionally `:user:pass`) — defaults to SOCKS5
 *  - subscription bodies:  base64-encoded blob containing any of the above
 *                          (whole payload or one base64 line per node)
 *
 * Used by every import entry point (clipboard / QR / subscription) and the link
 * editor so a single implementation handles every format. The two output kinds
 * map 1:1 to SharedConfig storage: a [NodeLink] becomes a `SingProxy`
 * subclass, a [NativeConfig] becomes a stock native `ProxyInfo` row.
 */
object ProxyLinkParser {

    sealed class Parsed {
        /** sing-box node link → SingProxy subclass. */
        data class NodeLink(val kind: String, val link: String) : Parsed()
        /** Native SOCKS5 / MTProto row built from a tg:// link or bare host:port. */
        data class NativeConfig(
            val kind: String, // "socks5" or "mtproto"
            val address: String,
            val port: Int,
            val username: String,
            val password: String,
            val secret: String
        ) : Parsed()
    }

    // hysteria2 must precede the legacy hysteria alternative so the longer
    // scheme wins; the same applies to the ss/ssr pair.
    private val NODE_SCHEME_REGEX = Regex("(?i)\\b(vless|vmess|hy2|hysteria2|hysteria|trojan|ss|tuic)://\\S+")
    private val SOCKS_SCHEME_REGEX = Regex("(?i)\\b(socks5|socks)://[^\\s\\r\\n;]*")
    private val TG_SOCKS_REGEX = Regex("(?i)\\btg://socks\\?[^\\s\\r\\n;]*")
    private val TG_PROXY_REGEX = Regex("(?i)\\btg://proxy\\?[^\\s\\r\\n;]*")
    private val TME_SOCKS_REGEX = Regex("(?i)https?://t\\.me/socks\\?[^\\s\\r\\n;]*")
    private val TME_PROXY_REGEX = Regex("(?i)https?://t\\.me/proxy\\?[^\\s\\r\\n;]*")
    // host(IPv4/dns or bracketed IPv6) : port [: user : pass] at end of line
    private val BASE64_LINE_REGEX = Regex("^[-A-Za-z0-9+/=_]+$")
    // Whole-body base64: same alphabet but newlines/whitespace are tolerated.
    private val BASE64_BODY_REGEX = Regex("^[-A-Za-z0-9+/=_\\s]+$")
    private val BARE_HOST_REGEX = Regex(
        "^(?<host>(?:\\[[^\\]]+\\])|(?:[^:\\s\\[]+))(?::(?<port>\\d{1,5}))(?:(?::(?<u>[^:\\s]+))?(?::(?<p>[^:\\s]+))?)?$"
    )

    /** Normalizes common alias schemes to the canonical sing-box link form (hy2:// → hysteria2://). */
    @JvmStatic
    fun normalizeScheme(link: String?): String {
        if (link.isNullOrBlank()) return link ?: ""
        return if (link.startsWith("hy2://", ignoreCase = true)) {
            "hysteria2://" + link.substring("hy2://".length)
        } else {
            link
        }
    }

    @JvmStatic
    fun parse(text: String?): List<Parsed> {
        if (text.isNullOrBlank()) return emptyList()
        val out = LinkedHashMap<String, Parsed>()
        val sources = mutableListOf(text)
        runCatching {
            // Whole-payload base64 (a typical subscription body): base64 may
            // legitimately contain '/' and '+', so the only shape check is the
            // alphabet — anything with '://' or stray punctuation is skipped.
            val trimmed = text.trim()
            if (trimmed.length in 2..1_000_000 && !trimmed.contains("://") && BASE64_BODY_REGEX.matches(trimmed)) {
                val decoded = decodeBase64Body(trimmed)
                if (!decoded.isNullOrEmpty() && decoded.length >= 4 && decoded != trimmed) {
                    sources.add(decoded)
                }
            }
        }
        for (chunk in sources) {
            extractAll(chunk, out)
            for (line in chunk.split('\n', '\r')) {
                val trimmedLine = line.trim(' ', '\t', ';')
                if (trimmedLine.isEmpty()) continue
                // Some subscriptions base64-encode every line separately.
                if (!trimmedLine.contains("://") && trimmedLine.length >= 16 && BASE64_LINE_REGEX.matches(trimmedLine)) {
                    val decoded = decodeBase64Body(trimmedLine)
                    if (!decoded.isNullOrEmpty() && decoded.contains("://")) {
                        extractAll(decoded, out)
                        continue
                    }
                }
                if (trimmedLine.contains("://")) continue
                parseBareHost(trimmedLine)?.let { parsed ->
                    if (parsed is Parsed.NativeConfig) {
                        out.put("native:" + parsed.address + ":" + parsed.port, parsed)
                    }
                }
            }
        }
        return out.values.toList()
    }

    private fun extractAll(chunk: String, sink: MutableMap<String, Parsed>) {
        for (m in NODE_SCHEME_REGEX.findAll(chunk)) {
            val token = tokenAt(chunk, m.range.first)
            val kind = token.substringBefore("://", "").lowercase()
            sink.put("node:" + token, Parsed.NodeLink(kind, token))
        }
        for (m in SOCKS_SCHEME_REGEX.findAll(chunk)) {
            val token = tokenAt(chunk, m.range.first)
            parseSocksUri(token)?.let { sink.put("native:" + token, it) }
        }
        for (m in TG_SOCKS_REGEX.findAll(chunk)) {
            val token = tokenAt(chunk, m.range.first)
            parseTgSocks(token)?.let { sink.put("native:" + token, it) }
        }
        for (m in TG_PROXY_REGEX.findAll(chunk)) {
            val token = tokenAt(chunk, m.range.first)
            parseTgProxy(token)?.let { sink.put("native:" + token, it) }
        }
        for (m in TME_SOCKS_REGEX.findAll(chunk)) {
            val token = tokenAt(chunk, m.range.first)
            parseTgSocks(token)?.let { sink.put("native:" + token, it) }
        }
        for (m in TME_PROXY_REGEX.findAll(chunk)) {
            val token = tokenAt(chunk, m.range.first)
            parseTgProxy(token)?.let { sink.put("native:" + token, it) }
        }
    }

    private fun tokenAt(text: String, start: Int): String {
        var end = start
        while (end < text.length && text[end] != ' ' && text[end] != '\t' && text[end] != '\n' && text[end] != '\r' && text[end] != ';') {
            end++
        }
        var token = text.substring(start, end).trim()
        // Strip common trailing punctuation that pasted text/subscription
        // bodies often carry (commas, semicolons, quotes, brackets).
        while (token.isNotEmpty() && token.last() in ",;)]}>\"'、，。；）】") {
            token = token.substring(0, token.length - 1).trimEnd()
        }
        return token
    }

    /** `socks5://user:pass@host:port` / `socks://host:port` → native SOCKS5 row. */
    private fun parseSocksUri(token: String): Parsed? {
        val body = token.substringAfter("://")
        val authority = body.substringBefore('/').substringBefore('?').substringBefore('#')
        val at = authority.lastIndexOf('@')
        var user = ""
        var pass = ""
        var hostPort = authority
        if (at >= 0) {
            val userInfo = authority.substring(0, at)
            hostPort = authority.substring(at + 1)
            val colon = userInfo.indexOf(':')
            if (colon >= 0) {
                user = safeDecode(userInfo.substring(0, colon))
                pass = safeDecode(userInfo.substring(colon + 1))
            } else {
                user = safeDecode(userInfo)
            }
        }
        val colon = hostPort.lastIndexOf(':')
        if (colon < 0) return null
        val host = hostPort.substring(0, colon).trim('[', ']')
        val port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return Parsed.NativeConfig("socks5", host, port, user, pass, "")
    }

    private fun parseTgSocks(token: String): Parsed? {
        val params = queryParams(token.substringAfter('?', ""))
        val address = params["server"] ?: return null
        val port = params["port"]?.toIntOrNull() ?: return null
        return Parsed.NativeConfig("socks5", address, port, params["user"] ?: "", params["pass"] ?: "", "")
    }

    private fun parseTgProxy(token: String): Parsed? {
        val params = queryParams(token.substringAfter('?', ""))
        val address = params["server"] ?: return null
        val port = params["port"]?.toIntOrNull() ?: return null
        return Parsed.NativeConfig("mtproto", address, port, "", "", params["secret"] ?: "")
    }

    private fun queryParams(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in q.split('&')) {
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val k = safeDecode(pair.substring(0, eq))
                val v = safeDecode(pair.substring(eq + 1))
                out[k] = v
            }
        }
        return out
    }

    private fun safeDecode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Throwable) {
        s
    }

    /**
     * Decodes a base64 blob (whole subscription body or a single encoded line).
     * Tries the standard, url-safe and unpadded alphabets; returns null when none
     * of them yields readable text.
     */
    private fun decodeBase64Body(s: String): String? {
        if (s.isBlank()) return null
        val candidates = arrayOf(
            Base64.DEFAULT,
            Base64.NO_WRAP,
            Base64.NO_PADDING or Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        for (flags in candidates) {
            try {
                val decoded = String(Base64.decode(s, flags), Charsets.UTF_8)
                if (decoded.isNotBlank()) return decoded
            } catch (ignored: Throwable) {
            }
        }
        return null
    }

    private fun parseBareHost(line: String): Parsed? {
        val m = BARE_HOST_REGEX.find(line) ?: return null
        val rawHost = m.groups["host"]?.value ?: return null
        val port = m.groups["port"]?.value?.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        val host = if (rawHost.startsWith("[") && rawHost.endsWith("]")) {
            rawHost.substring(1, rawHost.length - 1)
        } else {
            if (rawHost.isEmpty() || rawHost.contains(' ')) return null
            rawHost
        }
        return Parsed.NativeConfig("socks5", host, port, m.groups["u"]?.value ?: "", m.groups["p"]?.value ?: "", "")
    }
}
