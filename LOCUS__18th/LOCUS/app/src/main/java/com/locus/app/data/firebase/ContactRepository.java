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
import com.locus.app.model.CommunityRequest;
import com.locus.app.model.Contact;
import com.locus.app.model.ContactRequest;
import com.locus.app.model.ContactRules;
import com.locus.app.model.NotifyRules;
import com.locus.app.ui.ContactsActivity;
import com.locus.app.util.NotificationHelper;
import com.locus.app.util.NotifyPrefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Contact requests and saved contacts (Realtime Database).
 *
 * To message the person behind a Community post, a helper first sends a contact request. When the post's owner
 * accepts, both are saved in each other's contacts and can message each other (about the post, or directly any
 * time). Paths: contactRequests/{to}/{from} and contacts/{me}/{other}.
 */
public final class ContactRepository {
    private static final String TAG = "ContactRepository";
    private static final String PREFS = "locus_contacts";
    private static final String KEY_BASELINE = "_baseline_";

    private ContactRepository() {
    }

    public interface Done {
        /** @param error null on success, otherwise a short code: cloud, rules, offline, other:... */
        void done(@Nullable String error);
    }

    public interface StateCallback {
        /** @param state a {@link ContactRules} state; error is set (and state NONE) when it could not be read. */
        void onState(int state, @Nullable String error);
    }

    public interface Listener {
        void onChanged(List<ContactRequest> incoming, List<Contact> contacts);
    }

    private static DatabaseReference requests(String to) {
        return Rtdb.get().getReference("contactRequests").child(to);
    }

    private static DatabaseReference contacts(String uid) {
        return Rtdb.get().getReference("contacts").child(uid);
    }

    private static String classify(@Nullable Exception e) {
        String m = e == null || e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (m.contains("permission")) return "rules";
        if (m.contains("network") || m.contains("offline") || m.contains("disconnected")) return "offline";
        return "other:" + (e == null ? "unknown" : e.getMessage());
    }

    // ---- state with one person --------------------------------------------------------------------

    /** Is this person my contact, is my request waiting, or was it declined? Read from the server. */
    public static void stateWith(Context ctx, final String otherUid, final StateCallback cb) {
        final Context app = ctx.getApplicationContext();
        if (!Session.canSyncOnline(app) || Rtdb.url() == null) {
            cb.onState(ContactRules.NONE, "cloud");
            return;
        }
        final String me = Session.uid(app);
        try {
            contacts(me).child(otherUid).get().addOnCompleteListener(c -> {
                if (!c.isSuccessful()) {
                    cb.onState(ContactRules.NONE, classify(c.getException()));
                    return;
                }
                if (c.getResult().exists()) {
                    cb.onState(ContactRules.CONTACT, null);
                    return;
                }
                requests(otherUid).child(me).get().addOnCompleteListener(r -> {
                    if (!r.isSuccessful()) {
                        cb.onState(ContactRules.NONE, classify(r.getException()));
                        return;
                    }
                    DataSnapshot s = r.getResult();
                    String status = s.child("status").getValue(String.class);
                    Long at = s.child("at").getValue(Long.class);
                    cb.onState(ContactRules.state(false, status, at == null ? 0 : at, System.currentTimeMillis()), null);
                });
            });
        } catch (Exception e) {
            cb.onState(ContactRules.NONE, "other:" + e.getMessage());
        }
    }

    // ---- send / answer ----------------------------------------------------------------------------

    /** Asks the owner of a post to be contacts, with an optional short note. */
    public static void sendRequest(Context ctx, CommunityRequest post, String note, final Done cb) {
        final Context app = ctx.getApplicationContext();
        if (!Session.canSyncOnline(app) || Rtdb.url() == null) {
            cb.done("cloud");
            return;
        }
        final String me = Session.uid(app);
        Map<String, Object> m = new HashMap<>();
        m.put("fromUid", me);
        m.put("fromName", clip(Session.displayName(app), 60));
        m.put("postId", post.id);
        m.put("postTitle", clip(post.title, 200));
        m.put("note", ContactRules.note(note));
        m.put("at", ServerValue.TIMESTAMP);
        m.put("status", ContactRules.STATUS_PENDING);
        try {
            requests(post.ownerUid).child(me).setValue(m).addOnCompleteListener(t ->
                    cb.done(t.isSuccessful() ? null : classify(t.getException())));
        } catch (Exception e) {
            cb.done("other:" + e.getMessage());
        }
    }

    /** Takes my own request back. */
    public static void cancelRequest(Context ctx, String ownerUid, final Done cb) {
        try {
            requests(ownerUid).child(Session.uid(ctx)).removeValue().addOnCompleteListener(t ->
                    cb.done(t.isSuccessful() ? null : classify(t.getException())));
        } catch (Exception e) {
            cb.done("other:" + e.getMessage());
        }
    }

