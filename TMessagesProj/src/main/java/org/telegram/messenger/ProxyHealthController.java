package org.telegram.messenger;

import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;

import tw.nekomimi.nekogram.helpers.ProxyEngineClient;
import tw.nekomimi.nekogram.helpers.WebSocketHelper;

/**
 * Health layer for the proxy Telegram is currently using.
 *
 * **There is no timer here, on purpose.** The previous implementation ran a 10 s
 * ping of the active proxy for the whole lifetime of the process, plus a list-page
 * re-test on every list rebuild. Both were removed, for three reasons:
 *
 *  * the probe is not free. It is a real MTProto connection through the proxy:
 *    through the built-in ws relay it dials a *new* WebSocket to the CDN, through a
 *    node it costs an engine round trip. Every extra tunnel competes with the
 *    traffic the user actually wants;
 *  * it was measuring the wrong thing. A probe failing says almost nothing about a
 *    proxy that is carrying traffic fine - and flipping the *active* row to
 *    "unavailable" makes the list re-sort it and the rotation controller treat it
 *    as dead, so a transient hiccup used to look like a dead proxy;
 *  * the signal that matters is already delivered by the client itself. While the
 *    proxy is enabled, Telegram's own connection state *is* the availability
 *    measurement: {@code Connected} proves the proxy works, and a connection stuck
 *    on {@code ConnectingToProxy} proves something is wrong - with the exact timing
 *    of the user-visible problem.
 *
 * So availability is derived like this instead:
 *
 *  1. **Passive, free, and exact** - on {@code Connected} the active proxy is
 *     recorded as usable (and its freshness stamped, which also stops the list page
 *     from re-probing it);
 *  2. **Reactive** - a connection stuck on {@code ConnectingToProxy} is the trigger
 *     to check the things that can actually be broken from the app side (a dead
 *     node engine, a relay that is not listening), throttled so the check cannot
 *     turn into a loop of its own;
 *  3. **On demand** - the list page probes when it is opened, and the user can
 *     re-test at any time from the menu (`RetestPing`).
 *
 * Nodes are additionally covered by an event path: the engine client reports a dead
 * process/binding itself and restarts it from the persisted state, so liveness never
 * depended on a heartbeat.
 */
public class ProxyHealthController implements NotificationCenter.NotificationCenterDelegate {

    private static final ProxyHealthController INSTANCE = new ProxyHealthController();

    /**
     * Minimum spacing between two reactions to {@code ConnectingToProxy}.
     *
     * The state is re-broadcast repeatedly while a proxy is unreachable, and each
     * reaction can start an engine or re-apply proxy settings. Without this bound the
     * watchdog would itself become the busy loop the removed timer used to be.
     */
    private static final long CONNECTING_REACTION_THROTTLE_MS = 15_000L;

    private long lastConnectingReactionAt;

    private void register() {
        // Every account is observed (same pattern as ProxyRotationController) because
        // the active account can change without any proxy notification; the handler
        // then filters on the selected one, which is the account whose connection state
        // describes the proxy the user is looking at. Observing only the account
        // selected at start-up would go silent after an account switch.
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.didUpdateConnectionState);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        try {
            if (id == NotificationCenter.didUpdateConnectionState) {
                onConnectionStateChanged(account);
            }
        } catch (Throwable e) {
            // Delivered from a notification dispatch: an escaping throwable would take
            // the caller (often a UI handler) down with it.
            FileLog.e(e);
        }
    }

    private void onConnectionStateChanged(int account) {
        if (account != UserConfig.selectedAccount) {
            return;
        }
        if (!SharedConfig.isProxyEnabled()) {
            return;
        }
        int state = ConnectionsManager.getInstance(account).getConnectionState();
        if (state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating) {
            onProxyProvenUsable();
        } else if (state == ConnectionsManager.ConnectionStateConnectingToProxy) {
            onProxySuspect();
        }
    }

    /**
     * Telegram is connected through the proxy: that is the measurement.
     *
     * Stamping the freshness here is what keeps the rest of the app from re-probing a
     * proxy that is demonstrably working.
     */
    private void onProxyProvenUsable() {
        SharedConfig.ProxyInfo info = SharedConfig.currentProxy;
        if (info == null) {
            return;
        }
        info.availableCheckTime = SystemClock.elapsedRealtime();
        if (!info.available) {
            info.available = true;
            info.lastError = null;
            // The measured state is read by the list page and the rotation controller,
            // and both only listen for this notification.
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyCheckDone, info);
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyPingUpdated, info.ping);
    }

    /**
     * Telegram cannot get through the proxy right now. Check what the app can fix.
     *
     * This replaces the periodic engine heartbeat: the engine is only asked to prove
     * itself when there is an actual symptom, and the question asked of it ("is the
     * node engine still answering?") is the one the heartbeat used to ask - just on
     * demand instead of every 30 s.
     */
    private void onProxySuspect() {
        SharedConfig.ProxyInfo info = SharedConfig.currentProxy;
        if (info == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastConnectingReactionAt != 0 && now - lastConnectingReactionAt < CONNECTING_REACTION_THROTTLE_MS) {
            return;
        }
        lastConnectingReactionAt = now;

        if (info.isExternal()) {
            // Probing answers false quickly when the process is gone and restarts it
            // through the persisted state; a healthy engine is left alone.
            FileLog.d("proxy health: connection stuck on ConnectingToProxy, checking the node engine");
            ProxyEngineClient.recoverEngine();
            return;
        }

        // Built-in ws row: the relay lives in this process, so "not listening" is a
        // state the app can repair. Re-applying the proxy settings starts it again on
        // the recorded port; when it is already running this is a no-op.
        if (WebSocketHelper.proxyServer.equals(info.address) && WebSocketHelper.socksPortIfRunning() <= 0) {
            FileLog.d("proxy health: built-in ws relay is not listening, restarting it");
            SharedConfig.setProxyEnable(true);
        }
    }

    /** Registers the observers. Called once, from the application start-up path. */
    public static void init() {
        INSTANCE.register();
    }
}
