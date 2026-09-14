package tw.nekomimi.nekogram.services

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log
import io.nekohasekai.libbox.CommandServer
import tw.nekomimi.nekogram.helpers.LibboxEngine
import tw.nekomimi.nekogram.helpers.VlessConfig
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Hosts the sing-box engine in its own process (`:singbox`).
 *
 * Why: libbox is native code running in-process; a SIGSEGV/SIGABRT inside the
 * engine would take the whole Telegram app down (and the persisted proxy state
 * would then re-trigger it on every cold start). Running the engine in a
 * dedicated process gives the same isolation Nekogram X 9.3.3 gets by spawning
 * its v2ray/ss/ssr cores as child processes: if the engine crashes, only this
 * process dies and the UI keeps running.
 *
 * The service is started by the main process with a plain bind
 * (no foreground service, no notification). The main process keeps the saved
 * proxy selection untouched when the engine dies and simply (re)starts the
 * engine afterwards — the local proxy configuration is never cleared
 * automatically.
 */
class SingBoxEngineService : Service() {

    companion object {
        private const val TAG = "SingBoxEngine"
        private val stateLock = Any()
        private var currentServer: CommandServer? = null
        private var currentLink: String? = null
        private var currentPort = 0
    }

    private var handlerThread: HandlerThread? = null
    private var messenger: Messenger? = null

    override fun onCreate() {
        super.onCreate()
        val thread = HandlerThread("singbox-engine")
        thread.start()
        handlerThread = thread
        messenger = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(msg: Message) {
                handleCommand(msg)
            }
        })
    }

    private fun handleCommand(msg: Message) {
        try {
            when (msg.what) {
                EngineProtocol.MSG_START -> handleStart(msg)
                EngineProtocol.MSG_STOP -> handleStop(msg)
                EngineProtocol.MSG_STATUS -> handleStatus(msg)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "command failed", t)
            reply(msg.replyTo, EngineProtocol.REPLY_ERR, EngineProtocol.KEY_ERROR to (t.message ?: t.javaClass.simpleName))
        }
    }

    private fun handleStart(msg: Message) {
        val link = msg.data.getString(EngineProtocol.KEY_LINK) ?: return
        val portHint = msg.data.getInt(EngineProtocol.KEY_PORT_HINT, 0)
        val replyTo = msg.replyTo
        synchronized(stateLock) {
            if (currentServer != null && currentLink == link) {
                // Already running the very same node — answer with the live port.
                reply(replyTo, EngineProtocol.REPLY_OK, EngineProtocol.KEY_PORT to currentPort)
                return
            }
            stopLocked()
        }
        val port = choosePort(link, portHint)
        val config = VlessConfig.buildConfig(link, port)
        if (config == null) {
            Log.e(TAG, "refusing invalid node: $link")
            reply(replyTo, EngineProtocol.REPLY_ERR, EngineProtocol.KEY_ERROR to "Unsupported or invalid proxy link")
            return
        }
        val server = LibboxEngine.start(applicationContext, config)
        synchronized(stateLock) {
            currentServer = server
            currentLink = link
            currentPort = port
        }
        reply(replyTo, EngineProtocol.REPLY_OK, EngineProtocol.KEY_PORT to port)
    }

    private fun handleStop(msg: Message) {
        synchronized(stateLock) {
            stopLocked()
        }
        reply(msg.replyTo, EngineProtocol.REPLY_OK, EngineProtocol.KEY_PORT to 0)
    }

    private fun handleStatus(msg: Message) {
        val state = synchronized(stateLock) {
            (currentServer != null) to currentPort
        }
        reply(
            msg.replyTo, EngineProtocol.REPLY_OK,
            EngineProtocol.KEY_RUNNING to state.first,
            EngineProtocol.KEY_PORT to state.second
        )
    }

    private fun stopLocked() {
        val server = currentServer
        currentServer = null
        currentLink = null
        currentPort = 0
        LibboxEngine.stop(server)
    }

    private fun choosePort(link: String, hint: Int): Int {
        var base = if (hint > 0) hint else 31000 + Math.abs(link.hashCode() % 18000)
        if (base > 65535) {
            base = 1024 + (base - 65535)
        }
        for (i in 0..400) {
            val candidate = base + i
            val p = if (candidate > 65535) 1024 + (candidate - 65535) else candidate
            if (isLocalPortFree(p)) {
                return p
            }
        }
        return base
    }

    private fun isLocalPortFree(candidate: Int): Boolean {
        try {
            ServerSocket().use { socket ->
                socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), candidate))
                return true
            }
        } catch (t: Throwable) {
            return false
        }
    }

    private fun reply(replyTo: Messenger?, code: Int, vararg kv: Pair<String, Any>) {
        if (replyTo == null) {
            return
        }
        try {
            val data = Bundle()
            for ((key, value) in kv) {
                when (value) {
                    is Int -> data.putInt(key, value)
                    is Boolean -> data.putBoolean(key, value)
                    is String -> data.putString(key, value)
                }
            }
            replyTo.send(Message.obtain(null, code).apply { this.data = data })
        } catch (t: Throwable) {
            Log.e(TAG, "reply failed", t)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = messenger?.binder

    override fun onDestroy() {
        synchronized(stateLock) {
            stopLocked()
        }
        handlerThread?.quitSafely()
        handlerThread = null
        super.onDestroy()
    }
}
