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
 * Main-process client for the sing-box engine running in the `:singbox` process.
 * Every start/stop/status command is delivered over Messenger; replies are
 * correlated by id and handled on the main thread.
 *
 * The saved proxy configuration is intentionally left untouched when the engine
 * process dies: the UI keeps the user's selection and the engine is simply
 * restarted.
 *
 * Lifecycle notes (these are the reason this class is not a thin `send()` wrapper):
 *
 *  * The engine process is bound (not a foreground service), so the system is free
 *    to freeze it or kill it while the app is backgrounded. Telegram however keeps
 *    its persisted proxy pointed at the local `127.0.0.1` mixed inbound, so a dead
 *    engine means every connection fails until something brings it back.
 *  * With `BIND_AUTO_CREATE` a killed engine process is re-connected by the
 *    framework on its own — the re-created host process starts with *no* engine
 *    running. The binding therefore looks healthy while the engine is not, which is
 *    why recovery is driven by an explicit status request ([probeRunning]) instead
 *    of by the binding state.
 *  * Binding flags keep the engine process at least as important as the Telegram
 *    process ([Context.BIND_IMPORTANT] / [Context.BIND_ABOVE_CLIENT]) so it is not
 *    the first thing the system reclaims in the background.
 */
object ProxyEngineClient {

    private const val TAG = "ProxyEngineClient"

    /** Delay before the engine is checked/restarted after the binding was lost. */
    private const val RECOVER_DELAY_MS = 1500L

    /** A status request that is not answered in time is treated as "not running". */
    private const val PROBE_TIMEOUT_MS = 2000L

    /** A stop request that is not confirmed in time is treated as stopped. */
    private const val STOP_TIMEOUT_MS = 2500L

    /** Upper bound on consecutive recovery rounds (reset once the engine answers). */
    private const val MAX_RECOVERY_ROUNDS = 5

    interface StartCallback {
        fun onStarted(port: Int)
        fun onError(error: String)
    }

    fun interface StopCallback {
        fun onStopped()
    }

    /** Reply of a status request: whether an engine is running and on which port. */
    fun interface RunningCallback {
        fun onResult(running: Boolean, port: Int)
    }

    /**
     * App-layer hook used when the engine has to be brought back. The
     * implementation re-reads the persisted proxy state and is therefore
     * idempotent (and a no-op when the proxy was disabled in the meantime).
     * Registered by [org.telegram.messenger.SharedConfig].
     */
    fun interface RestartDelegate {
        fun restartIfNeeded()
    }

    private sealed class Pending {
        class Start(val callback: StartCallback?) : Pending()
        class Stop(val callback: StopCallback?) : Pending()
        class Probe(val callback: RunningCallback) : Pending()
    }

    @Volatile
    private var messenger: Messenger? = null

    /** True while a binding obtained through [bind] is still held. */
    @Volatile
    private var bindingHeld = false

    /** True once the held binding is known to be dead (process died / binding died). */
    @Volatile
    private var bindingSuspect = false

    private val pendingSend = ArrayList<(Messenger) -> Unit>()
    private val pending = HashMap<Int, Pending>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var nextId = 1

    @Volatile
    var lastLink: String? = null
        private set

    @Volatile
    var lastPort: Int = 0
        private set

    /** Whether the engine process answered that a node is actually running. */
    @Volatile
    private var engineRunning = false

    @Volatile
    private var recoveryScheduled = false
    private var recoveryRounds = 0

    @Volatile
    private var restartDelegate: RestartDelegate? = null

