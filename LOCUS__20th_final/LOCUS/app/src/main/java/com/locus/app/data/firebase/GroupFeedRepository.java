package com.locus.app.data.firebase;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.Query;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.locus.app.R;
import com.locus.app.model.CommunityRequest;
import com.locus.app.model.FamilyGroup;
import com.locus.app.model.GroupPost;
import com.locus.app.model.GroupPostRules;
import com.locus.app.model.GroupRules;
import com.locus.app.model.NotifyRules;
import com.locus.app.ui.GroupFeedActivity;
import com.locus.app.util.AppPrefs;
import com.locus.app.util.ImageCodec;
import com.locus.app.util.NotificationHelper;
import com.locus.app.util.NotifyPrefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The stream of a family-and-friends group (Realtime Database): messages, photos, trip and party plans (with
 * voting), help requests, and the Community posts and SOS the members shared.
 *
 * Paths: groupPosts/{gid}/{pid}, groupVotes/{gid}/{pid}/{uid}, groupImages/{gid}/{pid}.
 */
public final class GroupFeedRepository {
    private static final String TAG = "GroupFeedRepository";
    private static final String PREFS = "locus_group_feed";
    /** The newest posts kept live on screen. */
    public static final int WINDOW = 100;

    private GroupFeedRepository() {
    }

    public interface Done {
        void done(@Nullable String error);
    }

    public interface FeedListener {
        void onChanged(List<GroupPost> posts, Map<String, Map<String, String>> votes);
    }

    private static DatabaseReference root() {
        return Rtdb.get().getReference();
    }

    private static boolean offline(Context ctx) {
        return !Session.canSyncOnline(ctx) || Rtdb.url() == null;
    }

    private static String classify(@Nullable Exception e) {
        String m = e == null || e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (m.contains("permission")) return "rules";
        if (m.contains("network") || m.contains("offline") || m.contains("disconnected")) return "offline";
        return "other:" + (e == null ? "unknown" : e.getMessage());
    }

    private static String str(DataSnapshot s, String key) {
        String v = s.child(key).getValue(String.class);
        return v == null ? "" : v;
    }

    private static long num(DataSnapshot s, String key) {
        Long v = s.child(key).getValue(Long.class);
        return v == null ? 0 : v;
    }

    private static double dbl(DataSnapshot s, String key) {
        Double v = s.child(key).getValue(Double.class);
        return v == null ? 0 : v;
    }

    // ---- write ------------------------------------------------------------------------------

