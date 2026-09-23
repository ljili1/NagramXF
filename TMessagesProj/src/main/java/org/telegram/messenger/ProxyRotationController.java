package org.telegram.messenger;

import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import tw.nekomimi.nekogram.helpers.ProxyConnectivityHelper;
import tw.nekomimi.nekogram.helpers.WebSocketHelper;

/**
 * Auto-selection / failover for every proxy the app can store.
 *
 * When {@link SharedConfig#proxyRotationEnabled} is on and Telegram ends up
 * stuck on "connecting to proxy", the candidates are (re)measured and the
 * fastest reachable one becomes the current proxy. Candidates cover every kind:
 * native Socks5 / MTProto rows are probed in parallel by ConnectionsManager,
 * node (sing-box) rows by {@link ProxyConnectivityHelper} in the throwaway
 * `:singbox_test` engine — so measuring candidates never interrupts the proxy
 * that is currently in use.
 *
 * The built-in Cloudflare ws row is never a candidate: its upstream is dead by
 * design, so it could only ever be a useless failover target.
 */
public class ProxyRotationController implements NotificationCenter.NotificationCenterDelegate {
    private final static ProxyRotationController INSTANCE = new ProxyRotationController();

    public final static int DEFAULT_TIMEOUT_INDEX = 1;
    public final static List<Integer> ROTATION_TIMEOUTS = Arrays.asList(
            5, 10, 15, 30, 60
    );

    /** A measurement older than this is not trusted for a switch decision. */
    private final static long RESULT_MAX_AGE_MS = 3 * 60 * 1000L;

    /** Delay before an "current proxy is dead" report triggers a selection round. */
    private final static long DEAD_PROXY_DELAY_MS = 1500L;

    /** True from the first scheduled check until a switch decision was made. */
    private boolean isCurrentlyChecking;
    /** Outstanding checks (native callbacks + one external batch). */
    private int outstandingChecks;

    private Runnable checkProxyAndSwitchRunnable = () -> {
        if (isCurrentlyChecking) {
            return;
        }
        isCurrentlyChecking = true;
        outstandingChecks = 0;

        final int currentAccount = UserConfig.selectedAccount;
        final long now = SystemClock.elapsedRealtime();
        ArrayList<SharedConfig.SingProxy> externalCandidates = new ArrayList<>();

        // Snapshot first: callbacks post to the UI thread and may rebuild the
        // saved list while this loop is running.
        ArrayList<SharedConfig.ProxyInfo> candidates = new ArrayList<>(SharedConfig.proxyList);
        for (int i = 0; i < candidates.size(); i++) {
            final SharedConfig.ProxyInfo proxyInfo = candidates.get(i);
            if (proxyInfo == SharedConfig.currentProxy || proxyInfo.checking) {
                continue;
            }
            if (isBuiltInWsRow(proxyInfo)) {
                continue;
            }
            if (isResultFresh(proxyInfo, now)) {
                continue;
            }
            if (proxyInfo.isExternal()) {
                // Node proxies are tested one at a time by ProxyConnectivityHelper
                // (in the throwaway engine, so the live node keeps working).
                externalCandidates.add((SharedConfig.SingProxy) proxyInfo);
                continue;
            }
            outstandingChecks++;
            proxyInfo.checking = true;
            ConnectionsManager.getInstance(currentAccount).checkProxy(proxyInfo.address, proxyInfo.port, proxyInfo.username, proxyInfo.password, proxyInfo.secret, time -> AndroidUtilities.runOnUIThread(() -> {
                applyNativeResult(proxyInfo, time);
                onOneCheckFinished();
            }));
        }

        if (!externalCandidates.isEmpty()) {
            // Count the batch BEFORE testNodes(): when every candidate is already
            // filtered as fresh inside the helper, the completion can fire within
            // the very same call.
            outstandingChecks++;
            ProxyConnectivityHelper.testNodes(externalCandidates, false, (Runnable) this::onOneCheckFinished);
        }

        if (outstandingChecks == 0) {
            onAllChecksFinished();
        }
    };

    private void applyNativeResult(SharedConfig.ProxyInfo info, long time) {
        info.availableCheckTime = SystemClock.elapsedRealtime();
        info.checking = false;
        if (time == -1) {
            info.available = false;
            info.ping = 0;
        } else {
            info.ping = time;
            info.available = true;
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyCheckDone, info);
    }

    private static boolean isBuiltInWsRow(SharedConfig.ProxyInfo info) {
        return info != null && WebSocketHelper.proxyServer.equals(info.address);
    }

