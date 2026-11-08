package com.locus.app.data.firebase;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.locus.app.R;
import com.locus.app.data.db.LocusDatabase;
import com.locus.app.model.Comment;
import com.locus.app.model.CommunityRequest;
import com.locus.app.model.RequestType;
import com.locus.app.ui.CommunityActivity;
import com.locus.app.util.AppExecutors;
import com.locus.app.util.NotificationHelper;
import com.locus.app.work.SyncWorker;

import java.util.ArrayList;
import java.util.List;

/**
 * The shared Community board. While the app is open and someone is signed in, ONE real-time Firestore
 * listener keeps the phone's copy (Room) identical to the shared board, so everyone who installed the
 * app and logged in sees every post - their own and everybody else's - and gets a notification for new ones.
 *
 * It is started from whichever screen is open (not only the Community screen), restarted when another
 * person signs in, and reports an honest {@link State} that the Community screen shows, so a missing or
 * misconfigured cloud never fails silently.
 */
public final class CommunitySync implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "CommunitySync";
    private static final String PREFS = "locus_community_sync";
    private static final String KEY_SEEN = "seen_created_at";
    private static final int MAX_NOTIFICATIONS = 3;
    /** Topic every logged-in phone listens to; the Cloud Function publishes new posts to it. */
    public static final String PUSH_TOPIC = "community";

    public enum State {
        /** Firebase is not set up in this build (no google-services.json): posts stay on this phone. */
        LOCAL_ONLY,
        /** Signed in, waiting for the first answer from the shared board. */
        CONNECTING,
        /** Receiving the shared board live. */
        LIVE,
        /** No internet: showing the saved copy; new posts upload when back online. */
        OFFLINE,
        /** Firestore rules refuse access - the rules from the project were not published. */
        NO_PERMISSION,
        /** The Firestore database was never created / the API is off. */
        NO_DATABASE,
        /** Something else went wrong. */
        ERROR
    }

    private static final MutableLiveData<State> STATE = new MutableLiveData<>(State.CONNECTING);
    private static volatile long lastServerUpdate;

    public static LiveData<State> state() {
        return STATE;
    }

    /** Wall-clock time of the last answer that really came from the server (0 = none yet). */
    public static long lastUpdate() {
        return lastServerUpdate;
    }

    private final Application app;
    private int started;
    private ListenerRegistration registration;
    private ListenerRegistration commentRegistration;
    private String registeredUid;

    public CommunitySync(Application app) {
        this.app = app;
    }

    // ---- lifecycle: listen whenever any screen of the app is visible --------------------------------

    @Override
    public void onActivityStarted(Activity a) {
        started++;
        ensureListening();
    }

    @Override
    public void onActivityStopped(Activity a) {
        started = Math.max(0, started - 1);
        if (started == 0) stopListening();
    }

    private void ensureListening() {
        if (!Session.isFirebaseReady(app)) {
            STATE.postValue(State.LOCAL_ONLY);
            return;
        }
        if (!Session.canSyncOnline(app)) { // login screen / logged out: stop listening for the old account
            stopListening();
            return;
        }
        String uid = Session.uid(app);
        if (registration != null && uid.equals(registeredUid)) return;
        stopListening();
        registeredUid = uid;
        STATE.postValue(State.CONNECTING);
        if (hazardSub == null) hazardSub = HazardRepository.listen(app); // road-hazard alerts on every screen
        if (chatWatch == null) chatWatch = ChatRepository.listenForNotifications(app); // new private messages
        subscribeToChatPush(uid);
        registration = FirebaseFirestore.getInstance().collection(CommunityRepository.COLLECTION)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(300)
                .addSnapshotListener((snap, error) -> onSnapshot(uid, snap, error));
        // the comments under every post come live too, so everyone sees the discussion
        commentRegistration = FirebaseFirestore.getInstance().collection(CommentRepository.COLLECTION)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(1000)
                .addSnapshotListener((snap, error) -> onComments(snap, error));
        SyncWorker.enqueueNow(app); // upload anything written while offline
        subscribeToPush();
    }

    /** Instant alerts while the app is closed (needs the optional Cloud Function, see README). */
    private void subscribeToPush() {
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().subscribeToTopic(PUSH_TOPIC);
        } catch (RuntimeException e) {
            Log.w(TAG, "Push subscription failed", e);
        }
    }

    /** Called when someone logs out, so the phone stops receiving that account's alerts. */
    public static void unsubscribeFromPush() {
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().unsubscribeFromTopic(PUSH_TOPIC);
        } catch (RuntimeException ignored) {
            // Firebase not configured
        }
    }

    private HazardRepository.Subscription hazardSub;
    private ChatRepository.Watch chatWatch;

    /** Private messages while the app is closed (needs the optional Cloud Function): one topic per person. */
    private void subscribeToChatPush(String uid) {
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().subscribeToTopic(chatTopic(uid));
        } catch (RuntimeException e) {
            Log.w(TAG, "Chat push subscription failed", e);
        }
    }

    public static String chatTopic(String uid) {
        return "chat_" + uid.replaceAll("[^a-zA-Z0-9-_.~%]", "_");
    }

    public static void unsubscribeFromChatPush(String uid) {
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().unsubscribeFromTopic(chatTopic(uid));
        } catch (RuntimeException ignored) {
            // Firebase not configured
        }
    }

    private void stopListening() {
        if (registration != null) {
            registration.remove();
            registration = null;
        }
        if (commentRegistration != null) {
            commentRegistration.remove();
            commentRegistration = null;
        }
        if (hazardSub != null) {
            hazardSub.stop();
            hazardSub = null;
        }
        if (chatWatch != null) {
            chatWatch.stop();
            chatWatch = null;
        }
        registeredUid = null;
    }

    // ---- incoming data -----------------------------------------------------------------------------

    private void onSnapshot(String myUid, QuerySnapshot snap, FirebaseFirestoreException error) {
        if (error != null || snap == null) {
            Log.w(TAG, "Shared board unavailable", error);
            STATE.postValue(stateFor(error));
            return;
        }
        boolean fromCache = snap.getMetadata().isFromCache();
        if (!fromCache) lastServerUpdate = System.currentTimeMillis();
        STATE.postValue(fromCache ? State.OFFLINE : State.LIVE);

        final List<CommunityRequest> list = new ArrayList<>();
        for (DocumentSnapshot d : snap.getDocuments()) {
            CommunityRequest r = CommunityRequest.fromMap(d.getId(), d.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE));
            if (r != null) list.add(r);
        }
        // A cached answer on a brand-new install is empty and would make every old post look "new".
        if (!fromCache || hasBaseline(app)) notifyAboutNew(snap, myUid);
        AppExecutors.io().execute(() -> LocusDatabase.get(app).requestDao().replaceSynced(list));
    }

    private void onComments(QuerySnapshot snap, FirebaseFirestoreException error) {
        if (error != null || snap == null) {
            Log.w(TAG, "Comments unavailable", error);
            return; // the posts' own state banner already tells the person what is wrong
        }
        final List<Comment> list = new ArrayList<>();
        for (DocumentSnapshot d : snap.getDocuments()) {
            Comment c = Comment.fromMap(d.getId(), d.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE));
            if (c != null) list.add(c);
        }
        AppExecutors.io().execute(() -> LocusDatabase.get(app).commentDao().replaceSynced(list));
    }

    private static boolean hasBaseline(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_SEEN, 0) > 0;
    }

    static State stateFor(FirebaseFirestoreException e) {
        if (e == null) return State.ERROR;
        switch (e.getCode()) {
            case PERMISSION_DENIED:
            case UNAUTHENTICATED:
                return State.NO_PERMISSION;
            case UNAVAILABLE:
            case DEADLINE_EXCEEDED:
                return State.OFFLINE;
            case NOT_FOUND:
            case FAILED_PRECONDITION:
                return State.NO_DATABASE;
            default:
                return State.ERROR;
        }
    }

    private void notifyAboutNew(QuerySnapshot snap, String myUid) {
        List<CommunityRequest> added = new ArrayList<>();
        for (DocumentChange c : snap.getDocumentChanges()) {
            if (c.getType() != DocumentChange.Type.ADDED) continue;
            if (c.getDocument().getMetadata().hasPendingWrites()) continue; // written by this phone
            CommunityRequest r = CommunityRequest.fromMap(c.getDocument().getId(),
                    c.getDocument().getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE));
            if (r != null) added.add(r);
        }
        announce(app, added, myUid);
    }

    /**
     * A notification for posts by other people that this phone has not announced yet. "Announced" is a
     * remembered timestamp shared by every delivery path (live listener, background check, push), so a post
     * is never announced twice, and reinstalling or opening the app never floods the person with old posts.
     */
    public static synchronized void announce(Context app, List<CommunityRequest> candidates, String myUid) {
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long seen = prefs.getLong(KEY_SEEN, 0);
        long newest = seen;
        List<CommunityRequest> fresh = new ArrayList<>();
        for (CommunityRequest r : candidates) {
            newest = Math.max(newest, r.createdAt);
            if (seen > 0 && r.createdAt > seen && !r.ownerUid.equals(myUid)
                    && CommunityRequest.OPEN.equals(r.status)) {
                fresh.add(r);
            }
        }
        if (seen == 0) newest = Math.max(newest, 1); // first run: this is the baseline, announce nothing old
        if (newest != seen) prefs.edit().putLong(KEY_SEEN, newest).apply();

        int shown = 0;
        for (CommunityRequest r : fresh) {
            if (shown++ >= MAX_NOTIFICATIONS) break;
            NotificationHelper.notify(app, r.isSos() ? NotificationHelper.CH_HAZARD : NotificationHelper.CH_COMMUNITY,
                    r.id.hashCode(), app.getString(RequestType.fromName(r.type).labelRes), r.title,
                    CommunityActivity.class);
        }
        if (fresh.size() > MAX_NOTIFICATIONS) {
            NotificationHelper.notify(app, NotificationHelper.CH_COMMUNITY, 7007,
                    app.getString(R.string.title_community),
                    app.getString(R.string.more_new_requests, fresh.size() - MAX_NOTIFICATIONS),
                    CommunityActivity.class);
        }
    }

    // ---- unused lifecycle callbacks ----------------------------------------------------------------

    @Override
    public void onActivityCreated(Activity a, Bundle state) {
    }

    @Override
    public void onActivityResumed(Activity a) {
    }

    @Override
    public void onActivityPaused(Activity a) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity a, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity a) {
    }
}
