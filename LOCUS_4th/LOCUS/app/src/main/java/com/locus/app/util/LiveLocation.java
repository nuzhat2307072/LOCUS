package com.locus.app.util;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.Priority;

/**
 * A continuous, self-healing position feed - so the "you are here" dot never goes missing.
 *
 *  - Asks the phone to switch location on if it is off (system dialog, one tap).
 *  - Uses high-accuracy updates every second from Google's fused provider.
 *  - A watchdog notices when updates stall (tunnel, sleep, provider hiccup), re-requests them, and
 *    after a while also listens to the phone's own GPS / network providers as a second source.
 *  - Poor fixes never replace a good recent one, and every fix is remembered for the next start.
 *
 * All callbacks arrive on the main thread.
 */
public final class LiveLocation {

    public enum State {
        /** Location permission has not been granted. */
        NO_PERMISSION,
        /** Location is switched off on the phone. */
        GPS_OFF,
        /** Waiting for a first fix, or the signal was lost. */
        SEARCHING,
        /** Receiving good fixes. */
        OK
    }

    public interface Listener {
        void onLocation(Location location);

        void onState(State state);

        /** The phone's location setting must be changed - launch e.getResolution() to show the system dialog. */
        void onSettingsResolution(ResolvableApiException e);
    }

    private static final long WATCHDOG_MS = 4000;
    private static final long STALE_MS = 10_000;
    private static final long REQUEST_AGAIN_MS = 20_000;
    private static final long FALLBACK_AFTER_MS = 25_000;
    private static final float POOR_ACCURACY_M = 150f;

    private final Context app;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FusedLocationProviderClient fused;
    private final LocationManager manager;

    private boolean running;
    private boolean updatesRequested;
    private boolean fallbackOn;
    private long startedAt;
    private long lastFixAt;       // elapsedRealtime of the last accepted fix, 0 = none yet
    private long lastRequestAt;
    private Location last;
    private State state;

    private final LocationCallback fusedCallback = new LocationCallback() {
        @Override
        public void onLocationResult(@NonNull LocationResult result) {
            for (Location l : result.getLocations()) accept(l);
        }
    };

    private final LocationListener platformListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            accept(location);
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
        }

        @SuppressWarnings({"deprecation", "RedundantSuppression"})
        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }
    };

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            tick();
            handler.postDelayed(this, WATCHDOG_MS);
        }
    };

    public LiveLocation(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
        this.fused = LocationServices.getFusedLocationProviderClient(app);
        this.manager = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
    }

    public Location last() {
        return last;
    }

    public State state() {
        return state;
    }

    /** Starts (or restarts) the feed. Safe to call again, e.g. after the permission or setting changed. */
    @SuppressLint("MissingPermission") // guarded by PermissionHelper.hasLocation
    public void start() {
        if (!PermissionHelper.hasLocation(app)) {
            setState(State.NO_PERMISSION);
            return;
        }
        if (!running) {
            running = true;
            startedAt = SystemClock.elapsedRealtime();
            handler.postDelayed(watchdog, WATCHDOG_MS);
        }
        setState(providersEnabled() ? State.SEARCHING : State.GPS_OFF);

        // instant first picture: the last position the phone knows
        fused.getLastLocation().addOnSuccessListener(loc -> {
            if (!running || loc == null || last != null) return;
            long ageMs = (SystemClock.elapsedRealtimeNanos() - loc.getElapsedRealtimeNanos()) / 1_000_000L;
            if (ageMs < 20_000) {
                accept(loc);
            } else {
                last = loc; // old position: show it, but keep "searching" until a live fix arrives
                listener.onLocation(loc);
            }
        });

        // make sure location is switched on, then start the updates
        LocationRequest req = request();
        LocationSettingsRequest settings = new LocationSettingsRequest.Builder()
                .addLocationRequest(req).setAlwaysShow(true).build();
        LocationServices.getSettingsClient(app).checkLocationSettings(settings)
                .addOnSuccessListener(r -> {
                    if (running) requestUpdates();
                })
                .addOnFailureListener(e -> {
                    if (!running) return;
                    if (e instanceof ResolvableApiException) {
                        setState(State.GPS_OFF);
                        listener.onSettingsResolution((ResolvableApiException) e);
                    } else {
                        // no Google location service on this phone: use the platform providers directly
                        requestUpdates();
                        startFallback();
                    }
                });
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(watchdog);
        fused.removeLocationUpdates(fusedCallback);
        manager.removeUpdates(platformListener);
        updatesRequested = false;
        fallbackOn = false;
    }

    /** Forget the last fix, e.g. when the screen restarts and a fresh start is wanted. */
    public void reset() {
        last = null;
        lastFixAt = 0;
    }

    // ---- internals -----------------------------------------------------------------------------

    private static LocationRequest request() {
        return new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(500)
                .setMaxUpdateDelayMillis(0)
                .setWaitForAccurateLocation(false)
                .build();
    }

    @SuppressLint("MissingPermission")
    private void requestUpdates() {
        if (!running || !PermissionHelper.hasLocation(app)) return;
        fused.removeLocationUpdates(fusedCallback);
        fused.requestLocationUpdates(request(), fusedCallback, Looper.getMainLooper())
                .addOnFailureListener(e -> {
                    if (running) startFallback();
                });
        updatesRequested = true;
        lastRequestAt = SystemClock.elapsedRealtime();
    }

    @SuppressLint("MissingPermission")
    private void startFallback() {
        if (fallbackOn || !running || !PermissionHelper.hasLocation(app)) return;
        fallbackOn = true;
        try {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, platformListener,
                        Looper.getMainLooper());
            }
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000, 0f, platformListener,
                        Looper.getMainLooper());
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            // permission revoked while running, or the provider vanished: the watchdog reports the state
        }
    }

    private boolean providersEnabled() {
        try {
            return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return true;
        }
    }

    private void accept(Location loc) {
        if (!running || loc == null) return;
        long now = SystemClock.elapsedRealtime();
        boolean poor = loc.hasAccuracy() && loc.getAccuracy() > POOR_ACCURACY_M;
        boolean haveRecentGood = last != null && lastFixAt != 0 && now - lastFixAt < STALE_MS
                && (!last.hasAccuracy() || last.getAccuracy() <= POOR_ACCURACY_M);
        if (poor && haveRecentGood) return; // a coarse network fix must not throw the dot across town
        last = loc;
        lastFixAt = now;
        if (!poor) AppPrefs.setLastLocation(app, loc.getLatitude(), loc.getLongitude());
        setState(State.OK);
        listener.onLocation(loc);
    }

    private void tick() {
        long now = SystemClock.elapsedRealtime();
        if (!PermissionHelper.hasLocation(app)) {
            setState(State.NO_PERMISSION);
            return;
        }
        if (!providersEnabled()) {
            setState(State.GPS_OFF);
            return;
        }
        boolean stale = lastFixAt == 0 ? now - startedAt > STALE_MS : now - lastFixAt > STALE_MS;
        if (stale) {
            setState(State.SEARCHING);
            if (updatesRequested && now - lastRequestAt > REQUEST_AGAIN_MS) requestUpdates(); // kick the provider
            if (now - startedAt > FALLBACK_AFTER_MS) startFallback();
        } else if (state != State.OK) {
            setState(State.OK);
        }
    }

    private void setState(State s) {
        if (s == state) return;
        state = s;
        listener.onState(s);
    }
}
