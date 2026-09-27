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
 * (no foreground service, no notification), using
 * `BIND_AUTO_CREATE | BIND_IMPORTANT | BIND_ABOVE_CLIENT` so this process is never
 * the first one the system reclaims in the background. The main process keeps the
 * saved proxy selection untouched when the engine dies and simply (re)starts the
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

        /**
         * Grace period for reusing the previous live port after a node switch. Bounded
         * so an engine start is never delayed by a port that is genuinely gone.
         */
        private const val PORT_RELEASE_ATTEMPTS = 5
        private const val PORT_RELEASE_RETRY_MS = 60L
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
            reply(
                msg.replyTo, EngineProtocol.REPLY_ERR,
                EngineProtocol.KEY_ID to idOf(msg),
                EngineProtocol.KEY_ERROR to (t.message ?: t.javaClass.simpleName)
            )
        }
    }

    /**
     * Every reply echoes the request id: the main process correlates replies with
     * the command that produced them, and a reply without the id is dropped — that
     * is what silently prevented `onStarted` (live port persisted + Telegram proxy
     * re-applied) from ever running.
     */
    private fun idOf(msg: Message): Int = msg.data.getInt(EngineProtocol.KEY_ID, 0)

    private fun handleStart(msg: Message) {
        val id = idOf(msg)
        val link = msg.data.getString(EngineProtocol.KEY_LINK)
        if (link.isNullOrBlank()) {
            reply(
                msg.replyTo, EngineProtocol.REPLY_ERR,
                EngineProtocol.KEY_ID to id,
                EngineProtocol.KEY_ERROR to "Missing proxy link"
            )
            return
        }
        val portHint = msg.data.getInt(EngineProtocol.KEY_PORT_HINT, 0)
        val replyTo = msg.replyTo
        val previousPort: Int
        synchronized(stateLock) {
            if (currentServer != null && currentLink == link) {
                // Already running the very same node — answer with the live port.
                reply(replyTo, EngineProtocol.REPLY_OK, EngineProtocol.KEY_ID to id, EngineProtocol.KEY_PORT to currentPort)
                return
            }
            previousPort = currentPort
            stopLocked()
        }
        // Prefer the port Telegram is already pointed at over the target node's own
        // remembered one.
        //
        // Telegram's endpoint for a node is `127.0.0.1:<port>`. Reusing the live port
        // means switching nodes swaps the *upstream* while the local endpoint stays
        // exactly the same, so the client's connections come back as soon as the new
        // listener is up. Handing out a different port forced a re-apply of the proxy
        // settings first, and until that landed every connection was refused - the
        // "switching nodes drops everything for a moment / reconnects repeatedly"
        // symptom. On a cold start there is no previous port and the node's own
        // persisted one is used, exactly as before.
        val preferred = if (previousPort > 0) previousPort else portHint
        val port = choosePort(link, preferred, previousPort)
        val config = VlessConfig.buildConfig(link, port)
        if (config == null) {
            Log.e(TAG, "refusing invalid node: $link")
            reply(
                replyTo, EngineProtocol.REPLY_ERR,
                EngineProtocol.KEY_ID to id,
                EngineProtocol.KEY_ERROR to "Unsupported or invalid proxy link"
            )
            return
        }
        val server = LibboxEngine.start(applicationContext, config)
        synchronized(stateLock) {
            currentServer = server
            currentLink = link
            currentPort = port
        }
        reply(replyTo, EngineProtocol.REPLY_OK, EngineProtocol.KEY_ID to id, EngineProtocol.KEY_PORT to port)
    }

    private fun handleStop(msg: Message) {
        synchronized(stateLock) {
            stopLocked()
        }
        reply(
            msg.replyTo, EngineProtocol.REPLY_OK,
            EngineProtocol.KEY_ID to idOf(msg),
            EngineProtocol.KEY_PORT to 0
        )
    }

    private fun handleStatus(msg: Message) {
        val state = synchronized(stateLock) {
            (currentServer != null) to currentPort
        }
        reply(
            msg.replyTo, EngineProtocol.REPLY_OK,
            EngineProtocol.KEY_ID to idOf(msg),
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

    /**
     * Local inbound port for [link].
     *
     * [requireReuse] is the port the engine was listening on until a moment ago. It is
     * tried first (with a short grace period, because the kernel may not have released
     * it yet) so the endpoint Telegram is pointed at survives a node switch; only when
     * it cannot be taken does the search fall through to the hashed/remembered base.
     */
    private fun choosePort(link: String, hint: Int, requireReuse: Int): Int {
        if (requireReuse > 0 && waitForLocalPort(requireReuse)) {
            return requireReuse
        }
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

    /** Waits briefly for [port] to become bindable again after the previous engine. */
    private fun waitForLocalPort(port: Int): Boolean {
        for (attempt in 0 until PORT_RELEASE_ATTEMPTS) {
            if (isLocalPortFree(port)) {
                return true
            }
            if (attempt < PORT_RELEASE_ATTEMPTS - 1) {
                try {
                    Thread.sleep(PORT_RELEASE_RETRY_MS)
                } catch (t: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return false
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
