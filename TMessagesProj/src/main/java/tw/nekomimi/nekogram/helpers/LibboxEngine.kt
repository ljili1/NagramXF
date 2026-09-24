package tw.nekomimi.nekogram.helpers

import android.content.Context
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import android.util.Log

/**
 * sing-box `libbox` engine handle for one proxy node.
 *
 * Model follows Nekogram X 9.3.3: **each proxy object owns its own engine
 * instance** (start / stop are per-proxy operations, exactly like the
 * per-proxy `loader` inside `SharedConfig.VmessProxy` in the 9.3.3 blueprint).
 * There is no shared "global engine" and no hot-reload indirection: switching
 * the current proxy stops the previous node's engine and starts the selected
 * one.
 *
 * One libbox process-wide prerequisite is [Libbox.setup] which is performed
 * exactly once per process. Every node then gets its own command server:
 *
 *   `Libbox.newCommandServer(handler, platformStub)` + `CommandServer.start()`
 *   + `CommandServer.startOrReloadService(configJson, OverrideOptions())`
 *
 * and is released with `closeService()` + `close()`.
 *
 * CRITICAL constraints (verified against proother/sing-box-lib v1.14.1):
 *   * The native calls block for seconds — every entry point here MUST be
 *     called off the UI thread (the owning proxy object dispatches onto a
 *     background executor).
 *   * Any failure inside `startOrReloadService` can leave the command server
 *     in a state a later native call cannot recover from (SIGABRT bypasses the
 *     Java try/catch), so a failed start is always torn down completely and
 *     surfaced as an exception for the caller to report.
 *   * Keep the AAR pinned to v1.14.1. sing-box grows [PlatformInterface]
 *     across releases (shell / bridge / neighbor-monitor callbacks): a version
 *     bump requires re-implementing [PlatformStub] against the new interface.
 */
object LibboxEngine {

    private val setupLock = Any()
    private var setupDone = false

    /**
     * Starts a fresh engine for [configJson] and returns its handle.
     * The caller owns the handle and MUST release it through [stop] once the
     * node is stopped. Throws [Exception] when the engine cannot start (the
     * failed server is torn down internally before the exception propagates).
     */
    @JvmStatic
    fun start(context: Context, configJson: String): CommandServer {
        ensureSetup(context)
        // Build + start the command server in its own scope so a throw from
        // start()/startOrReloadService() cannot leak a half-initialised handle.
        val server = Libbox.newCommandServer(ServerHandler(), PlatformStub())
        try {
            server.start()
            server.startOrReloadService(configJson, OverrideOptions())
            Log.i("LibboxEngine", "node engine up (libbox ${Libbox.version()})")
            return server
        } catch (e: Throwable) {
            Log.e("LibboxEngine", "engine failure", e)
            release(server)
            throw IllegalStateException("sing-box failed to start: ${e.message}", e)
        }
    }

    /** Releases a node engine handle. Safe to call with null. Never throws. */
    @JvmStatic
    fun stop(server: CommandServer?) {
        if (server == null) return
        release(server)
        Log.i("LibboxEngine", "node engine stopped")
    }

    private fun ensureSetup(context: Context) {
        synchronized(setupLock) {
            if (setupDone) return
            val base = context.filesDir.absolutePath
            val setup = SetupOptions()
            setup.setBasePath(base)
            setup.setWorkingPath(base)
            setup.setTempPath(context.cacheDir.absolutePath)
            setup.setFixAndroidStack(true)
            Libbox.setup(setup)
            setupDone = true
        }
    }

    private fun release(server: CommandServer) {
        try {
            server.closeService()
        } catch (e: Throwable) {
            Log.e("LibboxEngine", "engine failure", e)
        }
        try {
            server.close()
        } catch (e: Throwable) {
            Log.e("LibboxEngine", "engine failure", e)
        }
    }

    /** Callbacks the engine invokes on the host app (neutral — no TUN/VPN). */
    private class ServerHandler : CommandServerHandler {
        override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()
        override fun serviceReload() {}
        override fun serviceStop() {}
        override fun setSystemProxyEnabled(enabled: Boolean) {}
        override fun writeDebugMessage(message: String) {
            Log.d("LibboxEngine", "libbox: $message")
        }
        override fun triggerNativeCrash() {}
        override fun connectSSHAgent(): Int = -1
    }

