package com.locus.app.model;

/** Small pure rules of the request chat (unit-tested). */
public final class ChatRules {
    public static final int MAX_LENGTH = 1000;
    public static final int PREVIEW_LENGTH = 60;
    /** How many messages a chat loads at first and with each "load earlier". */
    public static final int PAGE_SIZE = 40;

    private ChatRules() {
    }

    /** One conversation per (request, helper): the same helper always lands in the same chat. */
    public static String chatId(String requestId, String helperUid) {
        return clean(requestId) + "_" + clean(helperUid);
    }

    /** Realtime Database keys may not contain . $ # [ ] / */
    public static String clean(String s) {
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

    /** The request a chat belongs to: the chat id is requestId + "_" + helperUid. */
    public static String requestIdOf(String chatId) {
        if (chatId == null) return "";
        int i = chatId.lastIndexOf('_');
        return i <= 0 ? chatId : chatId.substring(0, i);
    }

    /**
     * Where a message belongs in the list, which is ordered by time and then by id. The server time of a message
     * can differ slightly from the estimate shown while it was being sent, so a message is always re-placed.
     */
    public static int insertIndex(java.util.List<ChatMessage> list, ChatMessage m) {
        int pos = list.size();
        while (pos > 0) {
            ChatMessage p = list.get(pos - 1);
            if (p.at < m.at || (p.at == m.at && p.id.compareTo(m.id) <= 0)) break;
            pos--;
        }
        return pos;
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

    /** A reminder for unread messages is sent after this long unread... */
    public static final long REMIND_AFTER_MS = 30 * 60_000L;
    /** ...and not more often than this. */
    public static final long REMIND_EVERY_MS = 2 * 60 * 60_000L;

    /** Night hours (22:00-07:00) when no reminder is shown. */
    public static boolean isQuietHour(int hourOfDay) {
        return hourOfDay >= 22 || hourOfDay < 7;
    }

    /**
     * Whether to remind the person about messages they have not read: some are unread, the oldest one has waited
     * long enough, the last reminder is old enough, and it is not night.
     */
    public static boolean shouldRemind(int unreadTotal, long oldestUnreadAt, long lastReminderAt, long now, int hourOfDay) {
        return unreadTotal > 0 && oldestUnreadAt > 0
                && now - oldestUnreadAt >= REMIND_AFTER_MS
                && now - lastReminderAt >= REMIND_EVERY_MS
                && !isQuietHour(hourOfDay);
    }

    /** Unread chats first (newest first), then the rest by newest message. */
    public static int compareForInbox(int unreadA, long lastAtA, int unreadB, long lastAtB) {
        boolean ua = unreadA > 0;
        boolean ub = unreadB > 0;
        if (ua != ub) return ua ? -1 : 1;
        return Long.compare(lastAtB, lastAtA);
    }
}
