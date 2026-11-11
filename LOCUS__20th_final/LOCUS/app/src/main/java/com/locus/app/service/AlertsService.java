package com.locus.app.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.locus.app.data.firebase.CommunitySync;
import com.locus.app.data.firebase.Session;
import com.locus.app.ui.MainActivity;
import com.locus.app.util.AppPrefs;
import com.locus.app.util.NotificationHelper;

/**
 * Live alerts with the app closed - the "broadcast service". While it runs, the database listeners for private
 * messages, road hazards and Community posts stay connected, so a notification appears within seconds of the
 * message / report, straight from the Realtime Database and Firestore. No Cloud Function and no push server is
 * needed. It shows one quiet, low-priority notification (Android requires that for any service that keeps
 * running); the person can turn it off in Settings. If the phone's maker kills background apps, the 15-minute
 * background checks (WorkManager) still deliver everything, just later.
 */
public class AlertsService extends Service {
    private static final String TAG = "AlertsService";
    private static volatile boolean running;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Re-checks every few minutes that the listeners belong to the signed-in person (login / logout changes). */
    private final Runnable recheck = new Runnable() {
        @Override
        public void run() {
            if (!AppPrefs.liveAlerts(AlertsService.this) || !Session.canSyncOnline(AlertsService.this)) {
                stopSelf();
                return;
            }
            CommunitySync.setKeepAlive(true);
            main.postDelayed(this, 5 * 60 * 1000L);
        }
    };

    public static boolean isRunning() {
        return running;
    }

    /** Starts the service when the person wants live alerts and is signed in to the cloud; safe to call often. */
    public static void startIfWanted(Context c) {
        if (running || !AppPrefs.liveAlerts(c) || !Session.canSyncOnline(c)) return;
        try {
            ContextCompat.startForegroundService(c, new Intent(c, AlertsService.class));
        } catch (RuntimeException e) {
            // Android refuses to start a foreground service from the background in some situations (for
            // example right after boot on newer versions); the 15-minute checks cover those cases.
            Log.w(TAG, "Could not start the live-alerts service now: " + e.getMessage());
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, AlertsService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!AppPrefs.liveAlerts(this) || !Session.canSyncOnline(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NotificationHelper.ID_ALERTS,
                        NotificationHelper.alertsNotification(this, MainActivity.class),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NotificationHelper.ID_ALERTS,
                        NotificationHelper.alertsNotification(this, MainActivity.class));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Foreground start refused: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        main.removeCallbacks(recheck);
        main.post(recheck);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        main.removeCallbacks(recheck);
        CommunitySync.setKeepAlive(false);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
