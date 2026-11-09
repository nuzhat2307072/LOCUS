package com.locus.app.data.firebase;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.locus.app.R;
import com.locus.app.model.ChatMessage;
import com.locus.app.model.ChatRules;
import com.locus.app.model.ChatSummary;
import com.locus.app.model.CommunityRequest;
import com.locus.app.ui.ChatActivity;
import com.locus.app.util.NotificationHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Private one-to-one chat about a Community request, on the Realtime Database.
 *
 * <pre>
 * chats/{chatId}              requestId, requestTitle, requesterUid/Name, helperUid/Name, createdAt, closed,
 *                             members/{uid}=true, messages/{id}={from,text,at}, seen/{uid}=time, typing/{uid}=bool
 * userChats/{uid}/{chatId}    title, otherName, otherUid, requestId, last, lastFrom, lastAt, unread, closed   (the chats list)
 * </pre>
 * chatId = requestId + "_" + helperUid. Only the two members can read or write a chat (database.rules.json).
 */
public final class ChatRepository {
    private static final String TAG = "Chat";
    private static final long TIMEOUT_MS = 12000;

    /** The chat that is on screen right now: its new messages need no notification. */
    private static volatile String openChatId;

    private ChatRepository() {
    }

    public interface Opened {
        /** @param chatId null when it failed; error then says why. */
        void done(@Nullable String chatId, @Nullable String error);
    }

    public interface Done {
        void done(@Nullable String error);
    }

    public static void setOpenChat(@Nullable String chatId) {
        openChatId = chatId;
    }

    private static DatabaseReference chats() {
        return Rtdb.get().getReference("chats");
    }

    private static DatabaseReference userChats(String uid) {
        return Rtdb.get().getReference("userChats").child(uid);
    }

    // ---- start a conversation -----------------------------------------------------------------------------

    /** The helper taps "Message" on a request: open the chat with its owner, creating it the first time. */
    public static void open(Context ctx, final CommunityRequest r, final Opened cb) {
        final Context app = ctx.getApplicationContext();
        if (!Session.canSyncOnline(app)) {
            cb.done(null, "cloud");
            return;
        }
        if (Rtdb.url() == null) {
            cb.done(null, "nodb");
            return;
        }
        final String me = Session.uid(app);
        if (r.ownerUid == null || r.ownerUid.trim().isEmpty()) {
            cb.done(null, "noowner"); // an old post that has no owner id: nobody to write to
            return;
        }
        if (me.equals(r.ownerUid)) {
            cb.done(null, "own");
            return;
        }
        final String id = ChatRules.chatId(r.id, me);
        final AtomicBoolean answered = new AtomicBoolean(false);
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable timeout = () -> {
            if (answered.compareAndSet(false, true)) cb.done(null, "timeout");
        };
        h.postDelayed(timeout, TIMEOUT_MS);
        final Opened finish = (chatId, error) -> {
            if (!answered.compareAndSet(false, true)) return;
            h.removeCallbacks(timeout);
            cb.done(chatId, error);
        };
        try {
            // reading a chat that does not exist yet is refused by the rules (no members): that means "create it"
            chats().child(id).child("members").get().addOnCompleteListener(t -> {
                if (t.isSuccessful() && t.getResult().exists()) {
                    finish.done(id, null);
                    return;
                }
                create(app, id, r, me, finish);
            });
        } catch (Exception e) {
            finish.done(null, "other:" + e.getMessage());
        }
    }

    /** Turns a Firebase failure into a short code the screens can explain. */
    static String classify(Exception e) {
        String m = e == null ? "" : String.valueOf(e.getMessage());
        String low = m.toLowerCase(java.util.Locale.ROOT);
        if (low.contains("permission denied") || low.contains("permission_denied")) return "rules";
        if (low.contains("network") || low.contains("disconnected") || low.contains("unavailable")
                || low.contains("offline")) return "offline";
        return "other:" + m;
    }

