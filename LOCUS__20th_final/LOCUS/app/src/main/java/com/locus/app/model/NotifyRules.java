package com.locus.app.model;

/** Which notifications are shown: per-kind switches and quiet hours. Pure Java, tested on the JVM. */
public final class NotifyRules {
    public static final int CHAT = 0;
    public static final int HAZARD = 1;
    public static final int COMMUNITY = 2;
    public static final int KINDS = 3;

    private NotifyRules() {
    }

    /** Quiet hours may cross midnight (22 to 7). from == to means the whole day is quiet. */
    public static boolean inQuietHours(boolean enabled, int fromHour, int toHour, int hour) {
        if (!enabled) return false;
        if (fromHour == toHour) return true;
        if (fromHour < toHour) return hour >= fromHour && hour < toHour;
        return hour >= fromHour || hour < toHour;
    }

    /**
     * @param kindOn  the switch of this kind of notification
     * @param urgent  an SOS: always shown
     * @param silent  a quiet notification that makes no sound (such as the unread-messages summary): quiet hours
     *                do not apply to it, only the switch does
     */
    public static boolean allowed(boolean kindOn, boolean quietEnabled, int fromHour, int toHour, int hour,
                                  boolean urgent, boolean silent) {
        if (urgent) return true;
        if (!kindOn) return false;
        return silent || !inQuietHours(quietEnabled, fromHour, toHour, hour);
    }
}
