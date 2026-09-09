package tw.nekomimi.nekogram.helpers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import org.telegram.messenger.ApplicationLoader
import tw.nekomimi.nekogram.services.EngineProtocol
import tw.nekomimi.nekogram.services.SingBoxEngineService

/**
 * Main-process client for the sing-box engine running in the `:singbox`
 * process. Every start/stop command is delivered over Messenger; replies are
 * correlated by id and delivered on the main thread.
 *
 * The saved proxy configuration is intentionally left untouched when the engine
 * process dies: the UI keeps the user's selection and the engine is simply
 * restarted.
 */
object ProxyEngineClient {

    private const val TAG = "ProxyEngineClient"

    interface StartCallback {
        fun onStarted(port: Int)
        fun onError(error: String)
    }

    fun interface StopCallback {
        fun onStopped()
    }

    @Volatile
    private var messenger: Messenger? = null

    private var bound = false
    private var pendingSend = ArrayList<(Messenger) -> Unit>()
    private val pendingCallbacks = HashMap<Int, StartCallback>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var nextId = 1

    @Volatile
    var lastLink: String? = null
        private set

    @Volatile
    var lastPort: Int = 0
        private set

    @Volatile
    private var respawnScheduled = false

    private val replyMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                EngineProtocol.REPLY_OK, EngineProtocol.REPLY_ERR -> {
                    val id = msg.data.getInt(EngineProtocol.KEY_ID, 0)
                    val callback = synchronized(pendingCallbacks) { pendingCallbacks.remove(id) }
                    if (callback != null) {
                        if (msg.what == EngineProtocol.REPLY_OK) {
                            val port = msg.data.getInt(EngineProtocol.KEY_PORT, 0)
                            lastPort = port
                            runCatching { callback.onStarted(port) }.onFailure { Log.e(TAG, "onStarted", it) }
                        } else {
                            val error = msg.data.getString(EngineProtocol.KEY_ERROR) ?: "engine start failed"
                            Log.e(TAG, "engine error: $error")
                            runCatching { callback.onError(error) }.onFailure { Log.e(TAG, "onError", it) }
                        }
                    }
                }
            }
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            messenger = Messenger(service)
            val toSend = synchronized(this@ProxyEngineClient) {
                val pending = pendingSend.toList()
                pendingSend.clear()
                pending
            }
            for (send in toSend) {
                send(messenger)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            messenger = null
            bound = false
            Log.w(TAG, "engine process died; UI keeps proxy state")
            // Fail any in-flight requests so callers can react (nothing is
            // cleared automatically — the saved proxy config is untouched).
            val failed = synchronized(pendingCallbacks) {
                val list = pendingCallbacks.values.toList()
                pendingCallbacks.clear()
                list
            }
            for (cb in failed) {
                mainHandler.post { runCatching { cb.onError("engine process died") }.onFailure { Log.e(TAG, "onError", it) } }
            }
            scheduleRespawn()
        }
    }

    @JvmStatic
    fun isEngineConnected(): Boolean = messenger != null

    @JvmStatic
    fun start(context: Context?, link: String?, portHint: Int, callback: StartCallback?) {
        if (context == null || link.isNullOrBlank()) {
            callback?.onError("missing context or link")
            return
        }
        lastLink = link
        val id: Int = synchronized(pendingCallbacks) {
            val newId = nextId++
            if (callback != null) {
                pendingCallbacks[newId] = callback
            }
            newId
        }
        val message = Message.obtain(null, EngineProtocol.MSG_START).apply {
            replyTo = replyMessenger
            data = Bundle().apply {
                putInt(EngineProtocol.KEY_ID, id)
                putString(EngineProtocol.KEY_LINK, link)
                putInt(EngineProtocol.KEY_PORT_HINT, portHint)
            }
        }
        dispatch(context, message)
    }

    @JvmStatic
    fun stop(context: Context?, callback: StopCallback?) {
        val message = Message.obtain(null, EngineProtocol.MSG_STOP).apply {
            replyTo = replyMessenger
            data = Bundle().apply { putInt(EngineProtocol.KEY_ID, 0) }
        }
        val wrapped = {
            if (callback != null) {
                mainHandler.post { runCatching { callback.onStopped() }.onFailure { Log.e(TAG, "onStopped", it) } }
            }
        }
        dispatch(context, message)
        wrapped()
    }

    /** Asks the engine process whether it is still alive and which port it uses. */
    @JvmStatic
    fun isRunning(context: Context?): Boolean {
        return messenger != null
    }

    private fun dispatch(context: Context, message: Message) {
        val current = messenger
        if (current != null) {
            sendSafe(current, message)
            return
        }
        synchronized(this) {
            pendingSend.add { m -> sendSafe(m, message) }
        }
        bind(context)
    }

    private fun bind(context: Context) {
        if (bound) {
            return
        }
        bound = true
        try {
            val intent = Intent(context, SingBoxEngineService::class.java)
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            bound = false
            Log.e(TAG, "bind failed", t)
            synchronized(pendingSend) { pendingSend.clear() }
        }
    }

    private fun sendSafe(target: Messenger?, message: Message) {
        if (target == null) {
            return
        }
        try {
            target.send(message)
        } catch (t: Throwable) {
            Log.e(TAG, "send failed", t)
        }
    }

    /** After an unexpected engine-process death, restart it once the app is up. */
    private fun scheduleRespawn() {
        if (respawnScheduled) {
            return
        }
        respawnScheduled = true
        mainHandler.postDelayed({
            respawnScheduled = false
            val link = lastLink
            if (!link.isNullOrBlank() && messenger == null) {
                Log.i(TAG, "respawn engine for $link")
                start(ApplicationLoader.applicationContext, link, lastPort, null)
            }
        }, 1500)
    }
}
