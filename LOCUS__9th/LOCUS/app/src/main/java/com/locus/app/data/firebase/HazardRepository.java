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
        private final DatabaseReference ref;
        private final ChildEventListener listener;

        Subscription(DatabaseReference ref, ChildEventListener listener) {
            this.ref = ref;
            this.listener = listener;
        }

        public void stop() {
            ref.removeEventListener(listener);
        }
    }

    @Nullable
    public static Subscription listen(Context ctx) {
        if (!Session.canSyncOnline(ctx)) return null;
        final Context app = ctx.getApplicationContext();
        try {
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
                    final String id = s.getKey();
                    if (id != null) {
                        AppExecutors.io().execute(() -> LocusDatabase.get(app).hazardDao().deleteById(id));
                    }
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
            return new Subscription(ref, l);
        } catch (Exception e) {
            Log.w(TAG, "Realtime Database not available (databaseURL missing?)", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void store(Context app, DataSnapshot s, boolean announceNew) {
        Object raw = s.getValue();
        if (s.getKey() == null || !(raw instanceof Map)) return;
        final Hazard h = Hazard.fromMap(s.getKey(), (Map<String, Object>) raw);
        if (h == null) return;
        AppExecutors.io().execute(() -> {
            LocusDatabase db = LocusDatabase.get(app);
            boolean existed = db.hazardDao().exists(h.id) > 0;
            db.hazardDao().insert(h);
            // Alert only for hazards that are new, close to the user and reported by somebody else -
            // never for your own report, old data, or something on the other side of the city.
            double[] last = AppPrefs.lastLocation(app);
            boolean near = GeoUtils.distance(last[0], last[1], h.lat, h.lon) < 5000;
            boolean recent = System.currentTimeMillis() - h.createdAt < 30 * 60 * 1000L;
            if (announceNew && !existed && near && recent && !h.reporterUid.equals(Session.uid(app))) {
                HazardAlertReceiver.send(app, h);
            }
        });
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
