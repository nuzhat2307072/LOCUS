package com.locus.app.model;

/** Small pure rules of the request chat (unit-tested). */
public final class ChatRules {
    public static final int MAX_LENGTH = 1000;
    public static final int PREVIEW_LENGTH = 60;

    private ChatRules() {
    }

    /** One conversation per (request, helper): the same helper always lands in the same chat. */
    public static String chatId(String requestId, String helperUid) {
        return clean(requestId) + "_" + clean(helperUid);
    }

    /** Realtime Database keys may not contain . $ # [ ] / */
    static String clean(String s) {
        return s == null ? "" : s.replaceAll("[.$#\\[\\]/]", "-");
    }

    /** What may be sent: trimmed, not empty, at most MAX_LENGTH characters; null = nothing to send. */
    public static String prepare(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        return t.length() > MAX_LENGTH ? t.substring(0, MAX_LENGTH) : t;
    }

    /** One line for the chats list. */
    public static String preview(String text) {
        if (text == null) return "";
        String one = text.replace('\n', ' ').trim();
        return one.length() > PREVIEW_LENGTH ? one.substring(0, PREVIEW_LENGTH - 1) + "\u2026" : one;
    }

    /** My message counts as seen when the other person opened the chat after it was sent. */
    public static boolean isSeen(long messageAt, long otherSeenAt) {
        return otherSeenAt > 0 && otherSeenAt >= messageAt;
    }

    /** The two people of a chat: the one who is not me. */
    public static String otherUid(String requesterUid, String helperUid, String myUid) {
        return myUid != null && myUid.equals(requesterUid) ? helperUid : requesterUid;
    }

    /** A request that is fulfilled (or gone) closes its chats: they stay readable but nobody can write. */
    public static boolean canWrite(boolean chatClosed, boolean requestDone) {
        return !chatClosed && !requestDone;
    }

    /** A new-message notification is shown only for somebody else's message that is newer than the last one shown. */
    public static boolean shouldNotify(String lastFrom, String myUid, int unread, long lastAt, long lastNotifiedAt, boolean chatOpen) {
        return lastFrom != null && !lastFrom.equals(myUid) && unread > 0 && lastAt > lastNotifiedAt && !chatOpen;
    }
}
