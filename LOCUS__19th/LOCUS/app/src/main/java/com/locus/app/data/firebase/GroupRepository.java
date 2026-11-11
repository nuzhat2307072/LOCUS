package com.locus.app.data.firebase;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.locus.app.R;
import com.locus.app.model.FamilyGroup;
import com.locus.app.model.GroupRules;
import com.locus.app.model.NotifyRules;
import com.locus.app.ui.GroupsActivity;
import com.locus.app.util.NotificationHelper;
import com.locus.app.util.NotifyPrefs;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Family and friends groups (Realtime Database).
 *
 * Paths: groups/{gid} (name, admin, code, members), groupCodes/{CODE} (finds a group by its code),
 * groupRequests/{gid}/{uid} (people waiting for the admin), userGroups/{uid}/{gid} (my list of groups).
 * Whoever starts a group is its admin; others join with the code and wait for the admin's approval.
 */
public final class GroupRepository {
    private static final String TAG = "GroupRepository";
    public static final String PREFS_NAME = "locus_groups";
    private static final SecureRandom RANDOM = new SecureRandom();

    private GroupRepository() {
    }

    public interface Done {
        /** error: null on success, otherwise cloud, rules, offline, nocode, own, already, full, other:... */
        void done(@Nullable String error, @Nullable String extra);
    }

    public interface ListListener {
        void onChanged(List<FamilyGroup> groups);
    }

    public interface DetailListener {
        /** group is null when it was deleted or I am no longer a member. */
        void onChanged(@Nullable FamilyGroup group);
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

    // ---- create ---------------------------------------------------------------------------------

    /** Starts a group: I am its admin. The result's extra is the group's join code. */
    public static void create(Context ctx, String rawName, final Done cb) {
        final Context app = ctx.getApplicationContext();
        final String name = GroupRules.cleanName(rawName);
        if (name.isEmpty()) {
            cb.done("noname", null);
            return;
        }
        if (offline(app)) {
            cb.done(Session.canSyncOnline(app) ? "nodb" : "cloud", null);
            return;
        }
        final String me = Session.uid(app);
        final String myName = Session.displayName(app);
        try {
            final String gid = root().child("groups").push().getKey();
            Map<String, Object> admin = new HashMap<>();
            admin.put("name", myName);
            admin.put("role", GroupRules.ADMIN);
            admin.put("since", ServerValue.TIMESTAMP);
            Map<String, Object> members = new HashMap<>();
            members.put(me, admin);
            Map<String, Object> g = new HashMap<>();
            g.put("name", name);
            g.put("adminUid", me);
            g.put("adminName", myName);
            g.put("createdAt", ServerValue.TIMESTAMP);
            g.put("members", members);
            root().child("groups").child(gid).setValue(g).addOnCompleteListener(t -> {
                if (!t.isSuccessful()) {
                    cb.done(classify(t.getException()), null);
                    return;
                }
                claimCode(name, gid, me, myName, 0, (error, code) -> {
                    if (error != null) {
                        root().child("groups").child(gid).removeValue(); // no half-made group is left behind
                        cb.done(error, null);
                        return;
                    }
                    Map<String, Object> mine = new HashMap<>();
                    mine.put("name", name);
                    mine.put("role", GroupRules.ADMIN);
                    mine.put("adminName", myName);
                    mine.put("since", ServerValue.TIMESTAMP);
                    Map<String, Object> up = new HashMap<>();
                    up.put("groups/" + gid + "/code", code);
                    up.put("userGroups/" + me + "/" + gid, mine);
                    root().updateChildren(up).addOnCompleteListener(u ->
                            cb.done(u.isSuccessful() ? null : classify(u.getException()), code));
                });
            });
        } catch (Exception e) {
            cb.done("other:" + e.getMessage(), null);
        }
    }

