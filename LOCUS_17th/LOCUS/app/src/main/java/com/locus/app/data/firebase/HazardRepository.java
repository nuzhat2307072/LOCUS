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
import com.locus.app.model.HazardTrust;
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
                DatabaseReference ref = Rtdb.get().getReference(NODE);
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
        final Hazard h = Hazard.fromMap(s.getKey(), (Map<String, Object>) raw, Session.uid(app));
        if (h != null) accept(app, h, announceNew);
    }

    /** A hazard that exists on the shared board: keep it, and alert when it is new, close and from somebody else. */
    static void accept(Context app, final Hazard h, boolean announceNew) {
        AppExecutors.io().execute(() -> {
            LocusDatabase db = LocusDatabase.get(app);
            if (h.isExpired(System.currentTimeMillis())) { // nobody confirmed it for long enough: it is gone
                db.hazardDao().deleteById(h.id);
                removeRemote(app, h.id);
                return;
            }
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
            if (h.isExpired(System.currentTimeMillis())) return; // it expired: nothing was "cleared"
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

    private static void removeRemote(Context app, String id) {
        if (!Session.canSyncOnline(app)) return;
        try {
            Rtdb.get().getReference(NODE).child(id).removeValue();
        } catch (Exception e) {
            Log.w(TAG, "Remove skipped", e);
        }
    }

    /** Metres from where this person last was to the hazard, or -1 when the position is not known yet. */
    public static double distanceToMe(Context ctx, Hazard h) {
        if (!AppPrefs.hasLastLocation(ctx)) return -1;
        double[] at = AppPrefs.lastLocation(ctx);
        return GeoUtils.distance(at[0], at[1], h.lat, h.lon);
    }

    /** True when this person is close enough to the hazard to vote on it. */
    public static boolean canVoteHere(Context ctx, Hazard h) {
        return HazardTrust.canVote(distanceToMe(ctx, h));
    }

    /**
     * "Still there" (still = true) or "Cleared". One vote per person, stored under the person's id, so voting again
     * only changes it. Only people who are near the hazard may vote (this is how a report gets verified by others
     * around the place); the distance is stored with the vote so everybody can see how many nearby people agree.
     * Updates the reporter's reputation and removes the hazard when enough nearby people say it is gone.
     *
     * @return false when the vote was not taken because the person is too far away (or their position is unknown)
     */
    public static boolean vote(Context ctx, final Hazard h, final boolean still) {
        final Context app = ctx.getApplicationContext();
        final String uid = Session.uid(app);
        final String value = still ? "still" : "cleared";
        final String previous = h.myVote;
        if (uid == null || uid.isEmpty() || value.equals(previous)) return true;
        final double dist = distanceToMe(app, h);
        if (!HazardTrust.canVote(dist)) return false;
        AppExecutors.io().execute(() -> {
            long now = System.currentTimeMillis();
            int s = h.stillVotes - ("still".equals(previous) ? 1 : 0) + (still ? 1 : 0);
            int c = h.clearedVotes - ("cleared".equals(previous) ? 1 : 0) + (still ? 0 : 1);
            // the voter is nearby by construction, so the nearby counters move the same way
            int ns = h.nearStill - ("still".equals(previous) ? 1 : 0) + (still ? 1 : 0);
            int nc = h.nearCleared - ("cleared".equals(previous) ? 1 : 0) + (still ? 0 : 1);
            boolean remove = !still && HazardTrust.removedByVotes(Math.max(ns, 0), Math.max(nc, 0));
            LocusDatabase db = LocusDatabase.get(app);
            if (remove) {
                db.hazardDao().deleteById(h.id);
            } else {
                Hazard copy = db.hazardDao().find(h.id);
                if (copy != null) {
                    copy.myVote = value;
                    copy.stillVotes = s;
                    copy.clearedVotes = c;
                    copy.nearStill = Math.max(ns, 0);
                    copy.nearCleared = Math.max(nc, 0);
                    if (still) copy.lastConfirmedAt = now;
                    db.hazardDao().insert(copy);
                }
            }
            if (!Session.canSyncOnline(app)) return;
            try {
                DatabaseReference ref = Rtdb.get().getReference(NODE).child(h.id);
                if (remove) {
                    ref.removeValue();
                } else {
                    Map<String, Object> up = new java.util.HashMap<>();
                    up.put("votes/" + uid, value);
                    up.put("voteDist/" + uid, Math.round(dist));
                    if (still) up.put("lastConfirmedAt", now);
                    ref.updateChildren(up).addOnFailureListener(e -> Log.w(TAG, "Vote failed", e));
                }
                boolean mine = uid.equals(h.reporterUid);
                int delta = HazardTrust.reputationDelta(previous, value, now - h.createdAt, remove);
                if (!mine && delta != 0 && h.reporterUid != null && !h.reporterUid.isEmpty()) {
                    addReputation(h.reporterUid, delta);
                }
            } catch (Exception e) {
                Log.w(TAG, "Vote skipped", e);
            }
        });
        return true;
    }

    private static void addReputation(String uid, final int delta) {
        Rtdb.get().getReference("reputation").child(uid)
                .runTransaction(new com.google.firebase.database.Transaction.Handler() {
                    @NonNull
                    @Override
                    public com.google.firebase.database.Transaction.Result doTransaction(
                            @NonNull com.google.firebase.database.MutableData data) {
                        Long v = data.getValue(Long.class);
                        data.setValue((v == null ? 0L : v) + delta);
                        return com.google.firebase.database.Transaction.success(data);
                    }

                    @Override
                    public void onComplete(@Nullable DatabaseError error, boolean committed,
                                           @Nullable DataSnapshot snapshot) {
                        if (error != null) Log.w(TAG, "Reputation update failed: " + error.getMessage());
                    }
                });
    }

    /** Reads a person's reputation points (0 when unknown or offline); the callback runs on the main thread. */
    public static void fetchReputation(Context ctx, String uid, final java.util.function.Consumer<Long> done) {
        if (uid == null || uid.isEmpty() || !Session.canSyncOnline(ctx)) {
            done.accept(0L);
            return;
        }
        try {
            Rtdb.get().getReference("reputation").child(uid).get()
                    .addOnSuccessListener(s -> {
                        Long v = s.getValue(Long.class);
                        done.accept(v == null ? 0L : v);
                    })
                    .addOnFailureListener(e -> done.accept(0L));
        } catch (Exception e) {
            done.accept(0L);
        }
    }

    /** Delete from Room and the Realtime Database. */
    public static void delete(Context ctx, Hazard h) {
        final Context app = ctx.getApplicationContext();
        AppExecutors.io().execute(() -> {
            LocusDatabase.get(app).hazardDao().deleteById(h.id);
            if (!Session.canSyncOnline(app)) return;
            try {
                Rtdb.get().getReference(NODE).child(h.id).removeValue()
                        .addOnFailureListener(e -> Log.w(TAG, "Remove failed (rules?)", e));
            } catch (Exception e) {
                Log.w(TAG, "Remove skipped", e);
            }
        });
    }

    public static void push(Context app, Hazard h) {
        if (!Session.canSyncOnline(app)) return;
        try {
            Rtdb.get().getReference(NODE).child(h.id).setValue(h.toMap())
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
