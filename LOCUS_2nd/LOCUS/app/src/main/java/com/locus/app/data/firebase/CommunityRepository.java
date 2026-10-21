package com.locus.app.data.firebase;

import android.content.Context;
import android.util.Log;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.locus.app.data.db.LocusDatabase;
import com.locus.app.model.CommunityRequest;
import com.locus.app.util.AppExecutors;
import com.locus.app.work.SyncWorker;

import java.util.ArrayList;
import java.util.List;

/**
 * Community requests: Room is the local source of truth (works offline), Cloud Firestore is the
 * shared online copy. The UI only ever observes Room.
 */
public final class CommunityRepository {
    private static final String TAG = "CommunityRepo";
    public static final String COLLECTION = "requests";

    private CommunityRepository() {
    }

    /** Real-time listener: every Firestore change is written into Room. Returns null in offline/guest mode. */
    public static ListenerRegistration listen(Context ctx) {
        if (!Session.canSyncOnline(ctx)) return null;
        final Context app = ctx.getApplicationContext();
        return FirebaseFirestore.getInstance().collection(COLLECTION)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(200)
                .addSnapshotListener((snap, error) -> {
                    if (error != null || snap == null) {
                        Log.w(TAG, "Firestore listen failed", error); // e.g. permission denied by security rules
                        return;
                    }
                    final List<CommunityRequest> list = new ArrayList<>();
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        CommunityRequest r = CommunityRequest.fromMap(d.getId(), d.getData());
                        if (r != null) list.add(r);
                    }
                    AppExecutors.io().execute(() -> LocusDatabase.get(app).requestDao().replaceSynced(list));
                });
    }

    /** Create: save to Room first (instant, offline-safe), then push to Firestore. */
    public static void add(Context ctx, CommunityRequest r, Runnable onSaved) {
        final Context app = ctx.getApplicationContext();
        AppExecutors.io().execute(() -> {
            r.synced = false;
            LocusDatabase.get(app).requestDao().insert(r);
            if (onSaved != null) AppExecutors.main().post(onSaved);
            push(app, r);
        });
    }

    /** Update: change the status (OPEN / DONE). */
    public static void setStatus(Context ctx, CommunityRequest r, String status) {
        final Context app = ctx.getApplicationContext();
        AppExecutors.io().execute(() -> {
            LocusDatabase.get(app).requestDao().setStatus(r.id, status);
            if (!Session.canSyncOnline(app)) return;
            FirebaseFirestore.getInstance().collection(COLLECTION).document(r.id).update("status", status)
                    .addOnSuccessListener(v -> AppExecutors.io().execute(
                            () -> LocusDatabase.get(app).requestDao().markSynced(r.id)))
                    .addOnFailureListener(e -> SyncWorker.enqueueNow(app));
        });
    }

    /** Delete from Room and Firestore. */
    public static void delete(Context ctx, CommunityRequest r) {
        final Context app = ctx.getApplicationContext();
        AppExecutors.io().execute(() -> {
            LocusDatabase.get(app).requestDao().deleteById(r.id);
            if (Session.canSyncOnline(app)) {
                FirebaseFirestore.getInstance().collection(COLLECTION).document(r.id).delete()
                        .addOnFailureListener(e -> Log.w(TAG, "Delete failed (rules?)", e));
            }
        });
    }

    /** Push one request to Firestore; on failure the SyncWorker retries later. */
    public static void push(Context app, CommunityRequest r) {
        if (!Session.canSyncOnline(app)) return;
        FirebaseFirestore.getInstance().collection(COLLECTION).document(r.id).set(r.toMap())
                .addOnSuccessListener(v -> AppExecutors.io().execute(
                        () -> LocusDatabase.get(app).requestDao().markSynced(r.id)))
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Push failed, will retry in background", e);
                    SyncWorker.enqueueNow(app);
                });
    }
}