    /**
     * True when [info] was measured recently enough to be used for a decision.
     * Values written with a different clock base (older builds) give a negative
     * age and are treated as stale.
     */
    private static boolean isResultFresh(SharedConfig.ProxyInfo info, long now) {
        if (info.availableCheckTime <= 0) {
            return false;
        }
        long age = now - info.availableCheckTime;
        return age >= 0 && age < RESULT_MAX_AGE_MS;
    }

    private void onOneCheckFinished() {
        if (!isCurrentlyChecking) {
            return;
        }
        outstandingChecks--;
        if (outstandingChecks <= 0) {
            outstandingChecks = 0;
            onAllChecksFinished();
        }
    }

    public static void init() {
        INSTANCE.initInternal();
    }

    @SuppressWarnings("ComparatorCombinators")
    private void onAllChecksFinished() {
        isCurrentlyChecking = false;

        if (!SharedConfig.proxyRotationEnabled) {
            return;
        }

        final long now = SystemClock.elapsedRealtime();
        List<SharedConfig.ProxyInfo> sortedList = new ArrayList<>(SharedConfig.proxyList);
        Collections.sort(sortedList, (o1, o2) -> Long.compare(o1.ping, o2.ping));
        for (SharedConfig.ProxyInfo info : sortedList) {
            if (info == SharedConfig.currentProxy || info.checking || !info.available) {
                continue;
            }
            if (isBuiltInWsRow(info) || !isResultFresh(info, now)) {
                continue;
            }

            // One switch path for every proxy type: SharedConfig owns engine
            // start/stop for node proxies and the native apply for the rest.
            MessagesController.getGlobalMainSettings().edit()
                    .putBoolean("proxy_enabled", true)
                    .apply();
            SharedConfig.setCurrentProxy(info);
            SharedConfig.setProxyEnable(true);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyChangedByRotation);
            break;
        }
    }

    /**
     * Immediately looks for a working proxy: used when the active proxy was just
     * proven dead, so the user does not have to wait for the rotation timeout.
     */
    private void scheduleAutoSelect(long delayMs) {
        if (!SharedConfig.proxyRotationEnabled || SharedConfig.proxyList.size() <= 1) {
            return;
        }
        if (isCurrentlyChecking) {
            return;
        }
        AndroidUtilities.cancelRunOnUIThread(checkProxyAndSwitchRunnable);
        AndroidUtilities.runOnUIThread(checkProxyAndSwitchRunnable, delayMs);
    }

    private void initInternal() {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.didUpdateConnectionState);
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxyCheckDone);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxySettingsChanged) {
            // Selection changed (user / another rotation round): cancel a pending
            // check, but never interrupt a batch that is already probing nodes.
            if (!isCurrentlyChecking) {
                AndroidUtilities.cancelRunOnUIThread(checkProxyAndSwitchRunnable);
            }
            // The active proxy may already be known to be dead (e.g. it was just
            // re-enabled after being marked unavailable): pick a working one
            // without waiting for the connection to time out first.
            SharedConfig.ProxyInfo current = SharedConfig.currentProxy;
            if (SharedConfig.isProxyEnabled() && current != null && current.availableCheckTime > 0 && !current.available) {
                scheduleAutoSelect(DEAD_PROXY_DELAY_MS);
            }
        } else if (id == NotificationCenter.proxyCheckDone && args.length > 0 && args[0] == SharedConfig.currentProxy) {
            // The active proxy was just measured as unreachable.
            SharedConfig.ProxyInfo current = SharedConfig.currentProxy;
            if (current != null && !current.available && SharedConfig.isProxyEnabled()) {
                scheduleAutoSelect(DEAD_PROXY_DELAY_MS);
            }
        } else if (id == NotificationCenter.didUpdateConnectionState && account == UserConfig.selectedAccount) {
            if (!SharedConfig.isProxyEnabled() && !SharedConfig.proxyRotationEnabled || SharedConfig.proxyList.size() <= 1) {
                AndroidUtilities.cancelRunOnUIThread(checkProxyAndSwitchRunnable);
                return;
            }

            int state = ConnectionsManager.getInstance(account).getConnectionState();

            if (state == ConnectionsManager.ConnectionStateConnectingToProxy) {
                if (!isCurrentlyChecking) {
                    AndroidUtilities.runOnUIThread(checkProxyAndSwitchRunnable, ROTATION_TIMEOUTS.get(SharedConfig.proxyRotationTimeout) * 1000L);
                }
            } else if (!isCurrentlyChecking) {
                AndroidUtilities.cancelRunOnUIThread(checkProxyAndSwitchRunnable);
            }
        }
    }
}
