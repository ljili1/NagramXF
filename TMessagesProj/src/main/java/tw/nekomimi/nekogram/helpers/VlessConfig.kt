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
 * - `vmess://`      -> sing-box "vmess"
 * - `trojan://`     -> sing-box "trojan"
 * - `ss://`         -> sing-box "shadowsocks"
 * - `hysteria://`   -> sing-box "hysteria" (legacy v1)
 * - `hysteria2://`  -> sing-box "hysteria2"
 * - `tuic://`       -> sing-box "tuic"
 *
 * The inbound / route template is identical for every type, so switching a node
 * is a pure hot reload of the outbound.
 */
object VlessConfig {

    /** Prefixes for which an outbound config can be produced. */
    private val SUPPORTED_PREFIXES = arrayOf(
        "vless://", "vmess://", "trojan://", "ss://",
        "hysteria2://", "hysteria://", "tuic://"
    )

    /** Tag of the resolver used for the proxy server's own domain. */
    private const val DNS_DIRECT_TAG = "dns-direct"

    /**
     * Bootstrap resolver for the proxy server address.
     *
     * It has to be a plain IP (it is used *before* any proxy exists, so it cannot
     * go through the proxy) and sing-box dials it directly. A domestic public
     * resolver is used because it is reliably reachable on Chinese networks, while
     * the common 8.8.8.8 / 1.1.1.1 are frequently unreachable or poisoned there -
     * and a resolver that cannot be reached means the node can never be dialled.
     */
    private const val BOOTSTRAP_DNS = "223.5.5.5"

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