    /** Writes groupCodes/{code}; a code already taken is simply replaced by another random one (up to 8 tries). */
    private static void claimCode(final String name, final String gid, final String me, final String myName,
                                  final int attempt, final Done cb) {
        final String code = GroupRules.newCode(RANDOM);
        Map<String, Object> m = new HashMap<>();
        m.put("gid", gid);
        m.put("name", name);
        m.put("adminUid", me);
        m.put("adminName", myName);
        try {
            root().child("groupCodes").child(code).setValue(m).addOnCompleteListener(t -> {
                if (t.isSuccessful()) {
                    cb.done(null, code);
                } else if (attempt < 8 && String.valueOf(t.getException() == null ? "" : t.getException().getMessage())
                        .toLowerCase().contains("permission")) {
                    claimCode(name, gid, me, myName, attempt + 1, cb); // taken (the rule refuses to overwrite)
                } else {
                    cb.done(classify(t.getException()), null);
                }
            });
        } catch (Exception e) {
            cb.done("other:" + e.getMessage(), null);
        }
    }

    /** Admin: a new code (the old one stops working). */
    public static void newCode(Context ctx, final FamilyGroup g, final Done cb) {
        final Context app = ctx.getApplicationContext();
        final String me = Session.uid(app);
        claimCode(g.name, g.id, me, Session.displayName(app), 0, (error, code) -> {
            if (error != null) {
                cb.done(error, null);
                return;
            }
            Map<String, Object> up = new HashMap<>();
            up.put("groups/" + g.id + "/code", code);
            if (!g.code.isEmpty()) up.put("groupCodes/" + g.code, null);
            root().updateChildren(up).addOnCompleteListener(u ->
                    cb.done(u.isSuccessful() ? null : classify(u.getException()), code));
        });
    }

    // ---- join ----------------------------------------------------------------------------------

    /** Sends a join request for the code; the extra is the group's name. The admin has to approve. */
    public static void requestJoin(Context ctx, String rawCode, final Done cb) {
        final Context app = ctx.getApplicationContext();
        final String code = GroupRules.normalizeCode(rawCode);
        if (!GroupRules.isValidCode(code)) {
            cb.done("badcode", null);
            return;
        }
        if (offline(app)) {
            cb.done(Session.canSyncOnline(app) ? "nodb" : "cloud", null);
            return;
        }
        final String me = Session.uid(app);
        try {
            root().child("groupCodes").child(code).get().addOnCompleteListener(t -> {
                if (!t.isSuccessful()) {
                    cb.done(classify(t.getException()), null);
                    return;
                }
                DataSnapshot s = t.getResult();
                if (!s.exists()) {
                    cb.done("nocode", null);
                    return;
                }
                final String gid = str(s, "gid");
                final String gname = str(s, "name");
                if (me.equals(str(s, "adminUid"))) {
                    cb.done("own", gname);
                    return;
                }
                root().child("userGroups").child(me).child(gid).get().addOnCompleteListener(u -> {
                    if (u.isSuccessful() && u.getResult().exists()) {
                        cb.done("already", gname);
                        return;
                    }
                    Map<String, Object> r = new HashMap<>();
                    r.put("name", Session.displayName(app));
                    r.put("at", ServerValue.TIMESTAMP);
                    r.put("status", "pending");
                    r.put("groupName", gname);
                    root().child("groupRequests").child(gid).child(me).setValue(r).addOnCompleteListener(w ->
                            cb.done(w.isSuccessful() ? null : classify(w.getException()), gname));
                });
            });
        } catch (Exception e) {
            cb.done("other:" + e.getMessage(), null);
        }
    }

    // ---- admin actions ---------------------------------------------------------------------------

    /** Approve: one atomic write adds the person to the group and to their own list, and clears the request. */
    public static void approve(Context ctx, FamilyGroup g, FamilyGroup.JoinRequest r, final Done cb) {
        final Context app = ctx.getApplicationContext();
        if (!GroupRules.hasRoom(g.members.size())) {
            cb.done("full", null);
            return;
        }
        Map<String, Object> member = new HashMap<>();
        member.put("name", r.name);
        member.put("role", GroupRules.MEMBER);
        member.put("since", ServerValue.TIMESTAMP);
        Map<String, Object> theirs = new HashMap<>();
        theirs.put("name", g.name);
        theirs.put("role", GroupRules.MEMBER);
        theirs.put("adminName", Session.displayName(app));
        theirs.put("since", ServerValue.TIMESTAMP);
        Map<String, Object> up = new HashMap<>();
        up.put("groups/" + g.id + "/members/" + r.uid, member);
        up.put("userGroups/" + r.uid + "/" + g.id, theirs);
        up.put("groupRequests/" + g.id + "/" + r.uid, null);
        write(up, cb);
    }