    /**
     * Minimal [PlatformInterface]. Every node exposes a local mixed
     * (SOCKS/HTTP) inbound — there is no TUN/VPN, no system proxy and no
     * per-app routing — so every TUN / shell / bridge / Wi-Fi-state callback is
     * never exercised and returns a neutral value.
     *
     * NULL-RETURN CONTRACT (this is what crashed the engine process):
     * libbox v1.14.1 wraps this object in `platformInterfaceWrapper` and, for a
     * handful of callbacks, dereferences the returned object **without a nil
     * check** — e.g. experimental/libbox/service.go:226
     *
     *     result, err := w.iif.FindConnectionOwner(...)
     *     if err != nil { return nil, err }
     *     return &adapter.ConnectionOwner{UserId: result.UserId, ...}, nil
     *
     * A Java `null` reaches the Go runtime as a null pointer, so `result.UserId`
     * is a nil dereference → `panic: invalid memory address or nil pointer
     * dereference` → SIGSEGV → the Go runtime calls abort() → SIGABRT. Because
     * sing-box asks for the owner of *every* loopback connection
     * (`Router.matchRule` → `prepareMatchMetadata` → `searchProcessInfo`,
     * route/route.go + route/process_cache.go), the engine process died on the
     * first probe/post message with a native crash, which Android surfaced as
     * "the app has stopped" and left every node unusable.
     *
     * Therefore: never return null from a callback whose result libbox reads.
     * [findConnectionOwner] and [getInterfaces] both return fully populated
     * (empty) objects instead; they mirror SagerNet/sing-box-for-android's
     * PlatformInterfaceWrapper, which always returns a value.
     *
     * The remaining nullable callbacks ([localDNSTransport], [readWIFIState],
     * [lookupUser], [openShellSession], [createBridge]) are checked at wrapper
     * level by libbox (`if x == nil { return ... }`) or are unreachable because
     * the corresponding `use*` flag returns false — they are left as-is.
     */
    private class PlatformStub : PlatformInterface {
        // false: resolve the connection owner ourselves (below) instead of
        // letting libbox scan /proc/net/tcp. Both paths avoid the crash — this
        // one answers immediately, so no syscall is added to the connection path
        // (route/process_cache.go caches per source/destination pair). Change it
        // only together with findConnectionOwner().
        override fun useProcFS(): Boolean = false
        override fun usePlatformAutoDetectInterfaceControl(): Boolean = false
        override fun autoDetectInterfaceControl(fd: Int) {}
        override fun openTun(options: TunOptions?): Int = -1
        override fun localDNSTransport(): LocalDNSTransport? = null
        override fun underNetworkExtension(): Boolean = false
        override fun includeAllNetworks(): Boolean = false
        override fun clearDNSCache() {}
        override fun readWIFIState(): WIFIState? = null
        override fun sendNotification(notification: Notification?) {}
        override fun cancelNotification(identifier: String, typeID: Int) {}
        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {}
        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {}

        // libbox iterates this unconditionally (`iteratorToArray(...)` calls
        // HasNext() on it), so a null would panic the same way
        // findConnectionOwner did. This node has no TUN and no `bind_interface`,
        // so an empty list is correct and nothing depends on it.
        override fun getInterfaces(): NetworkInterfaceIterator = EmptyInterfaceIterator()

        /**
         * Answers sing-box's per-connection "which local app owns this socket?"
         * lookup. MUST NOT return null (see the class comment): libbox copies
         * `UserId` / `UserName` / `ProcessPath` / `AndroidPackageNames` out of
         * the returned object.
         *
         * This build does no process-based routing (no route rules with
         * `process_name`), and on Android 10+ the uid is not readable without
         * additional permissions, so "unknown" (`-1`, exactly what libbox treats
         * as no-attribution in route/process_cache.go) is the honest answer.
         * Every field is filled in so no null travels into Go.
         */
        override fun findConnectionOwner(
            ipProtocol: Int,
            sourceAddress: String?,
            sourcePort: Int,
            destinationAddress: String?,
            destinationPort: Int
        ): ConnectionOwner = ConnectionOwner().apply {
            userId = -1
            userName = ""
            processPath = ""
            setAndroidPackageNames(EmptyStringIterator())
        }

        override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}
        override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}
        override fun registerMyInterface(name: String) {}
        override fun usePlatformShell(): Boolean = false
        override fun checkPlatformShell() {}
        override fun openShellSession(
            user: PlatformUser?,
            command: String,
            environ: StringIterator?,
            term: String,
            rows: Int,
            cols: Int
        ): ShellSession? = null
        override fun lookupUser(username: String): PlatformUser? = null
        override fun lookupSFTPServer(): String = ""
        override fun readSystemSSHHostKey(): String = ""
        override fun tailscaleHostname(): String = ""
        override fun usePlatformBridge(): Boolean = false
        override fun createBridge(options: BridgeOptions?): BridgeSession? = null
    }

    /** Empty [StringIterator]; libbox reads the slice field of the owner above. */
    private class EmptyStringIterator : StringIterator {
        override fun hasNext(): Boolean = false
        override fun len(): Int = 0
        override fun next(): String = ""
    }

    /** Empty [NetworkInterfaceIterator] — see [PlatformStub.getInterfaces]. */
    private class EmptyInterfaceIterator : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = false
        override fun next(): NetworkInterface = NetworkInterface()
    }
}