    private static Map<String, Object> toMap(Context app, GroupPost p) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", p.type);
        m.put("uid", Session.uid(app));
        m.put("name", GroupPostRules.clean(Session.displayName(app), 60));
        m.put("at", ServerValue.TIMESTAMP);
        if (!p.text.isEmpty()) m.put("text", GroupPostRules.clean(p.text, GroupPostRules.MAX_TEXT));
        if (!p.title.isEmpty()) m.put("title", GroupPostRules.clean(p.title, GroupPostRules.MAX_TITLE));
        if (p.when > 0) m.put("when", p.when);
        if (!p.place.isEmpty()) m.put("place", GroupPostRules.clean(p.place, GroupPostRules.MAX_PLACE));
        if (p.hasLocation) {
            m.put("lat", p.lat);
            m.put("lon", p.lon);
        }
        if (!p.kind.isEmpty()) m.put("kind", p.kind);
        if (p.hasPhoto) m.put("hasPhoto", true);
        if (!p.ref.isEmpty()) m.put("ref", p.ref);
        return m;
    }

    /** Sends a post to one group; a photo travels in the same atomic write. */
    public static void post(Context ctx, String gid, GroupPost p, @Nullable String photoBase64, final Done cb) {
        final Context app = ctx.getApplicationContext();
        p.hasPhoto = photoBase64 != null;
        if (!GroupPostRules.validNew(p.type, p.title, p.text, p.hasPhoto, p.when, System.currentTimeMillis())) {
            cb.done("invalid");
            return;
        }
        if (offline(app)) {
            cb.done(Session.canSyncOnline(app) ? "nodb" : "cloud");
            return;
        }
        try {
            String pid = root().child("groupPosts").child(gid).push().getKey();
            Map<String, Object> up = new HashMap<>();
            up.put("groupPosts/" + gid + "/" + pid, toMap(app, p));
            if (photoBase64 != null) up.put("groupImages/" + gid + "/" + pid, photoBase64);
            root().updateChildren(up).addOnCompleteListener(t -> cb.done(t.isSuccessful() ? null : classify(t.getException())));
        } catch (Exception e) {
            cb.done("other:" + e.getMessage());
        }
    }

    /** Sets (or, with null, takes back) my vote on a plan or a help request. */
    public static void vote(Context ctx, String gid, String pid, @Nullable String choice, final Done cb) {
        try {
            DatabaseReference ref = root().child("groupVotes").child(gid).child(pid).child(Session.uid(ctx));
            (choice == null ? ref.removeValue() : ref.setValue(choice)).addOnCompleteListener(t ->
                    cb.done(t.isSuccessful() ? null : classify(t.getException())));
        } catch (Exception e) {
            cb.done("other:" + e.getMessage());
        }
    }

    /** The author or the admin removes a post with its votes and photo. */
    public static void delete(Context ctx, String gid, String pid, final Done cb) {
        Map<String, Object> up = new HashMap<>();
        up.put("groupPosts/" + gid + "/" + pid, null);
        up.put("groupVotes/" + gid + "/" + pid, null);
        up.put("groupImages/" + gid + "/" + pid, null);
        try {
            root().updateChildren(up).addOnCompleteListener(t -> cb.done(t.isSuccessful() ? null : classify(t.getException())));
        } catch (Exception e) {
            cb.done("other:" + e.getMessage());
        }
    }

    // ---- read -------------------------------------------------------------------------------

    private static GroupPost parse(String gid, DataSnapshot s) {
        GroupPost p = new GroupPost();
        p.id = s.getKey() == null ? "" : s.getKey();
        p.groupId = gid;
        p.type = str(s, "type");
        p.uid = str(s, "uid");
        p.name = str(s, "name");
        p.at = num(s, "at");
        p.text = str(s, "text");
        p.title = str(s, "title");
        p.when = num(s, "when");
        p.place = str(s, "place");
        p.hasLocation = s.hasChild("lat") && s.hasChild("lon");
        p.lat = dbl(s, "lat");
        p.lon = dbl(s, "lon");
        p.kind = str(s, "kind");
        p.hasPhoto = Boolean.TRUE.equals(s.child("hasPhoto").getValue(Boolean.class));
        p.ref = str(s, "ref");
        return p;
    }

    /** The newest posts of a group and everyone's votes, live. */
    public static ChatRepository.Watch watch(Context ctx, final String gid, final FeedListener l) {
        final ChatRepository.Watch w = new ChatRepository.Watch();
        if (offline(ctx)) return w;
        try {
            final Query posts = root().child("groupPosts").child(gid).orderByChild("at").limitToLast(WINDOW);
            final DatabaseReference votes = root().child("groupVotes").child(gid);
            posts.keepSynced(true);
            votes.keepSynced(true);
            final List<GroupPost>[] cur = new List[]{new ArrayList<GroupPost>()};
            final Map<String, Map<String, String>>[] vm = new Map[]{new HashMap<String, Map<String, String>>()};
            final ValueEventListener pl = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot s) {
                    List<GroupPost> list = new ArrayList<>();
                    for (DataSnapshot c : s.getChildren()) {
                        GroupPost p = parse(gid, c);
                        if (GroupPostRules.isKnownType(p.type)) list.add(p);
                    }
                    Collections.sort(list, (a, b) -> Long.compare(a.at, b.at));
                    cur[0] = list;
                    l.onChanged(cur[0], vm[0]);
                }

                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    Log.w(TAG, "feed cancelled: " + e.getMessage());
                }
            };
            final ValueEventListener vl = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot s) {
                    Map<String, Map<String, String>> m = new HashMap<>();
                    for (DataSnapshot post : s.getChildren()) {
                        Map<String, String> per = new HashMap<>();
                        for (DataSnapshot v : post.getChildren()) {
                            String val = v.getValue(String.class);
                            if (v.getKey() != null && GroupPostRules.isVote(val)) per.put(v.getKey(), val);
                        }
                        if (post.getKey() != null) m.put(post.getKey(), per);
                    }
                    vm[0] = m;
                    l.onChanged(cur[0], vm[0]);
                }

                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    Log.w(TAG, "votes cancelled: " + e.getMessage());
                }
            };
            posts.addValueEventListener(pl);
            votes.addValueEventListener(vl);
            w.add(() -> posts.removeEventListener(pl));
            w.add(() -> votes.removeEventListener(vl));
        } catch (Exception e) {
            Log.w(TAG, "watch failed", e);
        }
        return w;
    }

    public interface PhotoCallback {
        void onPhoto(@Nullable Bitmap bmp);
    }

    /** A post's photo, fetched once (the screen keeps it in memory afterwards). */
    public static void loadPhoto(Context ctx, String gid, String pid, final PhotoCallback cb) {
        if (offline(ctx)) {
            cb.onPhoto(null);
            return;
        }
        try {
            root().child("groupImages").child(gid).child(pid).get().addOnCompleteListener(t -> {
                String data = t.isSuccessful() ? t.getResult().getValue(String.class) : null;
                cb.onPhoto(data == null ? null : ImageCodec.decode(data));
            });
        } catch (Exception e) {
            cb.onPhoto(null);
        }
    }

    // ---- sharing Community posts and SOS into the groups -----------------------------------------

    /**
     * A Community post or an SOS the person just made is also shown in every group they are in (when the
     * "share with my groups" switch is on). The post id is reused, so the same post is never shown twice.
     */
    public static void shareCommunityPost(Context ctx, final CommunityRequest r) {
        final Context app = ctx.getApplicationContext();
        if (!AppPrefs.shareWithGroups(app) || offline(app)) return;
        try {
            final String me = Session.uid(app);
            root().child("userGroups").child(me).get().addOnCompleteListener(t -> {
                if (!t.isSuccessful()) return;
                for (DataSnapshot g : t.getResult().getChildren()) {
                    if (g.getKey() == null) continue;
                    GroupPost p = new GroupPost();
                    p.type = r.isSos() ? GroupPostRules.EMERGENCY : GroupPostRules.COMMUNITY;
                    p.title = r.title == null ? "" : r.title;
                    p.text = r.description == null ? "" : r.description;
                    p.kind = r.type == null ? "" : r.type;
                    p.hasLocation = r.hasLocation;
                    p.lat = r.lat;
                    p.lon = r.lon;
                    p.ref = r.id;
                    root().child("groupPosts").child(g.getKey()).child(r.id).setValue(toMap(app, p))
                            .addOnFailureListener(e -> Log.w(TAG, "share to group failed: " + e.getMessage()));
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "shareCommunityPost failed", e);
        }
    }

    // ---- notifications ---------------------------------------------------------------------------

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String seenKey(String me, String gid) {
        return "seen|" + me + "|" + gid;
    }

    /** Opening a group marks everything in it as seen (and clears its notification). */
    public static void markSeen(Context ctx, String gid, long latestAt) {
        Context app = ctx.getApplicationContext();
        String me = Session.uid(app);
        long old = sp(app).getLong(seenKey(me, gid), 0);
        if (latestAt > old) sp(app).edit().putLong(seenKey(me, gid), latestAt).apply();
        androidx.core.app.NotificationManagerCompat.from(app).cancel(notificationId(gid));
    }

    private static int notificationId(String gid) {
        return 73000 + (gid.hashCode() & 0xFFFF);
    }

    /** Shows a notification for a post from someone else that was not seen yet. */
    static void announce(Context app, String me, String gid, String groupName, GroupPost p) {
        SharedPreferences sp = sp(app);
        long seen = sp.getLong(seenKey(me, gid), -1);
        if (seen < 0) return; // first run for this group: the baseline is set by the caller
        if (p.at <= seen) return;
        sp.edit().putLong(seenKey(me, gid), p.at).apply();
        if (p.uid.equals(me)) return;
        boolean emergency = GroupPostRules.EMERGENCY.equals(p.type);
        int kind = emergency || GroupPostRules.COMMUNITY.equals(p.type) ? NotifyRules.COMMUNITY : NotifyRules.CHAT;
        if (!NotifyPrefs.allow(app, kind, emergency, false)) return;
        String who = p.name.isEmpty() ? app.getString(R.string.chat_someone) : p.name;
        String body;
        switch (p.type) {
            case GroupPostRules.PHOTO: body = app.getString(R.string.group_notify_photo, who); break;
            case GroupPostRules.TRIP: body = app.getString(R.string.group_notify_trip, who, GroupPostRules.preview(p.title, "", 80)); break;
            case GroupPostRules.PARTY: body = app.getString(R.string.group_notify_party, who, GroupPostRules.preview(p.title, "", 80)); break;
            case GroupPostRules.HELP: body = app.getString(R.string.group_notify_help, who, GroupPostRules.preview(p.title, "", 80)); break;
            case GroupPostRules.EMERGENCY: body = app.getString(R.string.group_notify_emergency, who); break;
            case GroupPostRules.COMMUNITY: body = app.getString(R.string.group_notify_community, who, GroupPostRules.preview(p.title, "", 80)); break;
            default: body = who + ": " + GroupPostRules.preview("", p.text, 120);
        }
        NotificationHelper.notifyIntent(app, emergency ? NotificationHelper.CH_ALERTS : NotificationHelper.CH_COMMUNITY,
                notificationId(gid), groupName, body,
                new Intent(app, GroupFeedActivity.class).putExtra(GroupFeedActivity.EXTRA_GROUP_ID, gid));
    }

    /** Live listener while the app or the live-alerts service runs: new posts in each of my groups. */
    public static ChatRepository.Watch listenForNotifications(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final ChatRepository.Watch w = new ChatRepository.Watch();
        if (offline(app)) return w;
        final String me = Session.uid(app);
        try {
            final DatabaseReference mineRef = root().child("userGroups").child(me);
            final Map<String, ChildEventListener> listeners = new HashMap<>();
            final Map<String, Query> queries = new HashMap<>();
            final ValueEventListener ml = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot s) {
                    List<String> now = new ArrayList<>();
                    for (DataSnapshot g : s.getChildren()) {
                        final String gid = g.getKey();
                        if (gid == null) continue;
                        now.add(gid);
                        if (listeners.containsKey(gid)) continue;
                        final String name = str(g, "name");
                        SharedPreferences p = sp(app);
                        if (!p.contains(seenKey(me, gid))) { // a group seen for the first time: start from now
                            p.edit().putLong(seenKey(me, gid), System.currentTimeMillis()).apply();
                        }
                        Query q = root().child("groupPosts").child(gid).orderByChild("at").limitToLast(20);
                        ChildEventListener cl = new ChildEventListener() {
                            @Override
                            public void onChildAdded(@NonNull DataSnapshot c, String prev) {
                                GroupPost post = parse(gid, c);
                                if (GroupPostRules.isKnownType(post.type) && post.at > 0) announce(app, me, gid, name, post);
                            }

                            @Override
                            public void onChildChanged(@NonNull DataSnapshot c, String prev) {
                            }

                            @Override
                            public void onChildRemoved(@NonNull DataSnapshot c) {
                            }

                            @Override
                            public void onChildMoved(@NonNull DataSnapshot c, String prev) {
                            }

                            @Override
                            public void onCancelled(@NonNull DatabaseError e) {
                                Log.w(TAG, "group notify cancelled: " + e.getMessage());
                            }
                        };
                        listeners.put(gid, cl);
                        queries.put(gid, q);
                        q.addChildEventListener(cl);
                    }
                    for (String gid : new ArrayList<>(listeners.keySet())) {
                        if (!now.contains(gid)) queries.remove(gid).removeEventListener(listeners.remove(gid));
                    }
                }

                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    Log.w(TAG, "group list cancelled: " + e.getMessage());
                }
            };
            mineRef.addValueEventListener(ml);
            w.add(() -> {
                mineRef.removeEventListener(ml);
                for (Map.Entry<String, ChildEventListener> en : listeners.entrySet()) queries.get(en.getKey()).removeEventListener(en.getValue());
                listeners.clear();
                queries.clear();
            });
        } catch (Exception e) {
            Log.w(TAG, "group feed listener failed", e);
        }
        return w;
    }

    /** Blocking read for the 15-minute check (worker thread only). */
    public static void checkBlocking(Context ctx) throws Exception {
        final Context app = ctx.getApplicationContext();
        if (offline(app)) return;
        String me = Session.uid(app);
        DataSnapshot mine = com.google.android.gms.tasks.Tasks.await(root().child("userGroups").child(me).get(), 40, TimeUnit.SECONDS);
        for (DataSnapshot g : mine.getChildren()) {
            String gid = g.getKey();
            if (gid == null) continue;
            SharedPreferences p = sp(app);
            if (!p.contains(seenKey(me, gid))) {
                p.edit().putLong(seenKey(me, gid), System.currentTimeMillis()).apply();
                continue;
            }
            DataSnapshot posts = com.google.android.gms.tasks.Tasks.await(
                    root().child("groupPosts").child(gid).orderByChild("at").limitToLast(20).get(), 40, TimeUnit.SECONDS);
            List<GroupPost> list = new ArrayList<>();
            for (DataSnapshot c : posts.getChildren()) list.add(parse(gid, c));
            Collections.sort(list, (a, b) -> Long.compare(a.at, b.at));
            for (GroupPost post : list) if (GroupPostRules.isKnownType(post.type)) announce(app, me, gid, str(g, "name"), post);
        }
    }
}