    private static void create(final Context app, final String id, final CommunityRequest r, final String me, final Opened cb) {
        final String myName = Session.displayName(app);
        Map<String, Object> chat = new HashMap<>();
        chat.put("requestId", r.id);
        chat.put("requestTitle", r.title == null ? "" : r.title);
        chat.put("requesterUid", r.ownerUid);
        chat.put("requesterName", r.ownerName == null ? "" : r.ownerName);
        chat.put("helperUid", me);
        chat.put("helperName", myName);
        chat.put("createdAt", ServerValue.TIMESTAMP);
        chat.put("closed", false);
        Map<String, Object> members = new HashMap<>();
        members.put(me, true);
        members.put(r.ownerUid, true);
        chat.put("members", members);
        chats().child(id).setValue(chat).addOnCompleteListener(t -> {
            if (!t.isSuccessful()) {
                final String why = classify(t.getException());
                // the chat may already exist (the first read failed for a moment): read it once more before giving up
                chats().child(id).child("members").get().addOnCompleteListener(again -> {
                    if (again.isSuccessful() && again.getResult().exists()) cb.done(id, null);
                    else cb.done(null, why);
                });
                return;
            }
            // the chats list of both people (members exist now, so the rules allow writing the other one's entry)
            userChats(me).child(id).setValue(entry(r, r.ownerUid, r.ownerName == null ? "" : r.ownerName));
            userChats(r.ownerUid).child(id).setValue(entry(r, me, myName));
            cb.done(id, null);
        });
    }

    private static Map<String, Object> entry(CommunityRequest r, String otherUid, String otherName) {
        Map<String, Object> e = new HashMap<>();
        e.put("requestId", r.id);
        e.put("title", r.title == null ? "" : r.title);
        e.put("otherUid", otherUid);
        e.put("otherName", otherName);
        e.put("last", "");
        e.put("lastFrom", "");
        e.put("lastAt", ServerValue.TIMESTAMP);
        e.put("unread", 0);
        e.put("closed", false);
        return e;
    }

    // ---- send / read / typing / delete ----------------------------------------------------------------------

    /** @param otherUid the other member, so his chats list shows the message and one more unread. */
    public static void send(Context ctx, final String chatId, final String otherUid, String rawText, final Done cb) {
        final Context app = ctx.getApplicationContext();
        final String text = ChatRules.prepare(rawText);
        if (text == null) {
            cb.done("empty");
            return;
        }
        final String me = Session.uid(app);
        DatabaseReference msg = chats().child(chatId).child("messages").push();
        Map<String, Object> m = new HashMap<>();
        m.put("from", me);
        m.put("text", text);
        m.put("at", ServerValue.TIMESTAMP);
        msg.setValue(m).addOnCompleteListener(t -> {
            if (!t.isSuccessful()) {
                cb.done("refused");
                return;
            }
            String preview = ChatRules.preview(text);
            Map<String, Object> mine = new HashMap<>();
            mine.put("last", preview);
            mine.put("lastFrom", me);
            mine.put("lastAt", ServerValue.TIMESTAMP);
            mine.put("unread", 0);
            userChats(me).child(chatId).updateChildren(mine);
            Map<String, Object> theirs = new HashMap<>();
            theirs.put("last", preview);
            theirs.put("lastFrom", me);
            theirs.put("lastAt", ServerValue.TIMESTAMP);
            theirs.put("unread", ServerValue.increment(1));
            userChats(otherUid).child(chatId).updateChildren(theirs);
            cb.done(null);
        });
    }

    /** I have opened the chat: the other side sees "Seen", my unread counter goes to zero. */
    public static void markRead(Context ctx, String chatId) {
        if (!Session.canSyncOnline(ctx)) return;
        String me = Session.uid(ctx);
        try {
            chats().child(chatId).child("seen").child(me).setValue(ServerValue.TIMESTAMP);
            userChats(me).child(chatId).child("unread").setValue(0);
        } catch (Exception e) {
            Log.w(TAG, "markRead failed", e);
        }
    }

    public static void setTyping(Context ctx, String chatId, boolean typing) {
        if (!Session.canSyncOnline(ctx)) return;
        try {
            DatabaseReference t = chats().child(chatId).child("typing").child(Session.uid(ctx));
            t.setValue(typing);
            if (typing) t.onDisconnect().setValue(false); // a closed app never leaves "typing..." behind
        } catch (Exception e) {
            Log.w(TAG, "typing failed", e);
        }
    }

    /** Remove one of my own messages (for both people). */
    public static void deleteMessage(String chatId, String messageId) {
        try {
            chats().child(chatId).child("messages").child(messageId).removeValue();
        } catch (Exception e) {
            Log.w(TAG, "delete failed", e);
        }
    }

    // ---- watching one chat ----------------------------------------------------------------------------------