    public static void decline(Context ctx, FamilyGroup g, FamilyGroup.JoinRequest r, Done cb) {
        Map<String, Object> up = new HashMap<>();
        up.put("groupRequests/" + g.id + "/" + r.uid, null);
        write(up, cb);
    }

    /** Admin removes a member. */
    public static void removeMember(Context ctx, FamilyGroup g, FamilyGroup.Member m, Done cb) {
        Map<String, Object> up = new HashMap<>();
        up.put("groups/" + g.id + "/members/" + m.uid, null);
        up.put("userGroups/" + m.uid + "/" + g.id, null);
        write(up, cb);
    }

    /** A member leaves (the admin cannot: they delete the group instead). */
    public static void leave(Context ctx, FamilyGroup g, Done cb) {
        String me = Session.uid(ctx);
        Map<String, Object> up = new HashMap<>();
        up.put("groups/" + g.id + "/members/" + me, null);
        up.put("userGroups/" + me + "/" + g.id, null);
        write(up, cb);
    }

    /** Admin deletes the whole group: members' lists, the code and any waiting requests go with it. */
    public static void deleteGroup(Context ctx, FamilyGroup g, Done cb) {
        Map<String, Object> up = new HashMap<>();
        up.put("groups/" + g.id, null);
        up.put("groupRequests/" + g.id, null);
        if (!g.code.isEmpty()) up.put("groupCodes/" + g.code, null);
        for (FamilyGroup.Member m : g.members) up.put("userGroups/" + m.uid + "/" + g.id, null);
        write(up, cb);
    }

    private static void write(Map<String, Object> updates, final Done cb) {
        try {
            root().updateChildren(updates).addOnCompleteListener(t ->
                    cb.done(t.isSuccessful() ? null : classify(t.getException()), null));
        } catch (Exception e) {
            cb.done("other:" + e.getMessage(), null);
        }
    }

    // ---- live data ---------------------------------------------------------------------------------

