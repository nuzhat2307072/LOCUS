package com.locus.app.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.locus.app.R;
import com.locus.app.data.firebase.LiveShareRepository;
import com.locus.app.model.ShareRules;
import com.locus.app.ui.ShareHubActivity;
import com.locus.app.util.AppPrefs;
import com.locus.app.util.GeoUtils;
import com.locus.app.util.NotificationHelper;
import com.locus.app.util.PermissionHelper;
import com.locus.app.util.ShareState;

/**
 * Foreground service that publishes this phone's position to the people who joined my share. It stops itself when
 * the time is up, when I arrive (until-I-arrive shares) or when the battery is low.
 */
public class LocationShareService extends Service {
    public static final int NOTIFICATION_ID = 1002;

    private FusedLocationProviderClient client;
    private LocationCallback callback;

    @Override
    public void onCreate() {
        super.onCreate();
        client = LocationServices.getFusedLocationProviderClient(this);
    }

    @SuppressLint("MissingPermission") // checked with PermissionHelper
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!ShareState.active(this) || !PermissionHelper.hasLocation(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = new NotificationCompat.Builder(this, NotificationHelper.CH_TRACKING)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.share_notification_title))
                .setContentText(getString(R.string.share_notification_text, ShareState.code(this)))
                .setOngoing(true)
                .setContentIntent(android.app.PendingIntent.getActivity(this, 0,
                        new Intent(this, ShareHubActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT))
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        if (callback == null) {
            callback = new LocationCallback() {
                @Override
                public void onLocationResult(LocationResult result) {
                    Location loc = result.getLastLocation();
                    if (loc != null) onFix(loc);
                }
            };
            LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                    .setMinUpdateDistanceMeters(8f).build();
            client.requestLocationUpdates(req, callback, Looper.getMainLooper());
        }
        return START_STICKY;
    }

    private void onFix(Location loc) {
        String code = ShareState.code(this);
        if (code.isEmpty()) {
            stopSelf();
            return;
        }
        AppPrefs.setLastLocation(this, loc.getLatitude(), loc.getLongitude());
        int battery = batteryPercent();
        boolean untilArrive = ShareState.untilArrive(this);
        double dist = -1;
        if (untilArrive) {
            double[] d = ShareState.dest(this);
            dist = GeoUtils.distance(loc.getLatitude(), loc.getLongitude(), d[0], d[1]);
        }
        ShareRules.StopReason why = ShareRules.stopReason(System.currentTimeMillis(), ShareState.expiresAt(this),
                untilArrive, dist, battery, charging());
        if (why != ShareRules.StopReason.NONE) {
            LiveShareRepository.stop(this); // also stops this service
            NotificationHelper.notify(this, NotificationHelper.CH_HAZARD, 2002,
                    getString(R.string.share_stopped_title),
                    getString(why == ShareRules.StopReason.TIME_UP ? R.string.share_stopped_time
                            : why == ShareRules.StopReason.ARRIVED ? R.string.share_stopped_arrived
                            : R.string.share_stopped_battery), ShareHubActivity.class);
            return;
        }
        LiveShareRepository.publish(this, code, loc.getLatitude(), loc.getLongitude(), loc.getAccuracy(), battery);
    }

    private int batteryPercent() {
        Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return 100;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return level < 0 ? 100 : Math.round(level * 100f / scale);
    }

    private boolean charging() {
        Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return i != null && i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    @Override
    public void onDestroy() {
        if (callback != null) client.removeLocationUpdates(callback);
        callback = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
