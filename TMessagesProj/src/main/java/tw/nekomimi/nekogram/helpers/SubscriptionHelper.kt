package tw.nekomimi.nekogram.helpers

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Subscription-URL support for the proxy importer.
 *
 * A "subscription" is a plain `http(s)://` URL that returns a body containing
 * proxy links — either as plain text (`vless://…` one per line) or as a
 * base64-encoded blob (the usual v2rayNG / Shadowrocket export). This helper
 * only performs the network half: it detects candidate URLs and downloads the
 * body. Decoding + parsing is delegated to [ProxyLinkParser], so a subscription
 * body goes through exactly the same pipeline as a pasted link.
 *
 * All methods are blocking and MUST be called off the UI thread.
 */
object SubscriptionHelper {

    private const val TAG = "SubscriptionHelper"

    /** Hard cap on the downloaded body, to keep a rogue server from OOMing us. */
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024

    private val URL_REGEX = Regex("(?i)https?://[^\\s\"'<>\\\\\\[\\]{}]+")

    /** URLs that already carry a proxy config and must never be fetched. */
    private val NON_SUBSCRIPTION = listOf(
        "t.me/proxy", "t.me/socks", "telegram.me/proxy", "telegram.me/socks"
    )

    private val TRAILING = charArrayOf('.', ',', ';', ':', ')', ']', '}', '"', '\'', '、', '，', '。', '；')

    /**
     * Every `http(s)://` URL in [text] that looks like a subscription endpoint.
     * Telegram proxy links and URLs that are themselves parseable proxy links are
     * excluded, as are duplicates.
     */
    @JvmStatic
    fun extractUrls(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        for (match in URL_REGEX.findAll(text)) {
            var url = match.value.trim()
            while (url.isNotEmpty() && url.last() in TRAILING) {
                url = url.substring(0, url.length - 1)
            }
            if (url.length < 10) continue
            val lower = url.lowercase()
            if (NON_SUBSCRIPTION.any { lower.contains(it) }) continue
            // A URL that is itself a proxy link (tg://… / t.me/socks) is handled
            // by the normal parser, not by a network fetch.
            if (ProxyLinkParser.parse(url).isNotEmpty()) continue
            out.add(url)
        }
        return out.toList()
    }

    /** True when [text] contains at least one fetchable subscription URL. */
    @JvmStatic
    fun hasSubscriptionUrl(text: String?): Boolean = extractUrls(text).isNotEmpty()

    /**
     * Downloads [url] and returns the body as UTF-8 text, or null on any failure
     * (DNS, timeout, non-2xx, TLS). Never throws.
     */
    @JvmStatic
    @JvmOverloads
    fun fetch(url: String, timeoutMs: Int = 15000): String? {
        var conn: HttpURLConnection? = null
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                requestMethod = "GET"
                // Subscription backends usually serve the raw node list only to
                // known clients; a browser UA tends to receive an HTML page.
                setRequestProperty("User-Agent", "v2rayNG/1.9.16")
                setRequestProperty("Accept", "*/*")
            }
            conn = connection
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "subscription HTTP $code for $url")
                return null
            }
            connection.inputStream.use { readBody(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "subscription fetch failed: ${t.javaClass.simpleName}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun readBody(stream: InputStream): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val read = stream.read(chunk)
            if (read <= 0) break
            buffer.write(chunk, 0, read)
            if (buffer.size() >= MAX_BODY_BYTES) break
        }
        return String(buffer.toByteArray(), Charsets.UTF_8)
    }
}
