package com.locus.app.model;

import java.util.Collection;

/**
 * Posts inside a family-and-friends group: the kinds, what each needs, who may vote or delete, the vote tally.
 * Pure Java, tested on the JVM.
 */
public final class GroupPostRules {
    public static final String TEXT = "text";
    public static final String PHOTO = "photo";
    public static final String TRIP = "trip";
    public static final String PARTY = "party";
    public static final String HELP = "help";
    /** A post the person made on the Community board (shared into their groups). */
    public static final String COMMUNITY = "community";
    /** An SOS the person sent (shared into their groups). */
    public static final String EMERGENCY = "emergency";

    public static final String HELP_DONATION = "donation";
    public static final String HELP_VOLUNTEER = "volunteer";
    public static final String HELP_BLOOD = "blood";
    public static final String HELP_OTHER = "other";
    public static final String[] HELP_KINDS = {HELP_DONATION, HELP_VOLUNTEER, HELP_BLOOD, HELP_OTHER};

    public static final String YES = "yes";
    public static final String MAYBE = "maybe";
    public static final String NO = "no";

    public static final int MAX_TEXT = 1000;
    public static final int MAX_TITLE = 80;
    public static final int MAX_PLACE = 80;

    private GroupPostRules() {
    }

    public static boolean isKnownType(String t) {
        return TEXT.equals(t) || PHOTO.equals(t) || TRIP.equals(t) || PARTY.equals(t) || HELP.equals(t)
                || COMMUNITY.equals(t) || EMERGENCY.equals(t);
    }

    /** Trip and party plans are voted on (going / maybe / not going); a help request on "I can help". */
    public static boolean canVote(String type) {
        return TRIP.equals(type) || PARTY.equals(type) || HELP.equals(type);
    }

    public static boolean isPlan(String type) {
        return TRIP.equals(type) || PARTY.equals(type);
    }

    public static boolean isVote(String v) {
        return YES.equals(v) || MAYBE.equals(v) || NO.equals(v);
    }

    public static boolean isHelpKind(String k) {
        for (String h : HELP_KINDS) if (h.equals(k)) return true;
        return false;
    }

    /** Trimmed, at most {@code max} characters, never null. */
    public static String clean(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > max ? t.substring(0, max).trim() : t;
    }

    /** Can this post be sent? Each kind needs its own essentials. */
    public static boolean validNew(String type, String title, String text, boolean hasPhoto, long when, long now) {
        if (!isKnownType(type)) return false;
        String ti = clean(title, MAX_TITLE);
        String tx = clean(text, MAX_TEXT);
        switch (type) {
            case TEXT: return !tx.isEmpty();
            case PHOTO: return hasPhoto;
            case TRIP:
            case PARTY: return !ti.isEmpty() && when > now;
            default: return !ti.isEmpty(); // help, community, emergency
        }
    }

    /** How many said yes, maybe, no. Unknown values are ignored. */
    public static int[] tally(Collection<String> votes) {
        int[] c = new int[3];
        if (votes == null) return c;
        for (String v : votes) {
            if (YES.equals(v)) c[0]++;
            else if (MAYBE.equals(v)) c[1]++;
            else if (NO.equals(v)) c[2]++;
        }
        return c;
    }

    /** What happens when I tap a vote button: tapping my current vote takes it back (null). */
    public static String nextVote(String current, String tapped) {
        return tapped != null && tapped.equals(current) ? null : tapped;
    }

    /** The author or the group's admin may delete a post. */
    public static boolean canDelete(String myUid, String authorUid, boolean isAdmin) {
        return myUid != null && !myUid.isEmpty() && (isAdmin || myUid.equals(authorUid));
    }

    /** A one-line preview for a notification. */
    public static String preview(String title, String text, int max) {
        String p = clean(title, MAX_TITLE);
        String t = clean(text, MAX_TEXT);
        String s = p.isEmpty() ? t : t.isEmpty() ? p : p + " - " + t;
        s = s.replace('\n', ' ');
        return s.length() > max ? s.substring(0, Math.max(0, max - 1)).trim() + "…" : s;
    }
}
