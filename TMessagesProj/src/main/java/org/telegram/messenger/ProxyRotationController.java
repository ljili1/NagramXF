package org.telegram.messenger;

import android.content.SharedPreferences;
import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import tw.nekomimi.nekogram.helpers.ProxyConnectivityHelper;

public class ProxyRotationController implements NotificationCenter.NotificationCenterDelegate {
    private final static ProxyRotationController INSTANCE = new ProxyRotationController();

    public final static int DEFAULT_TIMEOUT_INDEX = 1;
    public final static List<Integer> ROTATION_TIMEOUTS = Arrays.asList(
            5, 10, 15, 30, 60
    );

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

        int currentAccount = UserConfig.selectedAccount;
        ArrayList<SharedConfig.SingProxy> externalCandidates = new ArrayList<>();

        for (int i = 0; i < SharedConfig.proxyList.size(); i++) {
            SharedConfig.ProxyInfo proxyInfo = SharedConfig.proxyList.get(i);
            if (proxyInfo == SharedConfig.currentProxy || proxyInfo.checking) {
                continue;
            }
            boolean resultFresh = proxyInfo.availableCheckTime > 0
                    && SystemClock.elapsedRealtime() - proxyInfo.availableCheckTime < 2 * 60 * 1000;
            if (proxyInfo.isExternal()) {
                // Node proxies are tested one at a time by ProxyConnectivityHelper
                // (the engine process runs a single node).
                if (!resultFresh) {
                    externalCandidates.add((SharedConfig.SingProxy) proxyInfo);
                }
                continue;
            }
            if (resultFresh) {
                continue;
            }
            outstandingChecks++;
            proxyInfo.checking = true;
            final SharedConfig.ProxyInfo info = proxyInfo;
            ConnectionsManager.getInstance(currentAccount).checkProxy(info.address, info.port, info.username, info.password, info.secret, time -> AndroidUtilities.runOnUIThread(() -> {
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
                onOneCheckFinished();
            }));
        }

        if (!externalCandidates.isEmpty()) {
            // Count the batch BEFORE testNodes(): when every candidate is already
            // filtered as fresh inside the helper, the completion can fire within
            // the very same call.
            outstandingChecks++;
            ProxyConnectivityHelper.testNodes(externalCandidates, false, (Runnable) () ->
                    // Helper invokes completion on the UI thread, after all nodes
                    // of the batch were probed.
                    onOneCheckFinished());
        }

        if (outstandingChecks == 0) {
            onAllChecksFinished();
        }
    };

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

        List<SharedConfig.ProxyInfo> sortedList = new ArrayList<>(SharedConfig.proxyList);
        Collections.sort(sortedList, (o1, o2) -> Long.compare(o1.ping, o2.ping));
        for (SharedConfig.ProxyInfo info : sortedList) {
            if (info == SharedConfig.currentProxy || info.checking || !info.available) {
                continue;
            }

            if (info.isExternal()) {
                // Node proxy: selection + engine start are owned by SharedConfig.
                // setCurrentProxy starts the engine whenever the proxy is enabled
                // (the rotation trigger only runs with proxy_enabled on).
                MessagesController.getGlobalMainSettings().edit()
                        .putBoolean("proxy_enabled", true)
                        .apply();
                SharedConfig.setCurrentProxy(info);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyChangedByRotation);
            } else {
                SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
                editor.putString("proxy_ip", info.address);
                editor.putString("proxy_pass", info.password);
                editor.putString("proxy_user", info.username);
                editor.putInt("proxy_port", info.port);
                editor.putString("proxy_secret", info.secret);
                editor.putBoolean("proxy_enabled", true);

                if (!info.secret.isEmpty()) {
                    editor.putBoolean("proxy_enabled_calls", false);
                }
                editor.apply();

                SharedConfig.currentProxy = info;
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyChangedByRotation);
                ConnectionsManager.setProxySettings(true, SharedConfig.currentProxy.address, SharedConfig.currentProxy.port, SharedConfig.currentProxy.username, SharedConfig.currentProxy.password, SharedConfig.currentProxy.secret);
            }
            break;
        }
    }

    private void initInternal() {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.didUpdateConnectionState);
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxySettingsChanged) {
            // Selection changed (user / another rotation round): cancel a pending
            // check, but never interrupt a batch that is already probing nodes.
            if (!isCurrentlyChecking) {
                AndroidUtilities.cancelRunOnUIThread(checkProxyAndSwitchRunnable);
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