    private val replyMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                EngineProtocol.REPLY_OK, EngineProtocol.REPLY_ERR -> settle(msg)
            }
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val connected = Messenger(service)
            messenger = connected
            bindingSuspect = false
            recoveryRounds = 0
            val toSend = synchronized(pendingSend) {
                val queued = ArrayList(pendingSend)
                pendingSend.clear()
                queued
            }
            for (send in toSend) {
                send(connected)
            }
            Log.i(TAG, "engine process connected (${toSend.size} queued command(s))")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The engine process is gone. With BIND_AUTO_CREATE the binding stays
            // valid and the framework re-connects on its own, so `bindingHeld` must
            // NOT be cleared here — a fresh bind would be created on top of it. The
            // engine itself is not running anymore though (the re-created host
            // process starts empty), so it has to be restarted explicitly.
            messenger = null
            engineRunning = false
            bindingSuspect = true
            Log.w(TAG, "engine process died; UI keeps proxy state")
            failPending("engine process died")
            scheduleRecovery(RECOVER_DELAY_MS)
        }

        override fun onBindingDied(name: ComponentName?) {
            // The binding itself is dead and must be released before a new one can
            // be created (API 26+).
            Log.w(TAG, "engine binding died; rebinding")
            messenger = null
            engineRunning = false
            bindingSuspect = true
            releaseBinding()
            failPending("engine binding died")
            scheduleRecovery(RECOVER_DELAY_MS)
        }

        override fun onNullBinding(name: ComponentName?) {
            Log.e(TAG, "engine service refused to bind")
            messenger = null
            engineRunning = false
            bindingSuspect = true
            releaseBinding()
            failPending("engine service unavailable")
            scheduleRecovery(RECOVER_DELAY_MS)
        }
    }

    /** True when a live connection to the engine process exists. */
    @JvmStatic
    fun isEngineConnected(): Boolean = messenger != null

    /** True when the engine process confirmed that a node is running. */
    @JvmStatic
    fun isEngineRunning(): Boolean = messenger != null && engineRunning

    /**
     * Registers the app-layer restart hook. Called by SharedConfig whenever a node
     * is (re)started, so recovery always goes through the persisted proxy state.
     */
    @JvmStatic
    fun setRestartDelegate(delegate: RestartDelegate?) {
        restartDelegate = delegate
    }

    @JvmStatic
    fun start(context: Context?, link: String?, portHint: Int, callback: StartCallback?) {
        if (link.isNullOrBlank()) {
            callback?.onError("missing link")
            return
        }
        lastLink = link
        send(context, EngineProtocol.MSG_START, Pending.Start(callback)) { data ->
            data.putString(EngineProtocol.KEY_LINK, link)
            data.putInt(EngineProtocol.KEY_PORT_HINT, portHint)
        }
    }

    @JvmStatic
    fun stop(context: Context?, callback: StopCallback?) {
        // A node is no longer wanted: drop the recovery anchor so a stray recovery
        // round cannot resurrect it (the delegate re-checks the persisted state too).
        lastLink = null
        val id = send(context, EngineProtocol.MSG_STOP, Pending.Stop(callback)) { }
        mainHandler.postDelayed({
            val request = synchronized(pending) { pending.remove(id) }
            if (request is Pending.Stop) {
                runCallback { request.callback?.onStopped() }
            }
        }, STOP_TIMEOUT_MS)
    }

    /**
     * Asks the engine process whether a node is running and on which port. Answers
     * `false` immediately when there is no live connection, and after
     * [PROBE_TIMEOUT_MS] when the engine process does not reply (frozen or stuck) —
     * a false negative is safe, restarting an already running node is idempotent.
     */
    @JvmStatic
    fun probeRunning(context: Context?, callback: RunningCallback?) {
        if (callback == null) {
            return
        }
        if (messenger == null) {
            engineRunning = false
            runCallback { callback.onResult(false, 0) }
            return
        }
        val id = send(context, EngineProtocol.MSG_STATUS, Pending.Probe(callback)) { }
        mainHandler.postDelayed({
            val request = synchronized(pending) { pending.remove(id) }
            if (request is Pending.Probe) {
                engineRunning = false
                runCallback { request.callback.onResult(false, 0) }
            }
        }, PROBE_TIMEOUT_MS)
    }

    /**
     * Verifies that the engine is still up and restarts it through the registered
     * delegate when it is not. Safe to call from any foreground transition.
     */
    @JvmStatic
    fun recoverEngine() {
        if (messenger == null) {
            // No live connection: the restart below rebuilds the binding first and
            // the queued start command is delivered as soon as it is up.
            requestRestart()
            return
        }
        probeRunning(ApplicationLoader.applicationContext) { running, _ ->
            if (!running) {
                requestRestart()
            }
        }
    }

    private fun settle(msg: Message) {
        val id = msg.data.getInt(EngineProtocol.KEY_ID, 0)
        val request = synchronized(pending) { pending.remove(id) } ?: return
        val ok = msg.what == EngineProtocol.REPLY_OK
        when (request) {
            is Pending.Start -> {
                if (ok) {
                    val port = msg.data.getInt(EngineProtocol.KEY_PORT, 0)
                    lastPort = port
                    engineRunning = true
                    recoveryRounds = 0
                    runCallback { request.callback?.onStarted(port) }
                } else {
                    val error = msg.data.getString(EngineProtocol.KEY_ERROR) ?: "engine start failed"
                    Log.e(TAG, "engine error: $error")
                    engineRunning = false
                    runCallback { request.callback?.onError(error) }
                }
            }
            is Pending.Stop -> {
                engineRunning = false
                runCallback { request.callback?.onStopped() }
            }
            is Pending.Probe -> {
                val running = ok && msg.data.getBoolean(EngineProtocol.KEY_RUNNING, false)
                val port = msg.data.getInt(EngineProtocol.KEY_PORT, 0)
                engineRunning = running
                if (running) {
                    recoveryRounds = 0
                }
                runCallback { request.callback.onResult(running, port) }
            }
        }
    }

    private fun send(
        context: Context?,
        what: Int,
        request: Pending,
        fill: (Bundle) -> Unit
    ): Int {
        val id = synchronized(pending) {
            val newId = nextId++
            pending[newId] = request
            newId
        }
        val message = Message.obtain(null, what).apply {
            replyTo = replyMessenger
            data = Bundle().apply {
                putInt(EngineProtocol.KEY_ID, id)
                fill(this)
            }
        }
        dispatch(context, message)
        return id
    }

    private fun dispatch(context: Context?, message: Message) {
        val current = messenger
        if (current != null) {
            sendSafe(current, message)
            return
        }
        synchronized(pendingSend) { pendingSend.add { target -> sendSafe(target, message) } }
        ensureBinding(context)
    }

    private fun ensureBinding(context: Context?) {
        val ctx = context ?: ApplicationLoader.applicationContext ?: return
        if (messenger != null) {
            return
        }
        if (bindingHeld && bindingSuspect) {
            // The held binding is known to be dead: the framework's own
            // auto-reconnect is not guaranteed to arrive, so release it and create
            // a fresh one — otherwise the queued command would never be delivered.
            releaseBinding()
        }
        bind(ctx)
    }

    private fun bind(context: Context) {
        if (bindingHeld || messenger != null) {
            return
        }
        bindingHeld = true
        try {
            val intent = Intent(context, SingBoxEngineService::class.java)
            // BIND_IMPORTANT + BIND_ABOVE_CLIENT keep the engine process at least as
            // important as the Telegram process, so backgrounding the app does not
            // get the engine reclaimed first.
            context.bindService(
                intent, connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT or Context.BIND_ABOVE_CLIENT
            )
        } catch (t: Throwable) {
            bindingHeld = false
            Log.e(TAG, "bind failed", t)
            synchronized(pendingSend) { pendingSend.clear() }
        }
    }

    private fun releaseBinding() {
        val ctx = ApplicationLoader.applicationContext
        if (ctx != null) {
            try {
                ctx.unbindService(connection)
            } catch (t: Throwable) {
                Log.e(TAG, "unbind failed", t)
            }
        }
        bindingHeld = false
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

    private fun failPending(reason: String) {
        val failed = synchronized(pending) {
            val all = ArrayList(pending.values)
            pending.clear()
            all
        }
        for (request in failed) {
            when (request) {
                is Pending.Start -> runCallback { request.callback?.onError(reason) }
                is Pending.Stop -> runCallback { request.callback?.onStopped() }
                is Pending.Probe -> runCallback { request.callback.onResult(false, 0) }
            }
        }
    }

    /** Schedules a verification round; bounded so a permanent failure cannot loop. */
    private fun scheduleRecovery(delayMs: Long) {
        if (recoveryScheduled) {
            return
        }
        if (recoveryRounds >= MAX_RECOVERY_ROUNDS) {
            Log.w(TAG, "engine recovery gave up after $recoveryRounds rounds")
            return
        }
        recoveryRounds++
        recoveryScheduled = true
        mainHandler.postDelayed({
            recoveryScheduled = false
            recoverEngine()
        }, delayMs)
    }

    private fun requestRestart() {
        val delegate = restartDelegate
        if (delegate != null) {
            Log.i(TAG, "restarting engine through the app layer")
            runCallback { delegate.restartIfNeeded() }
            return
        }
        val link = lastLink
        if (link.isNullOrBlank()) {
            return
        }
        Log.i(TAG, "restarting engine for ${link.take(48)}")
        start(ApplicationLoader.applicationContext, link, lastPort, null)
    }

    private fun runCallback(body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            Log.e(TAG, "callback failed", t)
        }
    }
}
