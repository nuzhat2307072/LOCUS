package com.locus.app.model;

/**
 * Rules of contact requests. To message the person behind a Community post you first send a request; when they
 * accept, you are saved as contacts on both accounts and can message each other. Pure Java, tested on the JVM.
 */
public final class ContactRules {
    public static final int NONE = 0;      // no request yet (or an old declined one: a new request may be sent)
    public static final int PENDING = 1;   // waiting for the other person to accept
    public static final int CONTACT = 2;   // accepted: they are in each other's contacts
    public static final int DECLINED = 3;  // declined recently: wait before asking again

    /** After a decline the same person may ask again after this long. */
    public static final long RETRY_AFTER_MS = 24L * 60 * 60 * 1000;
    public static final int MAX_NOTE = 200;

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_DECLINED = "declined";

    private ContactRules() {
    }

    /**
     * @param isContact     the other person is in my contacts
     * @param requestStatus status of my request to them ("pending", "declined") or null when there is none
     * @param requestAt     when that request was sent / answered
     */
    public static int state(boolean isContact, String requestStatus, long requestAt, long now) {
        if (isContact) return CONTACT;
        if (STATUS_PENDING.equals(requestStatus)) return PENDING;
        if (STATUS_DECLINED.equals(requestStatus)) {
            return now - requestAt >= RETRY_AFTER_MS ? NONE : DECLINED;
        }
        return NONE;
    }

    /** One direct chat per pair of contacts, the same id for both people (smaller id first). */
    public static String directChatId(String uidA, String uidB) {
        String a = ChatRules.clean(uidA);
        String b = ChatRules.clean(uidB);
        return a.compareTo(b) <= 0 ? "dm_" + a + "_" + b : "dm_" + b + "_" + a;
    }

    public static boolean isDirectChat(String chatId) {
        return chatId != null && chatId.startsWith("dm_");
    }

    /** The optional note sent with a request: trimmed, at most MAX_NOTE characters, never null. */
    public static String note(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        return t.length() > MAX_NOTE ? t.substring(0, MAX_NOTE) : t;
    }

    /** Whether a request is still waiting for an answer from me (the person it was sent to). */
    public static boolean needsAnswer(String status) {
        return STATUS_PENDING.equals(status);
    }
}