    public interface ChatListener {
        void onMessage(ChatMessage m);

        void onMessageRemoved(String id);

        /** The other person's last-seen time (0 = never). */
        void onOtherSeen(long at);

        void onOtherTyping(boolean typing);

        void onClosed(boolean closed);
    }

    public static final class Watch {
        private final List<Runnable> stoppers = new ArrayList<>();

        public void stop() {
            for (Runnable r : stoppers) r.run();
            stoppers.clear();
        }
    }

    public static Watch watch(Context ctx, String chatId, final String otherUid, final ChatListener l) {
        Watch w = new Watch();
        try {
            DatabaseReference chat = chats().child(chatId);
            Query q = chat.child("messages").orderByChild("at").limitToLast(300);
            final ChildEventListener ce = new ChildEventListener() {
                @Override
                public void onChildAdded(@NonNull DataSnapshot s, @Nullable String prev) {
                    ChatMessage m = parse(s);
                    if (m != null) l.onMessage(m);
                }

                @Override
                public void onChildChanged(@NonNull DataSnapshot s, @Nullable String prev) {
                    ChatMessage m = parse(s); // the server time arrives after the local estimate
                    if (m != null) l.onMessage(m);
                }

                @Override
                public void onChildRemoved(@NonNull DataSnapshot s) {
                    if (s.getKey() != null) l.onMessageRemoved(s.getKey());
                }

                @Override
                public void onChildMoved(@NonNull DataSnapshot s, @Nullable String prev) {
                }

                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    Log.w(TAG, "messages cancelled: " + e.getMessage());
                }
            };
            q.addChildEventListener(ce);
            w.stoppers.add(() -> q.removeEventListener(ce));

            final DatabaseReference seen = chat.child("seen").child(otherUid);
            final ValueEventListener sl = simple(s -> {
                Long v = s.getValue(Long.class);
                l.onOtherSeen(v == null ? 0 : v);
            });
            seen.addValueEventListener(sl);
            w.stoppers.add(() -> seen.removeEventListener(sl));

            final DatabaseReference typing = chat.child("typing").child(otherUid);
            final ValueEventListener tl = simple(s -> l.onOtherTyping(Boolean.TRUE.equals(s.getValue(Boolean.class))));
            typing.addValueEventListener(tl);
            w.stoppers.add(() -> typing.removeEventListener(tl));

            final DatabaseReference closed = chat.child("closed");
            final ValueEventListener cl = simple(s -> l.onClosed(Boolean.TRUE.equals(s.getValue(Boolean.class))));
            closed.addValueEventListener(cl);
            w.stoppers.add(() -> closed.removeEventListener(cl));
        } catch (Exception e) {
            Log.w(TAG, "watch failed", e);
        }
        return w;
    }

    private interface Snap {
        void on(DataSnapshot s);
    }

    private static ValueEventListener simple(final Snap f) {
        return new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot s) {
                f.on(s);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError e) {
                Log.w(TAG, "cancelled: " + e.getMessage());
            }
        };
    }

    @Nullable
    private static ChatMessage parse(DataSnapshot s) {
        String from = s.child("from").getValue(String.class);
        String text = s.child("text").getValue(String.class);
        Long at = s.child("at").getValue(Long.class);
        if (s.getKey() == null || from == null || text == null) return null;
        return new ChatMessage(s.getKey(), from, text, at == null ? System.currentTimeMillis() : at);
    }

    // ---- the chats list ---------------------------------------------------------------------------------------

    public interface ListListener {
        void onList(List<ChatSummary> list);
    }

    public static Watch watchList(Context ctx, final ListListener l) {
        Watch w = new Watch();
        if (!Session.canSyncOnline(ctx)) {
            l.onList(new ArrayList<>());
            return w;
        }
        try {
            final DatabaseReference ref = userChats(Session.uid(ctx));
            final ValueEventListener vl = simple(s -> l.onList(parseList(s)));
            ref.addValueEventListener(vl);
            w.stoppers.add(() -> ref.removeEventListener(vl));
        } catch (Exception e) {
            Log.w(TAG, "list failed", e);
        }
        return w;
    }

    private static List<ChatSummary> parseList(DataSnapshot all) {
        List<ChatSummary> out = new ArrayList<>();
        for (DataSnapshot s : all.getChildren()) {
            ChatSummary c = summary(s);
            if (c != null) out.add(c);
        }
        Collections.sort(out, new Comparator<ChatSummary>() {
            @Override
            public int compare(ChatSummary a, ChatSummary b) {
                return Long.compare(b.lastAt, a.lastAt);
            }
        });
        return out;
    }

    @Nullable
    private static ChatSummary summary(DataSnapshot s) {
        if (s.getKey() == null) return null;
        ChatSummary c = new ChatSummary();
        c.chatId = s.getKey();
        c.requestId = str(s, "requestId");
        c.title = str(s, "title");
        c.otherName = str(s, "otherName");
        c.otherUid = str(s, "otherUid");
        c.last = str(s, "last");
        c.lastFrom = str(s, "lastFrom");
        Long at = s.child("lastAt").getValue(Long.class);
        c.lastAt = at == null ? 0 : at;
        Long un = s.child("unread").getValue(Long.class);
        c.unread = un == null ? 0 : un.intValue();
        c.closed = Boolean.TRUE.equals(s.child("closed").getValue(Boolean.class));
        return c;
    }

    private static String str(DataSnapshot s, String k) {
        String v = s.child(k).getValue(String.class);
        return v == null ? "" : v;
    }

    // ---- closing with the request ---------------------------------------------------------------------------------

    /** The requester marked the request fulfilled (or reopened it): every chat about it closes (or opens again). */
    public static void setClosedForRequest(Context ctx, final String requestId, final boolean closed) {
        if (!Session.canSyncOnline(ctx)) return;
        final String me = Session.uid(ctx);
        try {
            userChats(me).get().addOnSuccessListener(all -> {
                for (DataSnapshot s : all.getChildren()) {
                    ChatSummary c = summary(s);
                    if (c == null || !requestId.equals(c.requestId)) continue;
                    chats().child(c.chatId).child("closed").setValue(closed);
                    userChats(me).child(c.chatId).child("closed").setValue(closed);
                    if (!c.otherUid.isEmpty()) userChats(c.otherUid).child(c.chatId).child("closed").setValue(closed);
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "close failed", e);
        }
    }

    // ---- notifications ------------------------------------------------------------------------------------------

    /**
     * Watches my chats list for the whole time the app is open and shows a notification for each new message
     * (not for my own, not for the chat that is on screen). Messages that arrived while the app was closed are
     * announced by the optional Cloud Function (functions/index.js), through the same messaging service.
     */
    public static Watch listenForNotifications(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        Watch w = new Watch();
        if (!Session.canSyncOnline(app)) return w;
        final String me = Session.uid(app);
        final Map<String, Long> notified = new HashMap<>();
        final boolean[] first = {true};
        try {
            final DatabaseReference ref = userChats(me);
            final ValueEventListener vl = simple(all -> {
                for (DataSnapshot s : all.getChildren()) {
                    ChatSummary c = summary(s);
                    if (c == null) continue;
                    Long before = notified.get(c.chatId);
                    if (first[0]) {
                        notified.put(c.chatId, c.lastAt); // what was already there when the app opened is not "new"
                        continue;
                    }
                    long seenAt = before == null ? 0 : before;
                    if (ChatRules.shouldNotify(c.lastFrom, me, c.unread, c.lastAt, seenAt, c.chatId.equals(openChatId))) {
                        announce(app, c);
                    }
                    notified.put(c.chatId, Math.max(seenAt, c.lastAt));
                }
                first[0] = false;
            });
            ref.addValueEventListener(vl);
            w.stoppers.add(() -> ref.removeEventListener(vl));
        } catch (Exception e) {
            Log.w(TAG, "notification listener failed", e);
        }
        return w;
    }

    /** Also used by the messaging service when a push arrives. */
    public static void announce(Context app, ChatSummary c) {
        Intent i = new Intent(app, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CHAT_ID, c.chatId)
                .putExtra(ChatActivity.EXTRA_OTHER_UID, c.otherUid)
                .putExtra(ChatActivity.EXTRA_OTHER_NAME, c.otherName)
                .putExtra(ChatActivity.EXTRA_TITLE, c.title);
        NotificationHelper.notifyIntent(app, NotificationHelper.CH_COMMUNITY, 3000 + (c.chatId.hashCode() & 0xFFFF),
                app.getString(R.string.chat_notification_title, c.otherName, c.title), c.last, i);
    }
}