    /**
     * Accept: ONE atomic write saves both people in each other's contacts and removes the request, so nobody is
     * ever left half-connected.
     */
    public static void accept(Context ctx, ContactRequest r, final Done cb) {
        final Context app = ctx.getApplicationContext();
        final String me = Session.uid(app);
        Map<String, Object> mine = new HashMap<>();
        mine.put("name", clip(r.fromName, 60));
        mine.put("since", ServerValue.TIMESTAMP);
        mine.put("postTitle", clip(r.postTitle, 200));
        mine.put("by", me);
        Map<String, Object> theirs = new HashMap<>();
        theirs.put("name", clip(Session.displayName(app), 60));
        theirs.put("since", ServerValue.TIMESTAMP);
        theirs.put("postTitle", clip(r.postTitle, 200));
        theirs.put("by", me);
        Map<String, Object> up = new HashMap<>();
        up.put("contacts/" + me + "/" + r.fromUid, mine);
        up.put("contacts/" + r.fromUid + "/" + me, theirs);
        up.put("contactRequests/" + me + "/" + r.fromUid, null);
        try {
            Rtdb.get().getReference().updateChildren(up).addOnCompleteListener(t ->
                    cb.done(t.isSuccessful() ? null : classify(t.getException())));
        } catch (Exception e) {
            cb.done("other:" + e.getMessage());
        }
    }

    /** Decline keeps a small "declined" mark so the sender cannot ask again for a day. */
    public static void decline(Context ctx, ContactRequest r, final Done cb) {
        try {
            Map<String, Object> up = new HashMap<>();
            up.put("status", ContactRules.STATUS_DECLINED);
            up.put("at", ServerValue.TIMESTAMP);
            requests(Session.uid(ctx)).child(r.fromUid).updateChildren(up).addOnCompleteListener(t ->
                    cb.done(t.isSuccessful() ? null : classify(t.getException())));
        } catch (Exception e) {
            cb.done("other:" + e.getMessage());
        }
    }

    /** Removes the contact on both accounts (the chats stay readable). */
    public static void remove(Context ctx, Contact c, final Done cb) {
        final String me = Session.uid(ctx);
        Map<String, Object> up = new HashMap<>();
        up.put("contacts/" + me + "/" + c.uid, null);
        up.put("contacts/" + c.uid + "/" + me, null);
        try {
            Rtdb.get().getReference().updateChildren(up).addOnCompleteListener(t ->
                    cb.done(t.isSuccessful() ? null : classify(t.getException())));
        } catch (Exception e) {
            cb.done("other:" + e.getMessage());
        }
    }

    // ---- live lists (the Contacts screen) -------------------------------------------------------------

    private static List<ContactRequest> parseRequests(DataSnapshot all) {
        List<ContactRequest> list = new ArrayList<>();
        for (DataSnapshot s : all.getChildren()) {
            ContactRequest r = new ContactRequest();
            r.fromUid = s.getKey() == null ? "" : s.getKey();
            r.fromName = str(s, "fromName");
            r.postId = str(s, "postId");
            r.postTitle = str(s, "postTitle");
            r.note = str(s, "note");
            Long at = s.child("at").getValue(Long.class);
            r.at = at == null ? 0 : at;
            r.status = str(s, "status");
            if (ContactRules.needsAnswer(r.status)) list.add(r);
        }
        Collections.sort(list, (a, b) -> Long.compare(b.at, a.at));
        return list;
    }

