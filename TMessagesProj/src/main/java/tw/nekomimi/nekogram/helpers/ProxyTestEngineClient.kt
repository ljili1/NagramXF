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
import tw.nekomimi.nekogram.services.SingBoxTestEngineService

/**
 * Minimal client for the throwaway test engine (`:singbox_test`).
 *
 * Deliberately dumber than [ProxyEngineClient]: the test engine is not part of
 * the live proxy path, so it has no recovery rounds, no restart delegate and no
 * persisted state. It only has to
 *
 *   bind (BIND_AUTO_CREATE) -> START(link, portHint) -> answer with the live port
 *
 * and to be releasable at the end of a test batch ([release] sends STOP and
 * drops the binding so the process can be reclaimed).
 *
 * Every entry point is safe to call from any thread and never throws; a request
 * that is not answered within [CONNECT_TIMEOUT_MS] is reported as a failure so
 * the caller's queue always makes progress (a hung test never wedges the list).
 */
object ProxyTestEngineClient {

    private const val TAG = "ProxyTestEngine"

    /**
     * A cold engine process has to be spawned and libbox initialised before the
     * first START can be answered — well above the 2s used for the live engine's
     * status probe, but still bounded so the queue cannot stall.
     */
    private const val CONNECT_TIMEOUT_MS = 12_000L

    fun interface StartCallback {
        /**
         * @param started whether the engine is now listening on [port]
         * @param port    local mixed-inbound port of the test engine (0 on failure)
         * @param error   engine error text, or null
         */
        fun onResult(started: Boolean, port: Int, error: String?)
    }

    @Volatile
    private var messenger: Messenger? = null

    /** True while a binding obtained here is still held. */
    @Volatile
    private var bindingHeld = false

    private val pending = HashMap<Int, StartCallback>()
    private val pendingSend = ArrayList<(Messenger) -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private var nextId = 1

    private val replyMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != EngineProtocol.REPLY_OK && msg.what != EngineProtocol.REPLY_ERR) {
                return
            }
            val id = msg.data.getInt(EngineProtocol.KEY_ID, 0)
            val callback = synchronized(pending) { pending.remove(id) } ?: return
            val ok = msg.what == EngineProtocol.REPLY_OK
            val port = msg.data.getInt(EngineProtocol.KEY_PORT, 0)
            val error = msg.data.getString(EngineProtocol.KEY_ERROR)
            dispatch { callback.onResult(ok, port, error) }
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val connected = Messenger(service)
            messenger = connected
            val queued = synchronized(pendingSend) {
                val copy = ArrayList(pendingSend)
                pendingSend.clear()
                copy
            }
            for (deliver in queued) {
                trySend(connected, deliver)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The framework re-creates it on the next bind; queued commands are
            // re-bound then.
            messenger = null
        }

        override fun onBindingDied(name: ComponentName?) {
            messenger = null
            releaseBinding()
        }

        override fun onNullBinding(name: ComponentName?) {
            messenger = null
            releaseBinding()
            failPending("test engine unavailable")
        }
    }

    /** Starts [link] in the test process; [callback] is invoked on the main thread. */
    @JvmStatic
    fun start(context: Context?, link: String?, portHint: Int, callback: StartCallback?) {
        if (callback == null) {
            return
        }
        if (link.isNullOrBlank()) {
            dispatch { callback.onResult(false, 0, "missing link") }
            return
        }
        val id = synchronized(pending) {
            val newId = nextId++
            pending[newId] = callback
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
        try {
            send(context, message)
        } catch (t: Throwable) {
            Log.e(TAG, "test engine dispatch failed", t)
            val failed = synchronized(pending) { pending.remove(id) }
            if (failed != null) {
                dispatch { failed.onResult(false, 0, "test engine unavailable") }
            }
            return
        }
        handler.postDelayed({
            val timedOut = synchronized(pending) { pending.remove(id) }
            if (timedOut != null) {
                dispatch { timedOut.onResult(false, 0, "test engine timeout") }
            }
        }, CONNECT_TIMEOUT_MS)
    }

    /**
     * Stops whatever the test engine runs and drops the binding, so the throwaway
     * process becomes idle and the system can reclaim it. Outstanding requests are
     * failed. Never throws.
     */
    @JvmStatic
    fun release() {
        try {
            val current = messenger
            if (current != null) {
                val message = Message.obtain(null, EngineProtocol.MSG_STOP).apply {
                    data = Bundle().apply { putInt(EngineProtocol.KEY_ID, 0) }
                }
                trySend(current, { target -> target.send(message) })
            }
        } catch (t: Throwable) {
            Log.e(TAG, "test engine stop failed", t)
        }
        failPending("test engine released")
        releaseBinding()
    }

    /** True while a live connection to the test engine process exists. */
    @JvmStatic
    fun isConnected(): Boolean = messenger != null

    private fun send(context: Context?, message: Message) {
        val current = messenger
        if (current != null) {
            trySend(current) { target -> target.send(message) }
            return
        }
        synchronized(pendingSend) { pendingSend.add { target -> target.send(message) } }
        bind(context)
    }

    private fun bind(context: Context?) {
        val ctx = context ?: ApplicationLoader.applicationContext ?: return
        if (bindingHeld || messenger != null) {
            return
        }
        bindingHeld = true
        try {
            val intent = Intent(ctx, SingBoxTestEngineService::class.java)
            ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            bindingHeld = false
            Log.e(TAG, "test engine bind failed", t)
            failPending("test engine unavailable")
        }
    }

    private fun releaseBinding() {
        if (!bindingHeld) {
            return
        }
        val ctx = ApplicationLoader.applicationContext
        if (ctx != null) {
            try {
                ctx.unbindService(connection)
            } catch (t: Throwable) {
                Log.e(TAG, "test engine unbind failed", t)
            }
        }
        bindingHeld = false
        synchronized(pendingSend) { pendingSend.clear() }
    }

    private fun trySend(target: Messenger, deliver: (Messenger) -> Unit) {
        try {
            deliver(target)
        } catch (t: Throwable) {
            Log.e(TAG, "test engine send failed", t)
        }
    }

    private fun failPending(reason: String) {
        val failed = synchronized(pending) {
            val all = ArrayList(pending.values)
            pending.clear()
            all
        }
        for (callback in failed) {
            dispatch { callback.onResult(false, 0, reason) }
        }
    }

    private fun dispatch(body: () -> Unit) {
        val wrapped = Runnable {
            try {
                body()
            } catch (t: Throwable) {
                Log.e(TAG, "test engine callback failed", t)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            wrapped.run()
        } else {
            handler.post(wrapped)
        }
    }
}
