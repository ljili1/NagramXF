package tw.nekomimi.nekogram.helpers

import android.util.Base64
import java.net.URLDecoder

/**
 * Unified parser for every flavour of proxy link / inline proxy text the user
 * can paste, scan as QR or pull from a subscription body.
 *
 * Recognised forms (in roughly this order):
 *  - sing-box node links:  `vless://`  `trojan://`  `ss://`  `hysteria2://`
 *  - native SOCKS5:        `tg://socks?…`  `https?://t.me/socks?…`
 *  - native MTProto:       `tg://proxy?…`  `https?://t.me/proxy?…`
 *  - bare `host:port` (optionally `:user:pass`) — defaults to SOCKS5
 *  - subscription bodies:  base64-encoded blob containing any of the above
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

    private val NODE_SCHEME_REGEX = Regex("(?i)\\b(vless|trojan|ss|hysteria2)://\\S+")
    private val TG_SOCKS_REGEX = Regex("(?i)\\btg://socks\\?[^\\s\\r\\n;]*")
    private val TG_PROXY_REGEX = Regex("(?i)\\btg://proxy\\?[^\\s\\r\\n;]*")
    private val TME_SOCKS_REGEX = Regex("(?i)https?://t\\.me/socks\\?[^\\s\\r\\n;]*")
    private val TME_PROXY_REGEX = Regex("(?i)https?://t\\.me/proxy\\?[^\\s\\r\\n;]*")
    // host(IPv4/dns or bracketed IPv6) : port [: user : pass] at end of line
    private val BARE_HOST_REGEX = Regex(
        "^(?<host>(?:\\[[^\\]]+\\])|(?:[^:\\s\\[]+))(?::(?<port>\\d{1,5}))(?:(?::(?<u>[^:\\s]+))?(?::(?<p>[^:\\s]+))?)?$"
    )

    @JvmStatic
    fun parse(text: String?): List<Parsed> {
        if (text.isNullOrBlank()) return emptyList()
        val out = LinkedHashMap<String, Parsed>()
        val sources = mutableListOf(text)
        runCatching {
            val trimmed = text.trim()
            if (trimmed.length in 2..20000 && !trimmed.contains("://") && !trimmed.contains('/')) {
                val decoded = String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8)
                if (decoded.length >= 4) {
                    sources.add(decoded)
                }
            }
        }
        for (chunk in sources) {
            extractAll(chunk, out)
            for (line in chunk.split('\n', '\r')) {
                val trimmedLine = line.trim(' ', '\t', ';')
                if (trimmedLine.isEmpty() || trimmedLine.contains("://")) continue
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
        return text.substring(start, end).trim()
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