    private static List<Contact> parseContacts(DataSnapshot all) {
        List<Contact> list = new ArrayList<>();
        for (DataSnapshot s : all.getChildren()) {
            Contact c = new Contact();
            c.uid = s.getKey() == null ? "" : s.getKey();
            c.name = str(s, "name");
            c.postTitle = str(s, "postTitle");
            Long since = s.child("since").getValue(Long.class);
            c.since = since == null ? 0 : since;
            list.add(c);
        }
        Collections.sort(list, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return list;
    }

    private static String str(DataSnapshot s, String key) {
        String v = s.child(key).getValue(String.class);
        return v == null ? "" : v;
    }

    /** Live requests and contacts for the Contacts screen. */
    public static ChatRepository.Watch watch(Context ctx, final Listener l) {
        final ChatRepository.Watch w = new ChatRepository.Watch();
        if (!Session.canSyncOnline(ctx) || Rtdb.url() == null) return w;
        final String me = Session.uid(ctx);
        final List<ContactRequest>[] in = new List[]{new ArrayList<ContactRequest>()};
        final List<Contact>[] cs = new List[]{new ArrayList<Contact>()};
        try {
            final DatabaseReference rr = requests(me);
            final DatabaseReference cr = contacts(me);
            rr.keepSynced(true);
            cr.keepSynced(true);
            final ValueEventListener rl = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot s) {
                    in[0] = parseRequests(s);
                    l.onChanged(in[0], cs[0]);
                }

                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    Log.w(TAG, "requests cancelled: " + e.getMessage());
                }
            };
            final ValueEventListener cl = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot s) {
                    cs[0] = parseContacts(s);
                    l.onChanged(in[0], cs[0]);
                }

                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    Log.w(TAG, "contacts cancelled: " + e.getMessage());
                }
            };
            rr.addValueEventListener(rl);
            cr.addValueEventListener(cl);
            w.add(() -> rr.removeEventListener(rl));
            w.add(() -> cr.removeEventListener(cl));
        } catch (Exception e) {
            Log.w(TAG, "watch failed", e);
        }
        return w;
    }

    // ---- notifications ---------------------------------------------------------------------------

    /** Requests waiting for my answer, as last read on this phone (shown on the Messages card). */
    public static int pendingCount(Context ctx) {
        String me = Session.uid(ctx);
        if (me == null || me.isEmpty()) return 0;
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("pending|" + me, 0);
    }

    public static final String PREFS_NAME = PREFS;

    /**
     * Announces new requests ("X wants to message you") and accepted requests ("X accepted"), once each, also when
     * they arrived while the app was closed. Used by the live listener and the 15-minute background check.
     */
    static synchronized void announceNew(Context app, String me, List<ContactRequest> incoming, List<Contact> contactList,
                                         Map<String, String> byWhom) {
        SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean baselined = sp.getBoolean(KEY_BASELINE + me, false);
        SharedPreferences.Editor ed = sp.edit();
        for (ContactRequest r : incoming) {
            String key = "r|" + me + "|" + r.fromUid + "|" + r.at;
            if (!sp.getBoolean(key, false)) {
                if (baselined && NotifyPrefs.allow(app, NotifyRules.CHAT)) {
                    NotificationHelper.notifyIntent(app, NotificationHelper.CH_COMMUNITY, idOf(key),
                            app.getString(R.string.contact_request_title, r.fromName),
                            r.postTitle.isEmpty() ? app.getString(R.string.contact_request_text)
                                    : app.getString(R.string.contact_request_about, r.postTitle),
                            new Intent(app, ContactsActivity.class));
                }
                ed.putBoolean(key, true);
            }
        }
        for (Contact c : contactList) {
            String key = "c|" + me + "|" + c.uid + "|" + c.since;
            if (!sp.getBoolean(key, false)) {
                String by = byWhom.get(c.uid);
                if (baselined && by != null && !by.equals(me) && NotifyPrefs.allow(app, NotifyRules.CHAT)) {
                    NotificationHelper.notifyIntent(app, NotificationHelper.CH_COMMUNITY, idOf(key),
                            app.getString(R.string.contact_accepted_title, c.name),
                            app.getString(R.string.contact_accepted_text),
                            new Intent(app, ContactsActivity.class));
                }
                ed.putBoolean(key, true);
            }
        }
        ed.putInt("pending|" + me, incoming.size());
        ed.putBoolean(KEY_BASELINE + me, true);
        ed.apply();
    }

    private static int idOf(String key) {
        return 71000 + (key.hashCode() & 0xFFFF);
    }

    /** Live listener for notifications while the app (or the live-alerts service) is running. */
    public static ChatRepository.Watch listenForNotifications(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final ChatRepository.Watch w = new ChatRepository.Watch();
        if (!Session.canSyncOnline(app) || Rtdb.url() == null) return w;
        final String me = Session.uid(app);
        final List<ContactRequest>[] in = new List[]{null};
        final List<Contact>[] cs = new List[]{null};
        final Map<String, String> by = new HashMap<>();
        try {
            final DatabaseReference rr = requests(me);
            final DatabaseReference cr = contacts(me);
            rr.keepSynced(true);
            cr.keepSynced(true);
            final ValueEventListener rl = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot s) {
                    in[0] = parseRequests(s);
                    if (cs[0] != null) announceNew(app, me, in[0], cs[0], by);
                }

                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    Log.w(TAG, "notify requests cancelled: " + e.getMessage());
                }
            };
            final ValueEventListener cl = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot s) {
                    cs[0] = parseContacts(s);
                    by.clear();
                    for (DataSnapshot c : s.getChildren()) {
                        String b = c.child("by").getValue(String.class);
                        if (c.getKey() != null && b != null) by.put(c.getKey(), b);
                    }
                    if (in[0] != null) announceNew(app, me, in[0], cs[0], by);
                }

                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    Log.w(TAG, "notify contacts cancelled: " + e.getMessage());
                }
            };
            rr.addValueEventListener(rl);
            cr.addValueEventListener(cl);
            w.add(() -> rr.removeEventListener(rl));
            w.add(() -> cr.removeEventListener(cl));
        } catch (Exception e) {
            Log.w(TAG, "contact listener failed", e);
        }
        return w;
    }

    /** Blocking read for the 15-minute check (worker thread only). */
    public static void checkBlocking(Context ctx) throws Exception {
        final Context app = ctx.getApplicationContext();
        if (!Session.canSyncOnline(app) || Rtdb.url() == null) return;
        String me = Session.uid(app);
        DataSnapshot rs = com.google.android.gms.tasks.Tasks.await(requests(me).get(), 40, TimeUnit.SECONDS);
        DataSnapshot cs = com.google.android.gms.tasks.Tasks.await(contacts(me).get(), 40, TimeUnit.SECONDS);
        Map<String, String> by = new HashMap<>();
        for (DataSnapshot c : cs.getChildren()) {
            String b = c.child("by").getValue(String.class);
            if (c.getKey() != null && b != null) by.put(c.getKey(), b);
        }
        announceNew(app, me, parseRequests(rs), parseContacts(cs), by);
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
