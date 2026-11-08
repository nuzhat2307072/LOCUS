package com.locus.app.data.firebase;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.FirebaseFirestore;
import com.locus.app.data.db.LocusDatabase;
import com.locus.app.model.Comment;
import com.locus.app.util.AppExecutors;
import com.locus.app.work.SyncWorker;

import java.util.UUID;

/**
 * Comments under Community posts. Same idea as the posts: Room first (instant, works offline), then the shared
 * board (Firestore collection "comments", one document per comment, shared by every phone with the app).
 */
public final class CommentRepository {
    private static final String TAG = "CommentRepo";
    public static final String COLLECTION = "comments";

    private CommentRepository() {
    }

    /** Adds a comment to a post. Blank text is ignored; the text is cut to {@link Comment#MAX_LENGTH}. */
    public static void add(Context ctx, String requestId, String text) {
        add(ctx, requestId, text, null);
    }

    /** A reply: {@code parent} is the comment that is answered (null for a comment under the post itself). */
    public static void add(Context ctx, String requestId, String text, Comment parent) {
        final Context app = ctx.getApplicationContext();
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty() || requestId == null) return;
        if (clean.length() > Comment.MAX_LENGTH) clean = clean.substring(0, Comment.MAX_LENGTH);
        final Comment c = new Comment();
        c.id = UUID.randomUUID().toString();
        c.requestId = requestId;
        c.ownerUid = Session.uid(app);
        c.ownerName = Session.displayName(app);
        c.text = clean;
        if (parent != null) {
            // replies stay one level deep: answering a reply files the answer under the same top comment
            c.parentId = parent.parentId == null || parent.parentId.isEmpty() ? parent.id : parent.parentId;
            c.replyToName = parent.ownerName == null ? "" : parent.ownerName;
        }
        c.createdAt = System.currentTimeMillis();
        c.synced = false;
        AppExecutors.io().execute(() -> {
            LocusDatabase.get(app).commentDao().insert(c);
            push(app, c);
        });
    }

    /** Uploads one comment now; on failure the SyncWorker retries later. */
    public static void push(Context app, Comment c) {
        if (!Session.canSyncOnline(app)) return;
        upload(c)
                .addOnSuccessListener(v -> AppExecutors.io().execute(
                        () -> LocusDatabase.get(app).commentDao().markSynced(c.id)))
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Comment not sent now, will retry in background", e);
                    if (!CommunityRepository.isRefusal(e)) SyncWorker.enqueueNow(app);
                });
    }

    /** Used by the background job too. */
    public static Task<Void> upload(Comment c) {
        return FirebaseFirestore.getInstance().collection(COLLECTION).document(c.id).set(c.toMap());
    }

    public static void delete(Context ctx, Comment c) {
        final Context app = ctx.getApplicationContext();
        AppExecutors.io().execute(() -> {
            LocusDatabase.get(app).commentDao().deleteById(c.id);
            if (Session.canSyncOnline(app)) {
                FirebaseFirestore.getInstance().collection(COLLECTION).document(c.id).delete()
                        .addOnFailureListener(e -> Log.w(TAG, "Delete failed (rules?)", e));
            }
        });
    }
}
