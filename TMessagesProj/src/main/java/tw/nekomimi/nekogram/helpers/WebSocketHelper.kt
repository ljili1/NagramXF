package tw.nekomimi.nekogram.helpers

import androidx.core.util.Pair
import org.tcp2ws.RelayLog
import org.tcp2ws.tcp2wsServer
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import tw.nekomimi.nekogram.NekoConfig
import java.net.ServerSocket

object WebSocketHelper {
    const val proxyServer = "ws.nagramxf"

    private var socksPort = -1
    private var tcp2wsStarted = false
    private var tcp2wsServer: tcp2wsServer? = null

    /**
     * The relay library has no logging dependency of its own; this wires its sink
     * into the app log once.
     *
     * Without it every relay failure (upstream domain unreachable, Worker 5xx,
     * destination with no CDN mapping, TLS failure) went to the process stdout, which
     * Android does not collect into the app log - so "the built-in ws proxy keeps
     * reconnecting" was undiagnosable on a device.
     */
    @Volatile
    private var relayLoggerInstalled = false

    private fun ensureRelayLogger() {
        if (relayLoggerInstalled) {
            return
        }
        relayLoggerInstalled = true
        RelayLog.setLogger(object : RelayLog.Logger {
            override fun d(message: String) {
                org.telegram.messenger.FileLog.d("tcp2ws: $message")
            }

            override fun e(message: String, error: Throwable?) {
                if (error == null) {
                    org.telegram.messenger.FileLog.e("tcp2ws: $message")
                } else {
                    org.telegram.messenger.FileLog.e("tcp2ws: $message", error)
                }
            }
        })
    }

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

    /**
     * Port the local tcp2ws relay listens on, or -1 when it is not running.
     *
     * Unlike [getSocksPort] this never *starts* the relay: it exists for callers
     * that only want to know whether the built-in ws proxy is reachable right now
     * (its status check must not boot a relay the user did not ask for).
     *
     * The answer is a fact rather than an intention because [tcp2wsServer.start]
     * binds the listen socket on the calling thread: `tcp2wsStarted` is only set
     * once the relay really accepts connections on the recorded port.
     */
    @JvmStatic
    fun socksPortIfRunning(): Int {
        return if (tcp2wsStarted && socksPort != -1) socksPort else -1
    }

    @JvmStatic
    fun wsReloadConfig() {
        ensureRelayLogger()
        if (tcp2wsServer != null) {
            try {
                tcp2wsServer!!.setCdnDomain(currentProvider.host)
                    .setTls(wsEnableTLS)
                    .setUserAgent((System.getProperty("http.agent") ?: "") + " " + userAgent)
                    .setConnHash(connHash)
                // Tunnels that are already open keep the host they were dialled with;
                // only new ones pick this up. Logged because "I changed the domain and
                // nothing happened" is otherwise indistinct from "the change was not
                // applied".
                org.telegram.messenger.FileLog.d("tcp2ws: upstream host set to ${currentProvider.host} (tls=$wsEnableTLS)")
            } catch (e: Exception) {
                org.telegram.messenger.FileLog.e(e)
            }
        }
    }

    fun getSocksPort(port: Int): Int {
        return if (tcp2wsStarted && socksPort != -1) {
            socksPort
        } else try {
            ensureRelayLogger()
            val target = if (port != -1) port else findFreePort()
            if (target == -1) {
                -1
            } else {
                // start() binds on this thread and returns only once the relay is
                // listening, so this either throws (port taken / socket refused)
                // or leaves a relay that really works. Recording a start that
                // silently failed is what made the built-in ws proxy permanently
                // unusable: Telegram was pointed at a dead local port while every
                // caller was told the relay was running.
                tcp2wsServer = tcp2wsServer().setCdnDomain(currentProvider.host)
                    .setTls(wsEnableTLS)
                    .setUserAgent((System.getProperty("http.agent") ?: "") + " " + userAgent)
                    .setConnHash(connHash)
                tcp2wsServer!!.start(target)
                socksPort = target
                tcp2wsStarted = true
                // A relay whose listen port nobody knows about is useless: the port is
                // what Telegram is pointed at, and a rebound port after a conflict is
                // otherwise invisible.
                org.telegram.messenger.FileLog.d(
                    "tcp2ws: relay listening on 127.0.0.1:$target (upstream=${currentProvider.host}, tls=$wsEnableTLS)"
                )
                target
            }
        } catch (e: Exception) {
            org.telegram.messenger.FileLog.e("tcp2ws: relay failed to start on port $port", e)
            // Nothing is listening: clear the state so the next caller rebuilds the
            // relay instead of being handed a stale port.
            tcp2wsStarted = false
            tcp2wsServer = null
            socksPort = -1
            if (port != -1) {
                getSocksPort(-1)
            } else {
                -1
            }
        }
    }

    private fun findFreePort(): Int {
        return try {
            val socket = ServerSocket(0)
            val free = socket.localPort
            socket.close()
            free
        } catch (e: Exception) {
            org.telegram.messenger.FileLog.e(e)
            -1
        }
    }
}