        // Since sing-box 1.14 an outbound whose `server` is a *domain* needs a
        // domain_resolver (or route.default_domain_resolver) - without one the
        // server address is never resolved and the node cannot connect at all.
        // Node links overwhelmingly use domain servers, so this is the difference
        // between "node works" and "node is dead".
        //
        // Only emitted when the server really is a domain: for an IP literal there
        // is nothing to resolve and the config is left exactly as before.
        val serverHost = outbound.optString("server", "")
        if (serverHost.isNotBlank() && !isIpLiteral(serverHost)) {
            val dnsServer = JSONObject()
                .put("type", "udp")
                .put("tag", DNS_DIRECT_TAG)
                .put("server", BOOTSTRAP_DNS)
                .put("server_port", 53)
            val servers = JSONArray()
            servers.put(dnsServer)
            val dns = JSONObject()
                .put("servers", servers)
                // A node whose domain has a stale AAAA record would otherwise be
                // dialled over IPv6 first, stall until the whole probe times out and
                // be reported as unavailable although it works fine elsewhere.
                .put("strategy", "prefer_ipv4")
            config.put("dns", dns)
            outbound.put("domain_resolver", DNS_DIRECT_TAG)
        }

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
            trimmed.startsWith("vmess://", ignoreCase = true) -> buildVmessOutbound(ProxyParse.parseVmess(trimmed))
            trimmed.startsWith("trojan://", ignoreCase = true) -> buildTrojanOutbound(ProxyParse.parseTrojan(trimmed))
            trimmed.startsWith("ss://", ignoreCase = true) -> buildShadowsocksOutbound(ProxyParse.parseSs(trimmed))
            // hysteria2 must be tested before the legacy hysteria prefix.
            trimmed.startsWith("hysteria2://", ignoreCase = true) -> buildHysteria2Outbound(ProxyParse.parseHysteria2(trimmed))
            trimmed.startsWith("hysteria://", ignoreCase = true) -> buildHysteriaOutbound(ProxyParse.parseHysteria(trimmed))
            trimmed.startsWith("tuic://", ignoreCase = true) -> buildTuicOutbound(ProxyParse.parseTuic(trimmed))
            else -> null
        }
    }

    /**
     * Build a sing-box "vmess" outbound from a parsed [ProxyParse.VmessBean].
     *
     * The transport block mirrors the VLESS builder (ws / grpc / h2 / httpupgrade);
     * `alter_id` and `security` are emitted as-is so AEAD (`alter_id: 0`) and
     * legacy (`aid > 0`) servers both work.
     */
    @JvmStatic
    fun buildVmessOutbound(bean: ProxyParse.VmessBean?): JSONObject? {
        if (bean == null) return null
        if (bean.address.isBlank() || bean.port <= 0 || bean.port > 65535 || bean.uuid.isBlank()) return null

        val outbound = JSONObject()
        outbound.put("type", "vmess")
        outbound.put("tag", "proxy")
        outbound.put("server", bean.address)
        outbound.put("server_port", bean.port)
        outbound.put("uuid", bean.uuid)
        outbound.put("security", normalizeVmessSecurity(bean.security))
        outbound.put("alter_id", bean.alterId)

        if (bean.tls) {
            // v2rayN links usually carry the CDN vhost in `host` (the ws Host
            // header) with no separate `sni`: TLS must be routed to that vhost,
            // not to the raw server address, or the handshake dies before any
            // proxy traffic. Prefer sni, then host, then the server address.
            val sni = bean.sni.ifBlank { bean.host }.ifBlank { bean.address }
            outbound.put("tls", buildTls(sni, bean.alpn, bean.fingerprint, false))
        }

        when (bean.network.lowercase()) {
            "ws" -> {
                val transport = JSONObject()
                transport.put("type", "ws")
                if (bean.path.isNotBlank()) transport.put("path", bean.path)
                if (bean.host.isNotBlank()) transport.put("headers", JSONObject().put("Host", bean.host))
                outbound.put("transport", transport)
            }
            "grpc" -> {
                val transport = JSONObject()
                transport.put("type", "grpc")
                // v2rayN stores the gRPC service name in `path`.
                if (bean.path.isNotBlank()) transport.put("service_name", bean.path)
                outbound.put("transport", transport)
            }
            "h2", "http" -> {
                val transport = JSONObject()
                transport.put("type", "http")
                if (bean.host.isNotBlank()) transport.put("host", JSONArray().put(bean.host))
                if (bean.path.isNotBlank()) transport.put("path", bean.path)
                outbound.put("transport", transport)
            }
            "httpupgrade" -> {
                val transport = JSONObject()
                transport.put("type", "httpupgrade")
                if (bean.host.isNotBlank()) transport.put("host", bean.host)
                if (bean.path.isNotBlank()) transport.put("path", bean.path)
                outbound.put("transport", transport)
            }
            "quic" -> outbound.put("transport", JSONObject().put("type", "quic"))
            else -> Unit // tcp: no transport block
        }
        return outbound
    }

    /**
     * Build a sing-box "tuic" outbound (TUIC v5). QUIC+TLS is mandatory; ALPN
     * defaults to `h3` because every TUIC server speaks HTTP/3 framing.
     */
    @JvmStatic
    fun buildTuicOutbound(bean: ProxyParse.TuicBean?): JSONObject? {
        if (bean == null) return null
        if (bean.server.isBlank() || bean.serverPort <= 0 || bean.serverPort > 65535) return null
        if (bean.uuid.isBlank()) return null

        val outbound = JSONObject()
        outbound.put("type", "tuic")
        outbound.put("tag", "proxy")
        outbound.put("server", bean.server)
        outbound.put("server_port", bean.serverPort)
        outbound.put("uuid", bean.uuid)
        if (bean.password.isNotBlank()) outbound.put("password", bean.password)
        // Only values sing-box defines are forwarded; an unknown enumeration value
        // is rejected with the whole config, so the node would never start.
        val congestion = bean.congestionControl.trim().lowercase()
        if (congestion == "cubic" || congestion == "new_reno" || congestion == "bbr") {
            outbound.put("congestion_control", congestion)
        }
        val udpRelayMode = bean.udpRelayMode.trim().lowercase()
        if (udpRelayMode == "native" || udpRelayMode == "quic") {
            outbound.put("udp_relay_mode", udpRelayMode)
        }
        outbound.put("tls", buildTls(bean.sni.ifBlank { bean.server }, bean.alpn.ifBlank { "h3" }, "", bean.allowInsecure))
        return outbound
    }

    /**
     * Build a sing-box legacy "hysteria" (v1) outbound. The bandwidth hints are
     * emitted when the link carries them; otherwise sing-box falls back to its
     * own congestion-control defaults.
     */
    @JvmStatic
    fun buildHysteriaOutbound(bean: ProxyParse.HysteriaBean?): JSONObject? {
        if (bean == null) return null
        if (bean.server.isBlank() || bean.serverPort <= 0 || bean.serverPort > 65535) return null
        if (bean.authStr.isBlank()) return null

        val outbound = JSONObject()
        outbound.put("type", "hysteria")
        outbound.put("tag", "proxy")
        outbound.put("server", bean.server)
        outbound.put("server_port", bean.serverPort)
        outbound.put("auth_str", bean.authStr)
        if (bean.upMbps > 0) outbound.put("up_mbps", bean.upMbps)
        if (bean.downMbps > 0) outbound.put("down_mbps", bean.downMbps)
        if (bean.obfs.isNotBlank()) outbound.put("obfs", bean.obfs)
        outbound.put("tls", buildTls(bean.sni.ifBlank { bean.server }, bean.alpn.ifBlank { "h3" }, "", bean.insecure))
        return outbound
    }

    /** Shared TLS block builder for the stream/QUIC protocols. */
    private fun buildTls(serverName: String, alpn: String, fingerprint: String, insecure: Boolean): JSONObject {
        val tls = JSONObject()
        tls.put("enabled", true)
        if (serverName.isNotBlank()) tls.put("server_name", serverName)
        val alpnArray = JSONArray()
        for (item in alpn.split(',')) {
            val trimmed = item.trim()
            if (trimmed.isNotEmpty()) alpnArray.put(trimmed)
        }
        if (alpnArray.length() > 0) tls.put("alpn", alpnArray)
        if (fingerprint.isNotBlank()) {
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint))
        }
        if (insecure) tls.put("insecure", true)
        return tls
    }

    /** VMess ciphers sing-box accepts; anything else is rejected at startup. */
    private val VMESS_SECURITY = setOf(
        "auto", "none", "zero", "aes-128-gcm", "chacha20-poly1305"
    )

    /**
     * Maps a link's `scy` / `security` onto a cipher sing-box knows. Link
     * generators commonly emit `aes-256-gcm` (a Shadowsocks cipher, not a VMess
     * one); forwarding it made the engine refuse the whole config, so the node
     * looked dead. Unknown values fall back to `auto`, which the server-side
     * negotiation accepts.
     */
    private fun normalizeVmessSecurity(raw: String): String {
        val value = raw.trim().lowercase()
        return if (value in VMESS_SECURITY) value else "auto"
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
            val uuid = decode(authority.substring(0, at))
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
            // Pre-flight validation: never hand the native engine a config with an
            // empty identity/address (a rejected outbound can SIGABRT the process,
            // which no Java catch can contain).
            if (host.isBlank() || port <= 0 || port > 65535 || uuid.isBlank()) return null

            val params = parseQuery(query)

            val outbound = JSONObject()
            outbound.put("type", "vless")
            outbound.put("tag", "proxy")
            outbound.put("server", host)
            outbound.put("server_port", port)
            outbound.put("uuid", uuid)

            val flow = params["flow"]
            if (flow != null && flow.equals("xtls-rprx-vision", ignoreCase = true)) {
                // sing-box only accepts xtls-rprx-vision; an unknown flow value
                // (e.g. the legacy xtls-rprx-vision-udp443) is rejected at
                // startup and the whole node appears "unable to connect".
                outbound.put("flow", "xtls-rprx-vision")
            }

            val security = (params["security"] ?: "none").lowercase()
            if (security != "none") {
                val tls = JSONObject()
                tls.put("enabled", true)
                // Many providers only set `host` (the ws Host header) without a
                // separate `sni`. For TLS the server_name must be the CDN/SNI
                // domain, so fall back host -> sni exactly like v2rayN does;
                // otherwise the TLS handshake is routed to the wrong vhost and
                // the connection dies before any proxy traffic.
                val sni = params["sni"]?.ifBlank { null } ?: params["host"]?.ifBlank { null }
                if (!sni.isNullOrBlank()) tls.put("server_name", sni)
                val fp = params["fp"]
                if (!fp.isNullOrBlank()) {
                    val utls = JSONObject()
                    utls.put("enabled", true)
                    utls.put("fingerprint", fp)
                    tls.put("utls", utls)
                }
                if (security == "reality") {
                    val pbk = params["pbk"]
                    if (!pbk.isNullOrBlank()) {
                        val reality = JSONObject()
                        reality.put("enabled", true)
                        reality.put("public_key", pbk)
                        val sid = params["sid"]
                        if (!sid.isNullOrBlank()) reality.put("short_id", sid)
                        tls.put("reality", reality)
                    }
                    // reality without public_key cannot handshake; emit plain TLS
                    // rather than a config the engine rejects at startup.
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
            } else if (type == "h2") {
                val transport = JSONObject()
                transport.put("type", "http")
                val h2Host = params["host"]
                if (!h2Host.isNullOrBlank()) transport.put("host", JSONArray().put(h2Host))
                val h2Path = params["path"]
                if (!h2Path.isNullOrBlank()) transport.put("path", h2Path)
                outbound.put("transport", transport)
            } else if (type == "http") {
                val transport = JSONObject()
                transport.put("type", "httpupgrade")
                val huHost = params["host"]
                if (!huHost.isNullOrBlank()) transport.put("host", huHost)
                val huPath = params["path"]
                if (!huPath.isNullOrBlank()) transport.put("path", huPath)
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
        if (bean.address.isBlank() || bean.port <= 0 || bean.port > 65535 || bean.password.isBlank()) return null
        val outbound = JSONObject()
        outbound.put("type", "trojan")
        outbound.put("tag", "proxy")
        outbound.put("server", bean.address)
        outbound.put("server_port", bean.port)
        outbound.put("password", bean.password)
        // Same SNI policy as vless / vmess: providers usually carry the CDN vhost
        // in `host` and no separate `sni`, and terminating TLS with the raw server
        // address sends the handshake to the wrong vhost.
        outbound.put(
            "tls",
            buildTls(bean.sni.ifBlank { bean.host }.ifBlank { bean.address }, bean.alpn, "", bean.insecure)
        )
        // Transport: a Trojan server that only listens on ws / grpc / h2 is
        // unreachable with a plain TCP outbound.
        when (bean.network.trim().lowercase()) {
            "ws" -> {
                val transport = JSONObject()
                transport.put("type", "ws")
                if (bean.path.isNotBlank()) transport.put("path", bean.path)
                if (bean.host.isNotBlank()) transport.put("headers", JSONObject().put("Host", bean.host))
                outbound.put("transport", transport)
            }
            "grpc" -> {
                val transport = JSONObject()
                transport.put("type", "grpc")
                val serviceName = bean.serviceName.ifBlank { bean.path }
                if (serviceName.isNotBlank()) transport.put("service_name", serviceName)
                outbound.put("transport", transport)
            }
            "h2", "http" -> {
                val transport = JSONObject()
                transport.put("type", "http")
                if (bean.host.isNotBlank()) transport.put("host", JSONArray().put(bean.host))
                if (bean.path.isNotBlank()) transport.put("path", bean.path)
                outbound.put("transport", transport)
            }
            "httpupgrade" -> {
                val transport = JSONObject()
                transport.put("type", "httpupgrade")
                if (bean.host.isNotBlank()) transport.put("host", bean.host)
                if (bean.path.isNotBlank()) transport.put("path", bean.path)
                outbound.put("transport", transport)
            }
            else -> Unit // tcp / empty: no transport block
        }
        return outbound
    }

    /** Build a sing-box "shadowsocks" outbound, including the SIP002 plugin. */
    @JvmStatic
    fun buildShadowsocksOutbound(bean: ProxyParse.SsBean?): JSONObject? {
        if (bean == null) return null
        if (bean.host.isBlank() || bean.remotePort <= 0 || bean.remotePort > 65535) return null
        if (bean.password.isBlank() || bean.method.isBlank()) return null
        val outbound = JSONObject()
        outbound.put("type", "shadowsocks")
        outbound.put("tag", "proxy")
        outbound.put("server", bean.host)
        outbound.put("server_port", bean.remotePort)
        outbound.put("method", ProxyParse.normalizeMethod(bean.method))
        outbound.put("password", bean.password)
        val plugin = parseSsPlugin(bean.plugin)
        if (plugin != null) {
            outbound.put("plugin", plugin.first)
            if (plugin.second.isNotBlank()) {
                outbound.put("plugin_opts", plugin.second)
            }
        }
        return outbound
    }

    /**
     * Maps an SIP002 `plugin` value onto sing-box's shadowsocks `plugin` /
     * `plugin_opts` pair.
     *
     * sing-box knows `obfs-local` (simple-obfs) and `v2ray-plugin`; everything
     * after the plugin name is passed through as plugin options. A plugin sing-box
     * does not know is dropped instead of forwarded — an unknown plugin makes the
     * engine reject the whole config, which shows up as a node that "cannot
     * connect" while a plain (plugin-less) outbound would at least start.
     */
    private fun parseSsPlugin(raw: String): Pair<String, String>? {
        if (raw.isBlank()) return null
        val parts = raw.split(';')
        val name = parts[0].trim().lowercase()
        val opts = parts.drop(1).joinToString(";").trim()
        return when {
            name.contains("obfs") -> "obfs-local" to opts
            name.contains("v2ray") -> "v2ray-plugin" to opts
            else -> null
        }
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

        // Hysteria2 only defines the `salamander` obfuscator. A link carrying any
        // other value is emitted without an obfs block instead of a block the
        // engine rejects (which would make the node look unable to connect even
        // though everything else about it is valid).
        if (bean.obfs.trim().equals("salamander", ignoreCase = true)) {
            val obfs = JSONObject()
            obfs.put("type", "salamander")
            if (bean.obfsPassword.isNotBlank()) {
                obfs.put("password", bean.obfsPassword)
            }
            outbound.put("obfs", obfs)
        }
        return outbound
    }

    /**
     * True when [host] is an IP literal (IPv4 or IPv6): the outbound server then
     * needs no DNS resolution and no resolver is emitted for it.
     */
    private fun isIpLiteral(host: String): Boolean {
        val value = host.trim().trim('[', ']')
        if (value.isEmpty()) return false
        if (value.contains(':')) return true // IPv6 literal
        val parts = value.split('.')
        if (parts.size != 4) return false
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return false
            if (octet < 0 || octet > 255) return false
        }
        return true
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
