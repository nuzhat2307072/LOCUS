package com.locus.app.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.locus.app.ui.NavigationActivity;
import com.locus.app.util.AppPrefs;
import com.locus.app.util.NotificationHelper;
import com.locus.app.util.PermissionHelper;

/**
 * Foreground service that keeps receiving GPS updates while the user navigates and re-broadcasts
 * every fix with a custom broadcast action.
 *
 * Service lifecycle: onCreate -> onStartCommand (every startService/startForegroundService call)
 * -> onDestroy (stopService / stopSelf). onBind returns null because this is a started service.
 */
public class LocationTrackingService extends Service {
    private static final String TAG = "LocationTrackingService";

    public static final String ACTION_START = "com.locus.app.action.START_TRACKING";
    public static final String ACTION_STOP = "com.locus.app.action.STOP_TRACKING";
    /** Custom broadcast sent for every GPS fix. */
    public static final String ACTION_LOCATION_UPDATE = "com.locus.app.ACTION_LOCATION_UPDATE";
    public static final String EXTRA_LAT = "lat";
    public static final String EXTRA_LON = "lon";

    private FusedLocationProviderClient client;
    private LocationCallback callback;
    private boolean running;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        client = LocationServices.getFusedLocationProviderClient(this);
    }

    @SuppressLint("MissingPermission") // checked with PermissionHelper below
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand " + (intent == null ? "null" : intent.getAction()));
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!PermissionHelper.hasLocation(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification n = NotificationHelper.trackingNotification(this, NavigationActivity.class);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NotificationHelper.ID_TRACKING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NotificationHelper.ID_TRACKING, n);
        }

        if (!running) {
            running = true;
            callback = new LocationCallback() {
                @Override
                public void onLocationResult(LocationResult result) {
                    Location loc = result.getLastLocation();
                    if (loc == null) return;
                    AppPrefs.setLastLocation(LocationTrackingService.this, loc.getLatitude(), loc.getLongitude());
                    Intent b = new Intent(ACTION_LOCATION_UPDATE)
                            .setPackage(getPackageName())
                            .putExtra(EXTRA_LAT, loc.getLatitude())
                            .putExtra(EXTRA_LON, loc.getLongitude());
                    sendBroadcast(b);
                }
            };
            LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
                    .setMinUpdateDistanceMeters(0f)
                    .build();
            client.requestLocationUpdates(req, callback, Looper.getMainLooper());

            client.getLastLocation().addOnSuccessListener(loc -> {
                if (loc != null) {
                    AppPrefs.setLastLocation(LocationTrackingService.this, loc.getLatitude(), loc.getLongitude());
                    Intent b = new Intent(ACTION_LOCATION_UPDATE)
                            .setPackage(getPackageName())
                            .putExtra(EXTRA_LAT, loc.getLatitude())
                            .putExtra(EXTRA_LON, loc.getLongitude());
                    sendBroadcast(b);
                }
            });
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        if (callback != null) client.removeLocationUpdates(callback);
        running = false;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
