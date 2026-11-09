package com.locus.app.data.firebase;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.locus.app.model.ShareRules;
import com.locus.app.service.LocationShareService;
import com.locus.app.util.ShareState;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live location sharing over the Realtime Database. A share lives at shares/{code}; the code is the invitation:
 * a contact who types it adds himself to shares/{code}/viewers and can then read the moving position. The owner
 * removes a viewer (revoke) or the whole share (stop) at any time.
 */
public final class LiveShareRepository {
    private static final String TAG = "LiveShare";
    public static final String NODE = "shares";
    private static final long TIMEOUT_MS = 12000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private LiveShareRepository() {
    }

    public interface Result {
        /** @param error null on success. */
        void done(@Nullable String error);
    }

    private static DatabaseReference ref(String code) {
        return Rtdb.get().getReference(NODE).child(code);
    }

    /** Waits for a Firebase task for at most TIMEOUT_MS: offline writes would otherwise never answer. */
    private static Runnable guard(final AtomicBoolean answered, final Result cb, final String offlineMessage) {
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable r = () -> {
            if (answered.compareAndSet(false, true)) cb.done(offlineMessage);
        };
        h.postDelayed(r, TIMEOUT_MS);
        return () -> h.removeCallbacks(r);
    }

    /**
     * Starts sharing.
     *
     * @param durationMs how long; 0 = until the destination is reached (destLat/destLon must be real then)
     */
    public static void start(final Context ctx, final long durationMs, final double destLat, final double destLon,
                             final String destName, final Result cb) {
        final Context app = ctx.getApplicationContext();
        if (!Session.canSyncOnline(app)) {
            cb.done("cloud");
            return;
        }
        attempt(app, durationMs, destLat, destLon, destName, cb, 0);
    }

    private static void attempt(final Context app, final long durationMs, final double destLat, final double destLon,
                                final String destName, final Result cb, final int tries) {
        final String code = ShareRules.newCode(RANDOM);
        final long now = System.currentTimeMillis();
        final long expiresAt = durationMs > 0 ? now + durationMs : 0;
        Map<String, Object> m = new HashMap<>();
        m.put("ownerUid", Session.uid(app));
        m.put("ownerName", Session.displayName(app));
        m.put("active", true);
        m.put("createdAt", now);
        m.put("expiresAt", expiresAt);
        m.put("updatedAt", now);
        if (durationMs == 0) {
            m.put("destLat", destLat);
            m.put("destLon", destLon);
            m.put("destName", destName == null ? "" : destName);
        }
        final AtomicBoolean answered = new AtomicBoolean(false);
        final Runnable cancelGuard = guard(answered, cb, "timeout");
        try {
            ref(code).setValue(m).addOnCompleteListener(t -> {
                if (!answered.compareAndSet(false, true)) return;
                cancelGuard.run();
                if (t.isSuccessful()) {
                    ShareState.start(app, code, expiresAt, durationMs == 0, destLat, destLon);
                    ContextCompat.startForegroundService(app, new Intent(app, LocationShareService.class));
                    cb.done(null);
                } else if (tries < 4) {
                    attempt(app, durationMs, destLat, destLon, destName, cb, tries + 1); // code taken: try another
                } else {
                    Exception e = t.getException();
                    cb.done(e == null ? "failed" : String.valueOf(e.getMessage()));
                }
            });
        } catch (Exception e) {
            if (answered.compareAndSet(false, true)) {
                cancelGuard.run();
                cb.done(String.valueOf(e.getMessage()));
            }
        }
    }

    /** Stops sharing: the position disappears for everybody at once. */
    public static void stop(Context ctx) {
        final Context app = ctx.getApplicationContext();
        String code = ShareState.code(app);
        ShareState.clear(app);
        app.stopService(new Intent(app, LocationShareService.class));
        if (code.isEmpty() || !Session.canSyncOnline(app)) return;
        try {
            ref(code).removeValue();
        } catch (Exception e) {
            Log.w(TAG, "Stop failed", e);
        }
    }

