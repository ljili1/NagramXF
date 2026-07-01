package tw.nekomimi.nekogram.helpers

import androidx.core.util.Pair
import org.tcp2ws.tcp2wsServer
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import tw.nekomimi.nekogram.NekoConfig
import java.net.ServerSocket

object WebSocketHelper {
    const val proxyServer = "ck2ut7v3g5zudnjw.top"

    private var socksPort = -1
    private var tcp2wsStarted = false
    private var tcp2wsServer: tcp2wsServer? = null

    private val userAgent = "${BuildConfig.APP_NAME} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    private val connHash = "381d52f35f552e10ad1701445dba9cd14acb7e43"

    enum class WsProvider(val num: Int, var host: String) {
        BuiltIn(0, proxyServer),
        Custom(2, NekoConfig.wsServerHost.String());
    }

    @JvmStatic
    var currentProvider = when (NekoConfig.wsBuiltInProxyBackend.Int()) {
        WsProvider.BuiltIn.num -> WsProvider.BuiltIn
        WsProvider.Custom.num -> WsProvider.Custom
        else -> WsProvider.BuiltIn
    }
        set(value) {
            if (value == WsProvider.Custom) {
                value.host = NekoConfig.wsServerHost.String()
            }
            NekoConfig.wsBuiltInProxyBackend.setConfigInt(value.num)
            field = value
        }

    @JvmStatic
    fun getProviders(): Pair<ArrayList<String>, ArrayList<WsProvider>> {
        val names = ArrayList<String>()
        val types = ArrayList<WsProvider>()
        names.add(BuildConfig.APP_NAME)
        types.add(WsProvider.BuiltIn)
        names.add(LocaleController.getString("AutoDownloadCustom", R.string.AutoDownloadCustom))
        types.add(WsProvider.Custom)
        return Pair(names, types)
    }

    @JvmStatic
    @get:JvmName("wsEnableTLS")
    var wsEnableTLS: Boolean
        get() = NekoConfig.wsEnableTLS.Bool()
        set(value) {
            NekoConfig.wsEnableTLS.setConfigBool(value)
        }

    @JvmStatic
    fun toggleWsEnableTLS() {
        wsEnableTLS = !wsEnableTLS
    }

    @JvmStatic
    fun getSocksPort(): Int {
        return getSocksPort(6356)
    }

    @JvmStatic
    fun wsReloadConfig() {
        if (tcp2wsServer != null) {
            try {
                tcp2wsServer!!.setCdnDomain(currentProvider.host)
                    .setTls(wsEnableTLS)
                    .setUserAgent((System.getProperty("http.agent") ?: "") + " " + userAgent)
                    .setConnHash(connHash)
            } catch (e: Exception) {
                org.telegram.messenger.FileLog.e(e)
            }
        }
    }

    fun getSocksPort(port: Int): Int {
        return if (tcp2wsStarted && socksPort != -1) {
            socksPort
        } else try {
            if (port != -1) {
                socksPort = port
            } else {
                val socket = ServerSocket(0)
                socksPort = socket.localPort
                socket.close()
            }
            if (!tcp2wsStarted) {
                tcp2wsServer = tcp2wsServer().setCdnDomain(currentProvider.host)
                    .setTls(wsEnableTLS)
                    .setUserAgent((System.getProperty("http.agent") ?: "") + " " + userAgent)
                    .setConnHash(connHash)
                tcp2wsServer!!.start(socksPort)
                tcp2wsStarted = true
            }
            socksPort
        } catch (e: Exception) {
            org.telegram.messenger.FileLog.e(e)
            if (port != -1) {
                getSocksPort(-1)
            } else {
                -1
            }
        }
    }
}
