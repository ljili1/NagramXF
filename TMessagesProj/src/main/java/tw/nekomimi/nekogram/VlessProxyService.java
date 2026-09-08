package tw.nekomimi.nekogram;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import tw.nekomimi.nekogram.helpers.LibboxEngine;
import tw.nekomimi.nekogram.helpers.VlessConfig;
import tw.nekomimi.nekogram.helpers.VlessProxyManager;

/**
 * Foreground service hosting the sing-box engine for the built-in VLESS proxy.
 *
 * Runs a local mixed SOCKS/HTTP inbound on 127.0.0.1:[VlessProxyManager.LOCAL_PORT]
 * and forwards to the configured VLESS outbound. Telegram's proxy setting is
 * pointed at that local port by ConnectionsManager.
 */
public class VlessProxyService extends Service {

    private static final int NOTIFICATION_ID = 1999;
    private static final String CHANNEL_ID = "vless_proxy";

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        // If the engine is already running (e.g. user switched node while the
        // service was alive), hot-reload instead of tearing it down.
        if (LibboxEngine.INSTANCE.isRunning()) {
            String link = VlessProxyManager.getVlessLink();
            String config = VlessConfig.buildConfig(link, VlessProxyManager.LOCAL_PORT);
            if (config != null) {
                LibboxEngine.INSTANCE.reload(config);
            }
        } else {
            startSingBox();
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "VLESS Proxy", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(LocaleController.getString(R.string.VlessProxy))
                .setContentText(LocaleController.getString(R.string.VlessProxyRunning))
                .setSmallIcon(R.drawable.call)
                .setOngoing(true)
                .build();
    }

    private void startSingBox() {
        try {
            String link = VlessProxyManager.getVlessLink();
            String config = VlessConfig.buildConfig(link, VlessProxyManager.LOCAL_PORT);
            if (config == null) {
                FileLog.e("VlessProxyService: invalid or empty vless config");
                stopSelf();
                return;
            }
            if (!LibboxEngine.INSTANCE.start(this, config)) {
                // Engine failed to start — do not leave Telegram pointed at a dead
                // local port; disable VLESS so the app falls back to direct.
                FileLog.e("VlessProxyService: sing-box failed to start, disabling VLESS");
                VlessProxyManager.setEnabled(false);
            }
        } catch (Throwable e) {
            FileLog.e(e);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        try {
            LibboxEngine.INSTANCE.stop();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
