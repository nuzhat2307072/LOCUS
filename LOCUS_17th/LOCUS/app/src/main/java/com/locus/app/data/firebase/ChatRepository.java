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
    private static final long TIMEOUT_MS = 10000;

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

    /** @param t the conversation: chat id, the other person, the request title (they fill my chats-list row). */
    public static void send(Context ctx, final ChatSummary t, String rawText, final Done cb) {
        final Context app = ctx.getApplicationContext();
        final String text = ChatRules.prepare(rawText);
        if (text == null) {
            cb.done("empty");
            return;
        }
        push(app, t, text, null, ChatRules.preview(text), cb);
    }

    /** Sends a photo (base64 JPEG, see ImageCodec). */
    public static void sendPhoto(Context ctx, ChatSummary t, String base64, Done cb) {
        Context app = ctx.getApplicationContext();
        push(app, t, "", base64, app.getString(R.string.chat_photo_preview), cb);
    }

    /**
     * ONE atomic write: the message, my chats-list row and the other person's row (last message, time, +1 unread).
     * Before, these were three separate writes, so a failure or a closed app between them left the list wrong,
     * and a missing row was re-created without its name and title.
     */
    private static void push(Context app, final ChatSummary t, final String text, final String image,
                             final String preview, final Done cb) {
        final String me = Session.uid(app);
        final String myName = Session.displayName(app);
        final String chatId = t.chatId;
        final String requestId = t.requestId.isEmpty() ? ChatRules.requestIdOf(chatId) : t.requestId;
        try {
            DatabaseReference msgRef = chats().child(chatId).child("messages").push();
            final String mid = msgRef.getKey();
            Map<String, Object> m = new HashMap<>();
            m.put("from", me);
            m.put("text", text);
            m.put("at", ServerValue.TIMESTAMP);
            if (image != null) m.put("hasImg", true);
            Map<String, Object> up = new HashMap<>();
            up.put("chats/" + chatId + "/messages/" + mid, m);
            if (image != null) up.put("chatImages/" + chatId + "/" + mid, image);
            String mine = "userChats/" + me + "/" + chatId + "/";
            up.put(mine + "requestId", requestId);
            up.put(mine + "title", t.title);
            up.put(mine + "otherUid", t.otherUid);
            up.put(mine + "otherName", t.otherName);
            up.put(mine + "last", preview);
            up.put(mine + "lastFrom", me);
            up.put(mine + "lastAt", ServerValue.TIMESTAMP);
            up.put(mine + "unread", 0);
            String theirs = "userChats/" + t.otherUid + "/" + chatId + "/";
            up.put(theirs + "requestId", requestId);
            up.put(theirs + "title", t.title);
            up.put(theirs + "otherUid", me);
            up.put(theirs + "otherName", myName);
            up.put(theirs + "last", preview);
            up.put(theirs + "lastFrom", me);
            up.put(theirs + "lastAt", ServerValue.TIMESTAMP);
            up.put(theirs + "unread", ServerValue.increment(1));
            Rtdb.get().getReference().updateChildren(up).addOnCompleteListener(task ->
                    cb.done(task.isSuccessful() ? null : classify(task.getException())));
        } catch (Exception e) {
            cb.done("other:" + e.getMessage());
        }
    }

    private static final java.util.Set<String> ensured = new java.util.HashSet<>();

    /**
     * Makes sure both people have a complete row in their chats list for this chat (name, title, request). Rows
     * that were never written, or written half-empty by an older version, are repaired. Only fixed fields are
     * written, never the last message or the unread counter. Once per chat and app start.
     */
    public static void ensureEntries(Context ctx, ChatSummary t) {
        if (!Session.canSyncOnline(ctx)) return;
        synchronized (ensured) {
            if (!ensured.add(t.chatId)) return;
        }
        try {
            String me = Session.uid(ctx);
            String requestId = t.requestId.isEmpty() ? ChatRules.requestIdOf(t.chatId) : t.requestId;
            Map<String, Object> up = new HashMap<>();
            String mine = "userChats/" + me + "/" + t.chatId + "/";
            up.put(mine + "requestId", requestId);
            up.put(mine + "title", t.title);
            up.put(mine + "otherUid", t.otherUid);
            up.put(mine + "otherName", t.otherName);
            String theirs = "userChats/" + t.otherUid + "/" + t.chatId + "/";
            up.put(theirs + "requestId", requestId);
            up.put(theirs + "title", t.title);
            up.put(theirs + "otherUid", me);
            up.put(theirs + "otherName", Session.displayName(ctx));
            Rtdb.get().getReference().updateChildren(up).addOnFailureListener(e -> {
                synchronized (ensured) {
                    ensured.remove(t.chatId); // try again next time
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "ensureEntries failed", e);
        }
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

    // ---- block / report -------------------------------------------------------------------------------------

    /** Blocked people cannot write to me any more (the database rules enforce it). */
    public static void setBlocked(Context ctx, String otherUid, boolean blocked) {
        if (!Session.canSyncOnline(ctx)) return;
        try {
            DatabaseReference r = Rtdb.get().getReference("blocks").child(Session.uid(ctx)).child(ChatRules.clean(otherUid));
            if (blocked) r.setValue(true);
            else r.removeValue();
        } catch (Exception e) {
            Log.w(TAG, "block failed", e);
        }
    }

    /** Tells whether I blocked this person, now and whenever it changes. Call the returned Runnable to stop. */
    public static Runnable watchBlocked(Context ctx, String otherUid, final java.util.function.Consumer<Boolean> cb) {
        if (!Session.canSyncOnline(ctx)) return () -> { };
        try {
            final DatabaseReference r = Rtdb.get().getReference("blocks").child(Session.uid(ctx)).child(ChatRules.clean(otherUid));
            final ValueEventListener l = simple(s -> cb.accept(Boolean.TRUE.equals(s.getValue(Boolean.class))));
            r.addValueEventListener(l);
            return () -> r.removeEventListener(l);
        } catch (Exception e) {
            return () -> { };
        }
    }

    /** A report for the moderators: who, in which chat, why. The reporter cannot read reports back. */
    public static void report(Context ctx, String chatId, String reportedUid, String reason, final Done cb) {
        try {
            Map<String, Object> m = new HashMap<>();
            m.put("chatId", chatId);
            m.put("reporter", Session.uid(ctx));
            m.put("reported", reportedUid);
            m.put("reason", reason);
            m.put("at", ServerValue.TIMESTAMP);
            Rtdb.get().getReference("reports").push().setValue(m)
                    .addOnCompleteListener(t -> cb.done(t.isSuccessful() ? null : "refused"));
        } catch (Exception e) {
            cb.done("refused");
        }
    }

    /** Remove one of my own messages (for both people), with its photo. */
    public static void deleteMessage(String chatId, String messageId, boolean hasImage) {
        try {
            Map<String, Object> up = new HashMap<>();
            up.put("chats/" + chatId + "/messages/" + messageId, null);
            if (hasImage) up.put("chatImages/" + chatId + "/" + messageId, null);
            Rtdb.get().getReference().updateChildren(up);
        } catch (Exception e) {
            Log.w(TAG, "delete failed", e);
        }
    }

    /** A photo is not downloaded with the messages (that was slow); it is fetched when its bubble is shown. */
    public static void loadImage(String chatId, String messageId, final ImageListener cb) {
        try {
            Rtdb.get().getReference("chatImages").child(chatId).child(messageId).get().addOnCompleteListener(t ->
                    cb.onImage(t.isSuccessful() ? t.getResult().getValue(String.class) : null));
        } catch (Exception e) {
            cb.onImage(null);
        }
    }

    public interface ImageListener {
        void onImage(@Nullable String base64);
    }

    public interface EarlierListener {
        void onEarlier(List<ChatMessage> older);
    }

    /** The messages before the oldest one on screen ("load earlier"); at most one page, oldest first. */
    public static void loadEarlier(String chatId, long beforeAt, final EarlierListener cb) {
        try {
            chats().child(chatId).child("messages").orderByChild("at").endAt((double) beforeAt)
                    .limitToLast(ChatRules.PAGE_SIZE + 1).get().addOnCompleteListener(t -> {
                List<ChatMessage> out = new ArrayList<>();
                if (t.isSuccessful()) {
                    for (DataSnapshot s : t.getResult().getChildren()) {
                        ChatMessage m = parse(s);
                        if (m != null) out.add(m);
                    }
                }
                cb.onEarlier(out);
            });
        } catch (Exception e) {
            cb.onEarlier(new ArrayList<>());
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
            Query q = chat.child("messages").orderByChild("at").limitToLast(ChatRules.PAGE_SIZE);
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
        ChatMessage m = new ChatMessage(s.getKey(), from, text, at == null ? System.currentTimeMillis() : at);
        String img = s.child("img").getValue(String.class); // photos sent by an older version were inline
        m.image = img == null ? "" : img;
        m.hasImage = !m.image.isEmpty() || Boolean.TRUE.equals(s.child("hasImg").getValue(Boolean.class));
        return m;
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
        try {
            final DatabaseReference ref = userChats(me);
            ref.keepSynced(true); // the chats list stays on the phone: it opens at once, also without internet
            final ValueEventListener vl = simple(all -> {
                List<ChatSummary> list = new ArrayList<>();
                for (DataSnapshot s : all.getChildren()) {
                    ChatSummary c = summary(s);
                    if (c != null) list.add(c);
                }
                announceNew(app, me, list);
            });
            ref.addValueEventListener(vl);
            w.stoppers.add(() -> ref.removeEventListener(vl));
        } catch (Exception e) {
            Log.w(TAG, "notification listener failed", e);
        }
        return w;
    }

    private static final String NOTIFIED_PREFS = "locus_chat_notified";
    private static final String KEY_BASELINE = "_baseline_";

    /**
     * Notifies for every chat whose newest message is from somebody else, unread, and newer than the last
     * message already announced on this phone. What was announced is remembered on the phone, so a message that
     * arrived while the app was closed is announced the next time the list is read - by the live listener, the
     * background service or the 15-minute check - and never twice.
     */
    static synchronized void announceNew(Context app, String me, List<ChatSummary> chats) {
        android.content.SharedPreferences sp = app.getSharedPreferences(NOTIFIED_PREFS, Context.MODE_PRIVATE);
        String key = KEY_BASELINE + me;
        boolean baselined = sp.getBoolean(key, false);
        android.content.SharedPreferences.Editor ed = sp.edit();
        for (ChatSummary c : chats) {
            String k = me + "|" + c.chatId;
            long seenAt = sp.getLong(k, -1);
            if (seenAt < 0) { // first time this phone sees the chat
                seenAt = baselined ? 0 : c.lastAt; // on the very first run what is there is not "new"
            }
            if (ChatRules.shouldNotify(c.lastFrom, me, c.unread, c.lastAt, seenAt, c.chatId.equals(openChatId))) {
                announce(app, c);
            }
            ed.putLong(k, Math.max(seenAt, c.lastAt));
        }
        ed.putBoolean(key, true).apply();
    }

    /** Blocking read of my chats list for the background check (call from a worker thread only). */
    public static void checkBlocking(Context ctx) throws Exception {
        final Context app = ctx.getApplicationContext();
        if (!Session.canSyncOnline(app)) return;
        String me = Session.uid(app);
        DataSnapshot all = com.google.android.gms.tasks.Tasks.await(userChats(me).get(), 40,
                java.util.concurrent.TimeUnit.SECONDS);
        List<ChatSummary> list = new ArrayList<>();
        for (DataSnapshot s : all.getChildren()) {
            ChatSummary c = summary(s);
            if (c != null) list.add(c);
        }
        announceNew(app, me, list);
    }

    /** Also used by the messaging service when a push arrives. */
    public static void announce(Context app, ChatSummary c) {
        Intent i = new Intent(app, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CHAT_ID, c.chatId)
                .putExtra(ChatActivity.EXTRA_OTHER_UID, c.otherUid)
                .putExtra(ChatActivity.EXTRA_OTHER_NAME, c.otherName)
                .putExtra(ChatActivity.EXTRA_REQUEST_ID, c.requestId)
                .putExtra(ChatActivity.EXTRA_TITLE, c.title);
        NotificationHelper.notifyIntent(app, NotificationHelper.CH_COMMUNITY, 3000 + (c.chatId.hashCode() & 0xFFFF),
                app.getString(R.string.chat_notification_title, c.otherName, c.title), c.last, i);
    }
}
