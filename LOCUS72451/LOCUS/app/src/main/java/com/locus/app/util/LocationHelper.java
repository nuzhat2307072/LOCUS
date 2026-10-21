package com.locus.app.util;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import java.util.function.Consumer;

/** One-shot GPS fix with graceful fallbacks (Location and GPS APIs). */
public final class LocationHelper {
    private LocationHelper() {
    }

    /** Delivers the current location, the last known location, or null. Always on the main thread. */
    @SuppressLint("MissingPermission") // guarded by PermissionHelper.hasLocation
    public static void getLocation(Context ctx, Consumer<Location> callback) {
        final Context app = ctx.getApplicationContext();
        if (!PermissionHelper.hasLocation(app)) {
            callback.accept(null);
            return;
        }
        final FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(app);
        CancellationTokenSource cts = new CancellationTokenSource();
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                .addOnSuccessListener(loc -> {
                    if (loc != null) {
                        AppPrefs.setLastLocation(app, loc.getLatitude(), loc.getLongitude());
                        callback.accept(loc);
                    } else {
                        lastKnown(client, app, callback);
                    }
                })
                .addOnFailureListener(e -> lastKnown(client, app, callback));
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
