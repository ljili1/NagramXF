package org.telegram.messenger;

import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestTimeDelegate;

import tw.nekomimi.nekogram.helpers.ProxyEngineClient;
import tw.nekomimi.nekogram.helpers.WebSocketHelper;

/**
 * Periodically measures the proxy Telegram is currently using so its row keeps
 * showing a live connectivity state.
 *
 * Two things are deliberately handled here (both used to make the proxy page
 * look broken):
 *
 *  * the result time is stamped with {@link SystemClock#elapsedRealtime()}, the
 *    same clock base every other freshness check uses. Stamping it with
 *    {@code System.currentTimeMillis()} made every freshly measured row look
 *    "ancient" and re-checked it immediately, which in turn restarted engines;
 *  * a single failed probe of a node proxy whose engine is not up yet (still
 *    starting, or just restarted in the background) is not treated as a node
 *    failure; the row is only flipped to "unavailable" after two consecutive
 *    failures.
 */
public class ProxyPingController {

    private static final ProxyPingController INSTANCE = new ProxyPingController();

    private static final long PING_INTERVAL_MS = 10_000L;

    /** Consecutive failed probes before the active proxy is called unreachable. */
    private static final int FAILURES_BEFORE_UNAVAILABLE = 2;

    private final Runnable pingRunnable = this::doPing;

    private int consecutiveFailures;

    private void doPing() {
        SharedConfig.ProxyInfo proxyInfo;
        if (SharedConfig.isProxyEnabled() && (proxyInfo = SharedConfig.currentProxy) != null) {
            String address = proxyInfo.address;
            int port = proxyInfo.port;
            if (WebSocketHelper.proxyServer.equals(address)) {
                // The built-in ws row is only reachable through its local tcp2ws
                // relay; pinging the sentinel domain would always fail and mark a
                // working proxy as unreachable.
                int relayPort = WebSocketHelper.socksPortIfRunning();
                if (relayPort <= 0) {
                    scheduleNextPing();
                    return;
                }
                address = "127.0.0.1";
                port = relayPort;
            } else if (proxyInfo.isExternal() && !ProxyEngineClient.isEngineRunning()) {
                // The node engine is not answering yet: a failed probe at this
                // point says nothing about the node itself.
                scheduleNextPing();
                return;
            }
            final String probeAddress = address;
            final int probePort = port;
            try {
                ConnectionsManager.getInstance(UserConfig.selectedAccount).checkProxy(
                        probeAddress, probePort,
                        proxyInfo.username, proxyInfo.password, proxyInfo.secret,
                        new RequestTimeDelegate() {
                            @Override
                            public void run(long time) {
                                AndroidUtilities.runOnUIThread(() -> onPingResult(proxyInfo, time));
                            }
                        }
                );
            } catch (Throwable e) {
                // Runs from a timer: an escaping throwable would kill the process.
                FileLog.e(e);
                scheduleNextPing();
            }
        } else {
            scheduleNextPing();
        }
    }

    private void onPingResult(SharedConfig.ProxyInfo proxyInfo, long time) {
        proxyInfo.availableCheckTime = SystemClock.elapsedRealtime();
        if (time != -1) {
            consecutiveFailures = 0;
            proxyInfo.ping = time;
            proxyInfo.available = true;
        } else if (++consecutiveFailures >= FAILURES_BEFORE_UNAVAILABLE) {
            proxyInfo.available = false;
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyPingUpdated, time);
        scheduleNextPing();
    }

    private void scheduleNextPing() {
        AndroidUtilities.cancelRunOnUIThread(pingRunnable);
        AndroidUtilities.runOnUIThread(pingRunnable, PING_INTERVAL_MS);
    }

    public static void init() {
        INSTANCE.scheduleNextPing();
    }
}
