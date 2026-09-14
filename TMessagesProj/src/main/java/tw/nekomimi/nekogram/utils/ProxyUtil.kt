@file:Suppress("UNCHECKED_CAST")

package tw.nekomimi.nekogram.utils

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Environment
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.createBitmap
import androidx.core.view.setPadding
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.WriterException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.TelegramQRCodeWriter
import org.telegram.messenger.browser.Browser
import tw.nekomimi.nekogram.helpers.ProxyLinkParser
import tw.nekomimi.nekogram.helpers.ProxyTypes
import tw.nekomimi.nekogram.helpers.SubscriptionHelper
import tw.nekomimi.nekogram.ui.BottomBuilder
import tw.nekomimi.nekogram.utils.AlertUtil.showToast
import java.io.File


object ProxyUtil {

    private var networkCallbackRegistered = false

    @JvmStatic
    fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        networkCallbackRegistered = true

        val connectivityManager = ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCallback: ConnectivityManager.NetworkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val networkCapabilities =
                        connectivityManager.getNetworkCapabilities(network) ?: return
                    val vpn = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    if (!vpn) {
                        if (SharedConfig.currentProxy == null) {
                            if (!SharedConfig.proxyList.isEmpty()) {
                                SharedConfig.setCurrentProxy(SharedConfig.proxyList[0])
                            } else {
                                return
                            }
                        }
                    }
                    if ((SharedConfig.isProxyEnabled() && vpn) || (!SharedConfig.isProxyEnabled() && !vpn)) {
                        SharedConfig.setProxyEnable(!vpn)
                        AndroidUtilities.runOnUIThread {
                            NotificationCenter.getGlobalInstance()
                                .postNotificationName(NotificationCenter.proxySettingsChanged)
                        }
                    }
                }
            }

        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }

    @JvmStatic
    fun getOwnerActivity(ctx: Context): Activity {

        if (ctx is Activity) return ctx

        if (ctx is ContextWrapper) return getOwnerActivity(ctx.baseContext)

        error("unable cast ${ctx.javaClass.name} to activity")

    }

    @JvmStatic
    @JvmOverloads
    fun showQrDialog(ctx: Context, text: String, icon: ((Int) -> Bitmap)? = null): AlertDialog {

        val code = createQRCode(text, icon = icon)

        ctx.setTheme(R.style.Theme_TMessages)

        return AlertDialog.Builder(ctx).setView(LinearLayout(ctx).apply {

            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)

            addView(LinearLayout(ctx).apply {
                val root = this

                gravity = Gravity.CENTER
                setBackgroundColor(Color.WHITE)
                setPadding(AndroidUtilities.dp(16f))

                val width = AndroidUtilities.dp(260f)

                addView(ImageView(ctx).apply {

                    setImageBitmap(code)

                    scaleType = ImageView.ScaleType.FIT_XY

                    setOnLongClickListener {

                        val builder = BottomBuilder(ctx)

                        builder.addItems(arrayOf(

                                getString(R.string.SaveToGallery),
                                getString(R.string.Cancel)

                        ), intArrayOf(

                                R.drawable.msg_gallery,
                                R.drawable.msg_cancel

                        )) { i, _, _ ->

                            if (i == 0) {

                                if (ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

                                    getOwnerActivity(ctx).requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 4)

                                    return@addItems

                                }

                                val saveTo = File(Environment.getExternalStorageDirectory(), "${Environment.DIRECTORY_PICTURES}/share_${text.hashCode()}.jpg")

                                saveTo.parentFile?.mkdirs()

                                runCatching {

                                    saveTo.createNewFile()

                                    saveTo.outputStream().use {

                                        loadBitmapFromView(root).compress(Bitmap.CompressFormat.JPEG, 100, it)

                                    }

                                    AndroidUtilities.addMediaToGallery(saveTo.path)
                                    showToast(getString(R.string.PhotoSavedHint))

                                }.onFailure {
                                    FileLog.e(it)
                                    showToast(it)
                                }

                            }

                        }

                        builder.show()

                        return@setOnLongClickListener true

                    }

                }, LinearLayout.LayoutParams(width, width))

            }, LinearLayout.LayoutParams(-2, -2).apply {

                gravity = Gravity.CENTER

            })

        }).create().apply {

            show()
            window?.setBackgroundDrawableResource(android.R.color.transparent)

        }

    }

    private fun loadBitmapFromView(v: View): Bitmap {
        val b = createBitmap(v.width, v.height)
        val c = Canvas(b)
        v.layout(v.left, v.top, v.right, v.bottom)
        v.draw(c)
        return b
    }

    @JvmStatic
    fun createQRCode(text: String, size: Int = 768, icon: ((Int) -> Bitmap)? = null): Bitmap {
        return try {
            val hints = HashMap<EncodeHintType, Any>()
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
            val writer = TelegramQRCodeWriter()
            val qrBitmap = writer.encode(text, size, size, hints, null)
            if (icon != null) {
                val iconBitmap = icon(writer.imageSize)
                val canvas = Canvas(qrBitmap)
                canvas.drawBitmap(iconBitmap, (qrBitmap.width - iconBitmap.width) / 2f, (qrBitmap.height - iconBitmap.height) / 2f, null)
                if (iconBitmap != qrBitmap) {
                    iconBitmap.recycle()
                }
            }
            qrBitmap
        } catch (e: WriterException) {
            FileLog.e(e)
            createBitmap(size, size)
        }
    }

    val qrReader = QRCodeReader()

    @JvmStatic
    fun tryReadQR(ctx: Activity, bitmap: Bitmap) {

        val intArray = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(intArray, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, intArray)

        try {

            val result = try {
                qrReader.decode(BinaryBitmap(GlobalHistogramBinarizer(source)), mapOf(
                        DecodeHintType.TRY_HARDER to true
                ))
            } catch (_: NotFoundException) {
                qrReader.decode(BinaryBitmap(GlobalHistogramBinarizer(source.invert())), mapOf(
                        DecodeHintType.TRY_HARDER to true
                ))
            }

            showLinkAlert(ctx, result.text)

        } catch (_: Throwable) {

            showToast(getString(R.string.NoQrFound))

        }

    }

    @JvmStatic
    @JvmOverloads
    fun showLinkAlert(ctx: Activity, text: String, tryInternal: Boolean = true) {

        val builder = BottomBuilder(ctx)

        if (tryInternal) {
            runCatching {
                if (Browser.isInternalUrl(text, booleanArrayOf(false))) {
                    Browser.openUrl(ctx, text)
                    return
                }
            }
        }

        builder.addTitle(text)

        builder.addItems(arrayOf(
                getString(R.string.Open),
                getString(R.string.Copy),
                getString(R.string.ShareQRCode)
        ), intArrayOf(
                R.drawable.web_browser,
                R.drawable.msg_copy,
                R.drawable.msg_qrcode
        )) { which, _, _ ->
            when (which) {
                0 -> Browser.openUrl(ctx, text)
                1 -> {
                    AndroidUtilities.addToClipboard(text)
                    showToast(getString(R.string.LinkCopied))
                }
                else -> showQrDialog(ctx, text)
            }
        }

        builder.show()

    }

    @JvmStatic
    fun importFromClipboard(ctx: Activity) {
        val text = clipboardText(ctx)
        if (text.isNullOrBlank()) {
            showToast(getString(R.string.BrokenLink))
            return
        }
        val urls = SubscriptionHelper.extractUrls(text)
        if (urls.isEmpty()) {
            applyImport(ctx, ProxyLinkParser.parse(text))
            return
        }
        // A subscription URL needs a network round-trip: fetch on a worker
        // thread, then apply the parsed nodes back on the UI thread.
        showToast(getString(R.string.SubscriptionFetching))
        Thread {
            val all = ArrayList<ProxyLinkParser.Parsed>()
            all.addAll(ProxyLinkParser.parse(text))
            var fetchedAny = false
            for (url in urls) {
                val body = SubscriptionHelper.fetch(url) ?: continue
                fetchedAny = true
                all.addAll(ProxyLinkParser.parse(body))
            }
            AndroidUtilities.runOnUIThread {
                if (!fetchedAny && all.isEmpty()) {
                    showToast(getString(R.string.VlessFetchFailed))
                    return@runOnUIThread
                }
                applyImport(ctx, all)
            }
        }.start()
    }

    /**
     * Adds every parsed entry to [SharedConfig] and reports a summary. Must run
     * on the UI thread (it mutates the shared proxy list and shows a dialog).
     */
    @JvmStatic
    fun applyImport(ctx: Activity, parsed: List<ProxyLinkParser.Parsed>) {
        if (parsed.isEmpty()) {
            showToast(getString(R.string.BrokenLink))
            return
        }
        val nativeAdded = mutableListOf<String>()
        val singAdded = mutableListOf<String>()
        for (p in parsed) {
            when (p) {
                is ProxyLinkParser.Parsed.NodeLink -> {
                    val link = ProxyLinkParser.normalizeScheme(p.link)
                    if (!ProxyTypes.isSupported(link)) continue
                    val obj = SharedConfig.createNodeProxy(link) ?: continue
                    if (SharedConfig.proxyList.none { it == obj }) {
                        SharedConfig.addProxy(obj)
                        singAdded.add(obj.getAddressLine())
                    }
                }
                is ProxyLinkParser.Parsed.NativeConfig -> {
                    val existing = SharedConfig.proxyList.any {
                        it.address == p.address && it.port == p.port &&
                                it.username == p.username && it.password == p.password && it.secret == p.secret
                    }
                    if (existing) continue
                    val info = SharedConfig.ProxyInfo(p.address, p.port, p.username, p.password, p.secret)
                    SharedConfig.addProxy(info)
                    nativeAdded.add(info.address)
                }
            }
        }
        if (nativeAdded.isEmpty() && singAdded.isEmpty()) {
            showToast(getString(R.string.BrokenLink))
            return
        }
        val summary = buildString {
            if (nativeAdded.isNotEmpty()) {
                append(getString(R.string.ImportedProxies))
                append("\n\n")
                append(nativeAdded.joinToString("\n"))
            }
            if (singAdded.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(getString(R.string.VlessNodesAdded).replace("%1\$d", singAdded.size.toString()))
                append("\n\n")
                append(singAdded.joinToString("\n"))
            }
        }
        AlertUtil.showSimpleAlert(ctx, summary)
        AndroidUtilities.runOnUIThread {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged)
        }
    }

    /**
     * Blocking. Returns [text] plus the downloaded body of every subscription URL
     * it contains, so the caller can parse everything in a single pass. Returns
     * [text] unchanged when it holds no subscription URL. Never throws.
     */
    @JvmStatic
    fun expandSubscriptions(text: String?): String {
        if (text.isNullOrBlank()) return text ?: ""
        val urls = SubscriptionHelper.extractUrls(text)
        if (urls.isEmpty()) return text
        val builder = StringBuilder(text)
        for (url in urls) {
            val body = SubscriptionHelper.fetch(url) ?: continue
            builder.append('\n').append(body)
        }
        return builder.toString()
    }

    /** True when [text] holds a subscription URL that must be fetched first. */
    @JvmStatic
    fun isSubscriptionText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        if (ProxyLinkParser.parse(text).isNotEmpty()) return false
        return SubscriptionHelper.hasSubscriptionUrl(text)
    }

    @JvmStatic
    fun clipboardText(context: Context?): String? {
        if (context == null) {
            return null
        }
        return runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull()
    }

    /**
     * Imports every supported sing-box node link (vless/trojan/ss/hysteria2)
     * found in [text] as a SharedConfig node proxy object.
     * @return number of nodes newly added
     */
    @JvmStatic
    fun importSingProxies(text: String?): Int {
        if (text.isNullOrBlank()) {
            return 0
        }
        var added = 0
        runCatching {
            for (p in ProxyLinkParser.parse(text)) {
                if (p !is ProxyLinkParser.Parsed.NodeLink) continue
                val link = ProxyLinkParser.normalizeScheme(p.link)
                if (!ProxyTypes.isSupported(link)) continue
                val created = SharedConfig.createNodeProxy(link) ?: continue
                if (SharedConfig.proxyList.none { it == created }) {
                    SharedConfig.addProxy(created)
                    added++
                }
            }
        }.onFailure {
            FileLog.e(it)
        }
        if (added > 0) {
            AndroidUtilities.runOnUIThread {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged)
            }
        }
        return added
    }

    @JvmStatic
    fun isIpv6Address(value: String): Boolean {
        var addr = value
        if (addr.indexOf("[") == 0 && addr.lastIndexOf("]") > 0) {
            addr = addr.drop(1)
            addr = addr.dropLast(addr.count() - addr.lastIndexOf("]"))
        }
        val regV6 = Regex("^([0-9A-Fa-f]{1,4})?(:[0-9A-Fa-f]{1,4})*::([0-9A-Fa-f]{1,4})?(:[0-9A-Fa-f]{1,4})*|([0-9A-Fa-f]{1,4})(:[0-9A-Fa-f]{1,4}){7}$")
        return regV6.matches(addr)
    }

    // --- Proxy-link extraction (ported subset of Nekogram X 9.3.3 ProxyUtil) ---
    // Adapted from Nekogram X 9.3.3 (GPL-3.0): pulls every standard proxy link
    // token out of a pasted text / subscription body / QR payload. Native
    // Telegram proxy links (tg://proxy etc.) are handled by the existing native
    // import path and intentionally left out of this extractor. Every scheme the
    // sing-box engine carries is listed here (vless/vmess/trojan/ss/hysteria/
    // hysteria2/tuic); `ssr` is kept so the caller can report it as unsupported.

    private val proxySchemeRegex = Regex(
        "(vless|vmess|trojan|ss|hysteria2|hysteria|tuic|ssr|socks|ws|wss)://",
        RegexOption.IGNORE_CASE
    )

    /** Extracts every proxy:// link token from [text] (best-effort unique). */
    @JvmStatic
    fun parseProxies(text: String): MutableList<String> {
        val out = mutableListOf<String>()
        extractProxyLinks(text, out)
        if (out.isEmpty() && text.isNotBlank()) {
            // Some subscriptions ship the whole payload as a single base64 blob.
            runCatching {
                val decoded = String(Base64.decode(text.trim(), Base64.DEFAULT), Charsets.UTF_8)
                if (decoded != text) {
                    extractProxyLinks(decoded, out)
                }
            }.onFailure {
                FileLog.e(it)
            }
        }
        return out
    }

    private fun extractProxyLinks(text: String, out: MutableList<String>) {
        for (match in proxySchemeRegex.findAll(text)) {
            val start = match.range.first
            var end = start
            while (end < text.length && text[end] !in charArrayOf(' ', '\t', '\n', '\r')) {
                end++
            }
            val token = text.substring(start, end).trim()
            if (token.isNotEmpty() && !out.contains(token)) {
                out.add(token)
            }
        }
    }
}
