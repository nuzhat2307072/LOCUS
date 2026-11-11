package com.locus.app.data.firebase;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.locus.app.data.db.LocusDatabase;
import com.locus.app.model.CommunityRequest;
import com.locus.app.util.AppExecutors;
import com.locus.app.work.SyncWorker;

import java.util.concurrent.TimeUnit;


/**
 * Community requests: Room is the local source of truth (works offline), Cloud Firestore is the
 * shared online copy. The UI only ever observes Room.
 */
public final class CommunityRepository {
    private static final String TAG = "CommunityRepo";
    public static final String COLLECTION = "requests";
    private static final long CONFIRM_WAIT_S = 4;

    private CommunityRepository() {
    }

    /** Result of posting: {@code sharedNow} = the shared board confirmed it within a few seconds. */
    public interface ShareResult {
        /** One of {@link #SHARED}, {@link #QUEUED}, {@link #REFUSED}, {@link #LOCAL_ONLY}. */
        void onResult(int outcome);
    }

    /** The shared board confirmed the post: everyone can see it. */
    public static final int SHARED = 0;
    /** Saved on this phone; it uploads by itself as soon as there is a connection. */
    public static final int QUEUED = 1;
    /** The board's rules refused it (rules not published / wrong project) - it will not be shared. */
    public static final int REFUSED = 2;
    /** No cloud account in this build: the post stays on this phone. */
    public static final int LOCAL_ONLY = 3;

    /**
     * Create: save to Room first (instant, offline-safe), then send to the shared board and wait a few seconds
     * for its confirmation, so the person is told the truth: "everyone can see it" or "saved, will be shared
     * as soon as you are online".
     */
    public static void add(Context ctx, CommunityRequest r, ShareResult onResult) {
        final Context app = ctx.getApplicationContext();
        AppExecutors.io().execute(() -> {
            r.synced = false;
            LocusDatabase.get(app).requestDao().insert(r);
            int outcome = LOCAL_ONLY;
            if (Session.canSyncOnline(app)) {
                outcome = QUEUED;
                Task<Void> t = FirebaseFirestore.getInstance().collection(COLLECTION).document(r.id).set(r.toMap());
                t.addOnSuccessListener(v -> AppExecutors.io().execute(
                        () -> LocusDatabase.get(app).requestDao().markSynced(r.id)));
                t.addOnFailureListener(e -> {
                    Log.w(TAG, "Post refused or failed, will retry in background", e);
                    SyncWorker.enqueueNow(app);
                });
                try {
                    Tasks.await(t, CONFIRM_WAIT_S, TimeUnit.SECONDS);
                    outcome = SHARED;
                } catch (java.util.concurrent.ExecutionException e) {
                    if (isRefusal(e.getCause())) outcome = REFUSED;
                    else SyncWorker.enqueueNow(app);
                } catch (Exception e) {
                    SyncWorker.enqueueNow(app); // offline or slow: the background job keeps trying
                }
            }
            if (outcome == SHARED || outcome == QUEUED) GroupFeedRepository.shareCommunityPost(app, r); // also in my groups
            final int result = outcome;
            if (onResult != null) AppExecutors.main().post(() -> onResult.onResult(result));
        });
    }

    /** Update: change the status (OPEN / DONE). */
    public static void setStatus(Context ctx, CommunityRequest r, String status) {
        final Context app = ctx.getApplicationContext();
        AppExecutors.io().execute(() -> {
            LocusDatabase.get(app).requestDao().setStatus(r.id, status);
            if (!Session.canSyncOnline(app)) return;
            // a fulfilled request closes its private chats (they stay readable); reopening opens them again
            ChatRepository.setClosedForRequest(app, r.id, CommunityRequest.DONE.equals(status));
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
            LocusDatabase.get(app).commentDao().deleteForRequest(r.id);
            if (Session.canSyncOnline(app)) {
                ChatRepository.setClosedForRequest(app, r.id, true); // nobody can write to a deleted request's chats
                FirebaseFirestore.getInstance().collection(COLLECTION).document(r.id).delete()
                        .addOnFailureListener(e -> Log.w(TAG, "Delete failed (rules?)", e));
            }
        });
    }

    /**
     * A post written before the cloud was connected carries the phone-only identity of the same person.
     * Re-label it with the signed-in cloud identity, otherwise the board's rules (owner must be the sender)
     * would refuse it forever.
     */
    public static void adoptIfOwnLocalPost(Context app, CommunityRequest r) {
        String uid = Session.uid(app);
        if (uid.equals(r.ownerUid)) return;
        String email = Session.email(app);
        if (email == null || !com.locus.app.security.LocalAccounts.uid(email).equals(r.ownerUid)) return;
        r.ownerUid = uid;
        r.ownerName = Session.displayName(app);
        LocusDatabase.get(app).requestDao().insert(r);
    }

    /** True when the server said no (rules), as opposed to "cannot reach the server". */
    public static boolean isRefusal(Throwable e) {
        return e instanceof com.google.firebase.firestore.FirebaseFirestoreException
                && ((com.google.firebase.firestore.FirebaseFirestoreException) e).getCode()
                == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED;
    }

    /**
     * Upload for the background job: create the post if the board does not have it yet, otherwise only
     * send the status change (the rules let owners change nothing else).
     */
    public static Task<Void> safeUpload(Context app, CommunityRequest r) {
        final DocumentReference ref = FirebaseFirestore.getInstance().collection(COLLECTION).document(r.id);
        return ref.get().continueWithTask(t -> {
            if (!t.isSuccessful()) {
                Exception e = t.getException() != null ? t.getException() : new IllegalStateException("read failed");
                return Tasks.<Void>forException(e);
            }
            DocumentSnapshot d = t.getResult();
            if (d != null && d.exists()) return ref.update("status", r.status);
            return ref.set(r.toMap());
        });
    }

    /** Push one request now; on failure the SyncWorker retries later. */
    public static void push(Context app, CommunityRequest r) {
        if (!Session.canSyncOnline(app)) return;
        adoptIfOwnLocalPost(app, r);
        safeUpload(app, r)
                .addOnSuccessListener(v -> AppExecutors.io().execute(
                        () -> LocusDatabase.get(app).requestDao().markSynced(r.id)))
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Push failed, will retry in background", e);
                    SyncWorker.enqueueNow(app);
                });
    }
}