    /** My groups (the list screen). */
    public static ChatRepository.Watch watchMine(Context ctx, final ListListener l) {
        final ChatRepository.Watch w = new ChatRepository.Watch();
        if (offline(ctx)) return w;
        try {
            final DatabaseReference ref = root().child("userGroups").child(Session.uid(ctx));
            ref.keepSynced(true);
            final ValueEventListener vl = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot s) {
                    l.onChanged(parseMine(s));
                }

                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    Log.w(TAG, "groups cancelled: " + e.getMessage());
                }
            };
            ref.addValueEventListener(vl);
            w.add(() -> ref.removeEventListener(vl));
        } catch (Exception e) {
            Log.w(TAG, "watchMine failed", e);
        }
        return w;
    }

    private static List<FamilyGroup> parseMine(DataSnapshot all) {
        List<FamilyGroup> list = new ArrayList<>();
        for (DataSnapshot s : all.getChildren()) {
            FamilyGroup g = new FamilyGroup();
            g.id = s.getKey() == null ? "" : s.getKey();
            g.name = str(s, "name");
            g.role = str(s, "role");
            g.adminName = str(s, "adminName");
            g.createdAt = num(s, "since");
            list.add(g);
        }
        Collections.sort(list, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return list;
    }

    /** One group: details, members, and (for the admin) the people waiting for approval. */
    public static ChatRepository.Watch watchGroup(final Context ctx, final String gid, final DetailListener l) {
        final ChatRepository.Watch w = new ChatRepository.Watch();
        if (offline(ctx)) return w;
        final String me = Session.uid(ctx);
        try {
            final DatabaseReference gref = root().child("groups").child(gid);
            final DatabaseReference rref = root().child("groupRequests").child(gid);
            final FamilyGroup[] cur = new FamilyGroup[1];
            final List<FamilyGroup.JoinRequest> reqs = new ArrayList<>();
            final ValueEventListener rl = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot s) {
                    reqs.clear();
                    reqs.addAll(parseRequests(gid, cur[0] == null ? "" : cur[0].name, s));
                    if (cur[0] != null) {
                        cur[0].requests.clear();
                        cur[0].requests.addAll(reqs);
                        l.onChanged(cur[0]);
                    }
                }

                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    Log.w(TAG, "requests cancelled: " + e.getMessage());
                }
            };
            final boolean[] listeningRequests = {false};
            final ValueEventListener gl = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot s) {
                    if (!s.exists()) {
                        cur[0] = null;
                        l.onChanged(null);
                        return;
                    }
                    FamilyGroup g = new FamilyGroup();
                    g.id = gid;
                    g.name = str(s, "name");
                    g.adminUid = str(s, "adminUid");
                    g.adminName = str(s, "adminName");
                    g.code = str(s, "code");
                    g.createdAt = num(s, "createdAt");
                    g.role = me.equals(g.adminUid) ? GroupRules.ADMIN : GroupRules.MEMBER;
                    for (DataSnapshot m : s.child("members").getChildren()) {
                        FamilyGroup.Member mem = new FamilyGroup.Member();
                        mem.uid = m.getKey() == null ? "" : m.getKey();
                        mem.name = str(m, "name");
                        mem.role = str(m, "role");
                        mem.since = num(m, "since");
                        g.members.add(mem);
                    }
                    Collections.sort(g.members, (a, b) -> {
                        if (a.role.equals(b.role)) return a.name.compareToIgnoreCase(b.name);
                        return GroupRules.isAdmin(a.role) ? -1 : 1;
                    });
                    if (g.isAdmin()) {
                        g.requests.addAll(reqs);
                        if (!listeningRequests[0]) {
                            listeningRequests[0] = true;
                            rref.addValueEventListener(rl);
                        }
                    }
                    cur[0] = g;
                    l.onChanged(g);
                }

                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    Log.w(TAG, "group cancelled: " + e.getMessage());
                    l.onChanged(null); // not a member (any more)
                }
            };
            gref.addValueEventListener(gl);
            w.add(() -> gref.removeEventListener(gl));
            w.add(() -> {
                if (listeningRequests[0]) rref.removeEventListener(rl);
            });
        } catch (Exception e) {
            Log.w(TAG, "watchGroup failed", e);
        }
        return w;
    }

    private static List<FamilyGroup.JoinRequest> parseRequests(String gid, String gname, DataSnapshot all) {
        List<FamilyGroup.JoinRequest> list = new ArrayList<>();
        for (DataSnapshot s : all.getChildren()) {
            FamilyGroup.JoinRequest r = new FamilyGroup.JoinRequest();
            r.groupId = gid;
            r.groupName = gname.isEmpty() ? str(s, "groupName") : gname;
            r.uid = s.getKey() == null ? "" : s.getKey();
            r.name = str(s, "name");
            r.at = num(s, "at");
            list.add(r);
        }
        Collections.sort(list, (a, b) -> Long.compare(b.at, a.at));
        return list;
    }

    // ---- notifications -------------------------------------------------------------------------------

    /** Join requests waiting for me (as admin), as last read on this phone: shown on the dashboard card. */
    public static int pendingCount(Context ctx) {
        String me = Session.uid(ctx);
        if (me == null || me.isEmpty()) return 0;
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt("pending|" + me, 0);
    }

    /**
     * Announces new join requests to the admin and "you were approved" to the person, once each (also when they
     * arrived while the app was closed). The first call only records what exists, without notifying.
     */
    static synchronized void announce(Context app, String me, Map<String, List<FamilyGroup.JoinRequest>> requestsByGroup,
                                      List<FamilyGroup> mine) {
        SharedPreferences sp = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean baselined = sp.getBoolean("baseline|" + me, false);
        SharedPreferences.Editor ed = sp.edit();
        int pending = 0;
        for (Map.Entry<String, List<FamilyGroup.JoinRequest>> e : requestsByGroup.entrySet()) {
            for (FamilyGroup.JoinRequest r : e.getValue()) {
                pending++;
                String key = "r|" + me + "|" + r.groupId + "|" + r.uid + "|" + r.at;
                if (sp.getBoolean(key, false)) continue;
                if (baselined && NotifyPrefs.allow(app, NotifyRules.COMMUNITY)) {
                    NotificationHelper.notifyIntent(app, NotificationHelper.CH_COMMUNITY, 72000 + (key.hashCode() & 0xFFFF),
                            app.getString(R.string.group_request_title, r.name, r.groupName),
                            app.getString(R.string.group_request_text),
                            new Intent(app, GroupsActivity.class));
                }
                ed.putBoolean(key, true);
            }
        }
        for (FamilyGroup g : mine) {
            String key = "j|" + me + "|" + g.id + "|" + g.createdAt;
            if (sp.getBoolean(key, false)) continue;
            if (baselined && !g.isAdmin() && NotifyPrefs.allow(app, NotifyRules.COMMUNITY)) {
                NotificationHelper.notifyIntent(app, NotificationHelper.CH_COMMUNITY, 72000 + (key.hashCode() & 0xFFFF),
                        app.getString(R.string.group_joined_title, g.name),
                        app.getString(R.string.group_joined_text),
                        new Intent(app, GroupsActivity.class));
            }
            ed.putBoolean(key, true);
        }
        ed.putInt("pending|" + me, pending);
        ed.putBoolean("baseline|" + me, true);
        ed.apply();
    }

    /** Live listener (the app or the live-alerts service is running): requests for my groups and approvals. */
    public static ChatRepository.Watch listenForNotifications(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final ChatRepository.Watch w = new ChatRepository.Watch();
        if (offline(app)) return w;
        final String me = Session.uid(app);
        try {
            final DatabaseReference mineRef = root().child("userGroups").child(me);
            mineRef.keepSynced(true);
            final Map<String, List<FamilyGroup.JoinRequest>> byGroup = new HashMap<>();
            final Map<String, ValueEventListener> reqListeners = new HashMap<>();
            final List<FamilyGroup>[] mine = new List[]{new ArrayList<FamilyGroup>()};
            final ValueEventListener ml = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot s) {
                    mine[0] = parseMine(s);
                    Set<String> adminOf = new HashSet<>();
                    for (final FamilyGroup g : mine[0]) {
                        if (!g.isAdmin()) continue;
                        adminOf.add(g.id);
                        if (reqListeners.containsKey(g.id)) continue;
                        final DatabaseReference rr = root().child("groupRequests").child(g.id);
                        ValueEventListener rl = new ValueEventListener() {
                            @Override
                            public void onDataChange(@NonNull DataSnapshot r) {
                                byGroup.put(g.id, parseRequests(g.id, g.name, r));
                                announce(app, me, byGroup, mine[0]);
                            }

                            @Override
                            public void onCancelled(@NonNull DatabaseError e) {
                                Log.w(TAG, "notify requests cancelled: " + e.getMessage());
                            }
                        };
                        reqListeners.put(g.id, rl);
                        rr.addValueEventListener(rl);
                    }
                    // groups I no longer administer: stop listening
                    for (String id : new ArrayList<>(reqListeners.keySet())) {
                        if (!adminOf.contains(id)) {
                            root().child("groupRequests").child(id).removeEventListener(reqListeners.remove(id));
                            byGroup.remove(id);
                        }
                    }
                    announce(app, me, byGroup, mine[0]);
                }

                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    Log.w(TAG, "notify groups cancelled: " + e.getMessage());
                }
            };
            mineRef.addValueEventListener(ml);
            w.add(() -> {
                mineRef.removeEventListener(ml);
                for (Map.Entry<String, ValueEventListener> e : reqListeners.entrySet()) {
                    root().child("groupRequests").child(e.getKey()).removeEventListener(e.getValue());
                }
                reqListeners.clear();
            });
        } catch (Exception e) {
            Log.w(TAG, "group listener failed", e);
        }
        return w;
    }

    /** Blocking read for the 15-minute check (worker thread only). */
    public static void checkBlocking(Context ctx) throws Exception {
        final Context app = ctx.getApplicationContext();
        if (offline(app)) return;
        String me = Session.uid(app);
        DataSnapshot mineSnap = com.google.android.gms.tasks.Tasks.await(
                root().child("userGroups").child(me).get(), 40, TimeUnit.SECONDS);
        List<FamilyGroup> mine = parseMine(mineSnap);
        Map<String, List<FamilyGroup.JoinRequest>> byGroup = new HashMap<>();
        for (FamilyGroup g : mine) {
            if (!g.isAdmin()) continue;
            DataSnapshot r = com.google.android.gms.tasks.Tasks.await(
                    root().child("groupRequests").child(g.id).get(), 40, TimeUnit.SECONDS);
            byGroup.put(g.id, parseRequests(g.id, g.name, r));
        }
        announce(app, me, byGroup, mine);
    }
}
