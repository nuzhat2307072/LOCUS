package com.locus.app.util;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One-shot GPS fix with graceful fallbacks and a timeout (Location and GPS APIs). */
public final class LocationHelper {
    private static final long TIMEOUT_MS = 6000;

    private LocationHelper() {
    }

    /**
     * Delivers the current location, else the last known location, else null - never later than about
     * six seconds, so a screen never hangs waiting for a GPS fix indoors. Always on the main thread.
     */
    @SuppressLint("MissingPermission") // guarded by PermissionHelper.hasLocation
    public static void getLocation(Context ctx, Consumer<Location> callback) {
        final Context app = ctx.getApplicationContext();
        if (!PermissionHelper.hasLocation(app)) {
            callback.accept(null);
            return;
        }
        final FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(app);
        final CancellationTokenSource cts = new CancellationTokenSource();
        final AtomicBoolean done = new AtomicBoolean(false);
        final Handler main = new Handler(Looper.getMainLooper());

        final Runnable timeout = () -> {
            if (done.compareAndSet(false, true)) {
                cts.cancel();
                lastKnown(client, app, callback);
            }
        };
        main.postDelayed(timeout, TIMEOUT_MS);

        client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.getToken())
                .addOnSuccessListener(loc -> {
                    if (!done.compareAndSet(false, true)) return;
                    main.removeCallbacks(timeout);
                    if (loc != null) {
                        AppPrefs.setLastLocation(app, loc.getLatitude(), loc.getLongitude());
                        callback.accept(loc);
                    } else {
                        lastKnown(client, app, callback);
                    }
                })
                .addOnFailureListener(e -> {
                    if (!done.compareAndSet(false, true)) return;
                    main.removeCallbacks(timeout);
                    lastKnown(client, app, callback);
                });
    }

    @SuppressLint("MissingPermission")
    private static void lastKnown(FusedLocationProviderClient client, Context app, Consumer<Location> callback) {
        client.getLastLocation()
                .addOnSuccessListener(loc -> {
                    if (loc != null) AppPrefs.setLastLocation(app, loc.getLatitude(), loc.getLongitude());
                    callback.accept(loc);
                })
                .addOnFailureListener(e -> callback.accept(null));
    }
}
