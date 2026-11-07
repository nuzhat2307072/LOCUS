package com.locus.app.data.firebase;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.locus.app.data.db.LocusDatabase;
import com.locus.app.model.Hazard;
import com.locus.app.receiver.HazardAlertReceiver;
import com.locus.app.util.AppExecutors;
import com.locus.app.util.AppPrefs;
import com.locus.app.util.GeoUtils;
import com.locus.app.work.SyncWorker;

import java.util.Map;

/**
 * Road hazards: Room locally, Firebase Realtime Database for live sharing between users.
 * New hazards from other users arrive through a ChildEventListener and trigger a custom broadcast.
 */
public final class HazardRepository {
    private static final String TAG = "HazardRepo";
    public static final String NODE = "hazards";

    private HazardRepository() {
    }

    /** Handle returned by {@link #listen}; call stop() in onStop/onDestroy. */
    public static final class Subscription {
        private boolean stopped;

        Subscription() {
        }

        public synchronized void stop() {
            if (stopped) return;
            stopped = true;
            release();
        }
    }

    // One shared listener for the whole app (the board is watched while any screen is open); every screen that
    // asks gets a handle, and the listener is removed when the last handle is released.
    private static DatabaseReference sharedRef;
    private static ChildEventListener sharedListener;
    private static int refs;

    @Nullable
    public static synchronized Subscription listen(Context ctx) {
        if (!Session.canSyncOnline(ctx)) return null;
        final Context app = ctx.getApplicationContext();
        try {
            if (refs == 0) {
                DatabaseReference ref = FirebaseDatabase.getInstance().getReference(NODE);
                ChildEventListener l = new ChildEventListener() {
                    @Override
                    public void onChildAdded(@NonNull DataSnapshot s, @Nullable String prev) {
                        store(app, s, true);
                    }

                    @Override
                    public void onChildChanged(@NonNull DataSnapshot s, @Nullable String prev) {
                        store(app, s, false);
                    }

                    @Override
                    public void onChildRemoved(@NonNull DataSnapshot s) {
                        if (s.getKey() != null) removedOnServer(app, s.getKey());
                    }

                    @Override
                    public void onChildMoved(@NonNull DataSnapshot s, @Nullable String prev) {
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.w(TAG, "Realtime Database listen cancelled: " + error.getMessage());
                    }
                };
                ref.addChildEventListener(l);
                sharedRef = ref;
                sharedListener = l;
            }
            refs++;
            return new Subscription();
        } catch (Exception e) {
            Log.w(TAG, "Realtime Database not available (databaseURL missing?)", e);
            return null;
        }
    }

    private static synchronized void release() {
        if (refs > 0) refs--;
        if (refs == 0 && sharedRef != null && sharedListener != null) {
            sharedRef.removeEventListener(sharedListener);
            sharedRef = null;
            sharedListener = null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void store(Context app, DataSnapshot s, boolean announceNew) {
        Object raw = s.getValue();
        if (s.getKey() == null || !(raw instanceof Map)) return;
        final Hazard h = Hazard.fromMap(s.getKey(), (Map<String, Object>) raw);
        if (h != null) accept(app, h, announceNew);
    }

    /** A hazard that exists on the shared board: keep it, and alert when it is new, close and from somebody else. */
    static void accept(Context app, final Hazard h, boolean announceNew) {
        AppExecutors.io().execute(() -> {
            LocusDatabase db = LocusDatabase.get(app);
            boolean existed = db.hazardDao().exists(h.id) > 0;
            db.hazardDao().insert(h);
            boolean mine = h.reporterUid != null && h.reporterUid.equals(Session.uid(app));
            boolean recent = System.currentTimeMillis() - h.createdAt < 60 * 60 * 1000L;
            if (announceNew && !existed && !mine && recent && isNearMe(app, h)) {
                HazardAlertReceiver.send(app, h, false, distanceFromMe(app, h));
            }
        });
    }

    /** A hazard disappeared from the shared board (cleared by somebody): drop it and tell people nearby. */
    static void removedOnServer(Context app, final String id) {
        AppExecutors.io().execute(() -> {
            LocusDatabase db = LocusDatabase.get(app);
            Hazard h = db.hazardDao().find(id);
            if (h == null) return; // already gone here (for example, removed from this phone)
            db.hazardDao().deleteById(id);
            boolean mine = h.reporterUid != null && h.reporterUid.equals(Session.uid(app));
            boolean recent = System.currentTimeMillis() - h.createdAt < 12L * 60 * 60 * 1000L;
            if (!mine && recent && isNearMe(app, h)) {
                HazardAlertReceiver.send(app, h, true, distanceFromMe(app, h));
            }
        });
    }

    /** Everything on the board right now (used by the background check): new ones alert, vanished ones are cleared. */
    public static void syncAll(Context app, Map<String, Hazard> onServer) {
        LocusDatabase db = LocusDatabase.get(app);
        for (Hazard h : onServer.values()) accept(app, h, true);
        for (Hazard local : db.hazardDao().getSynced()) {
            if (!onServer.containsKey(local.id)) removedOnServer(app, local.id);
        }
    }

    private static boolean isNearMe(Context app, Hazard h) {
        if (!AppPrefs.hasLastLocation(app)) return true; // position unknown: better a needless alert than a missed one
        return distanceFromMe(app, h) < 10000;
    }

    private static double distanceFromMe(Context app, Hazard h) {
        double[] last = AppPrefs.lastLocation(app);
        return GeoUtils.distance(last[0], last[1], h.lat, h.lon);
    }

    /** Create: Room first, then Realtime Database. */
    public static void add(Context ctx, Hazard h, Runnable onSaved) {
        final Context app = ctx.getApplicationContext();
        AppExecutors.io().execute(() -> {
            h.synced = false;
            LocusDatabase.get(app).hazardDao().insert(h);
            if (onSaved != null) AppExecutors.main().post(onSaved);
            push(app, h);
        });
    }

    /** Delete from Room and the Realtime Database. */
    public static void delete(Context ctx, Hazard h) {
        final Context app = ctx.getApplicationContext();
        AppExecutors.io().execute(() -> {
            LocusDatabase.get(app).hazardDao().deleteById(h.id);
            if (!Session.canSyncOnline(app)) return;
            try {
                FirebaseDatabase.getInstance().getReference(NODE).child(h.id).removeValue()
                        .addOnFailureListener(e -> Log.w(TAG, "Remove failed (rules?)", e));
            } catch (Exception e) {
                Log.w(TAG, "Remove skipped", e);
            }
        });
    }

    public static void push(Context app, Hazard h) {
        if (!Session.canSyncOnline(app)) return;
        try {
            FirebaseDatabase.getInstance().getReference(NODE).child(h.id).setValue(h.toMap())
                    .addOnSuccessListener(v -> AppExecutors.io().execute(
                            () -> LocusDatabase.get(app).hazardDao().markSynced(h.id)))
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "Push failed, will retry in background", e);
                        SyncWorker.enqueueNow(app);
                    });
        } catch (Exception e) {
            Log.w(TAG, "Push skipped", e);
        }
    }
}