    /** Publishes my position (called by the service for every fix). */
    public static void publish(Context app, String code, double lat, double lon, float accuracy, int battery) {
        try {
            Map<String, Object> up = new HashMap<>();
            up.put("lat", lat);
            up.put("lon", lon);
            up.put("acc", accuracy);
            up.put("battery", battery);
            up.put("updatedAt", System.currentTimeMillis());
            ref(code).updateChildren(up);
        } catch (Exception e) {
            Log.w(TAG, "Publish skipped", e);
        }
    }

    /** I type the code I was given: I become a viewer of that share. */
    public static void join(final Context ctx, String rawCode, final Result cb) {
        final Context app = ctx.getApplicationContext();
        final String code = ShareRules.normalizeCode(rawCode);
        if (code == null) {
            cb.done("code");
            return;
        }
        if (!Session.canSyncOnline(app)) {
            cb.done("cloud");
            return;
        }
        Map<String, Object> me = new HashMap<>();
        me.put("name", Session.displayName(app));
        me.put("joinedAt", System.currentTimeMillis());
        final AtomicBoolean answered = new AtomicBoolean(false);
        final Runnable cancelGuard = guard(answered, cb, "timeout");
        try {
            ref(code).child("viewers").child(Session.uid(app)).setValue(me).addOnCompleteListener(t -> {
                if (!answered.compareAndSet(false, true)) return;
                cancelGuard.run();
                if (!t.isSuccessful()) {
                    cb.done("invalid"); // no such share, or it was stopped
                    return;
                }
                ref(code).child("ownerName").get().addOnCompleteListener(r -> {
                    String name = r.isSuccessful() && r.getResult().getValue() != null
                            ? String.valueOf(r.getResult().getValue()) : code;
                    ShareState.addJoined(app, code, name);
                    cb.done(null);
                });
            });
        } catch (Exception e) {
            if (answered.compareAndSet(false, true)) {
                cancelGuard.run();
                cb.done(String.valueOf(e.getMessage()));
            }
        }
    }

    /** I stop watching a share (and it forgets me). */
    public static void leave(Context ctx, String code) {
        Context app = ctx.getApplicationContext();
        ShareState.removeJoined(app, code);
        if (!Session.canSyncOnline(app)) return;
        try {
            ref(code).child("viewers").child(Session.uid(app)).removeValue();
        } catch (Exception e) {
            Log.w(TAG, "Leave failed", e);
        }
    }

    /** Owner: take a viewer's access away. */
    public static void revoke(String code, String viewerUid) {
        try {
            ref(code).child("viewers").child(viewerUid).removeValue();
        } catch (Exception e) {
            Log.w(TAG, "Revoke failed", e);
        }
    }

    public interface ViewersListener {
        void onChanged(Map<String, String> viewers); // uid -> name
    }

    /** Owner: the people who can see me, kept up to date. Call {@link #stopListening} with the result. */
    public static Object listenViewers(String code, final ViewersListener l) {
        final Map<String, String> map = new java.util.LinkedHashMap<>();
        final DatabaseReference r = ref(code).child("viewers");
        ChildEventListener c = new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot s, @Nullable String p) {
                map.put(s.getKey(), String.valueOf(s.child("name").getValue()));
                l.onChanged(new java.util.LinkedHashMap<>(map));
            }

            @Override
            public void onChildChanged(@NonNull DataSnapshot s, @Nullable String p) {
                onChildAdded(s, p);
            }

            @Override
            public void onChildRemoved(@NonNull DataSnapshot s) {
                map.remove(s.getKey());
                l.onChanged(new java.util.LinkedHashMap<>(map));
            }

            @Override
            public void onChildMoved(@NonNull DataSnapshot s, @Nullable String p) {
            }

            @Override
            public void onCancelled(@NonNull DatabaseError e) {
                Log.w(TAG, "Viewer list: " + e.getMessage());
            }
        };
        r.addChildEventListener(c);
        return new Object[]{r, c};
    }

    public static void stopListening(Object handle) {
        if (handle instanceof Object[]) {
            Object[] h = (Object[]) handle;
            ((DatabaseReference) h[0]).removeEventListener((ChildEventListener) h[1]);
        }
    }
}
