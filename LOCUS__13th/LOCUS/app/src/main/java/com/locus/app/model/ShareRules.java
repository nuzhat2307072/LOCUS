package com.locus.app.model;

import java.security.SecureRandom;

/** Pure rules of live location sharing (unit-tested): share codes, when a share must stop, how "fresh" a position is. */
public final class ShareRules {
    /** No 0/O/1/I/L: a code is read aloud or typed from a message. */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    public static final int CODE_LENGTH = 6;
    public static final int LOW_BATTERY_PERCENT = 15;
    public static final double ARRIVE_METERS = 40;

    public enum StopReason {NONE, TIME_UP, ARRIVED, LOW_BATTERY}

    private ShareRules() {
    }

    public static String newCode(SecureRandom rnd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CODE_LENGTH; i++) sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    /** Upper case, spaces and dashes removed; null when it cannot be a code. */
    public static String normalizeCode(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(java.util.Locale.ROOT).replace(" ", "").replace("-", "");
        if (s.length() != CODE_LENGTH) return null;
        for (int i = 0; i < s.length(); i++) if (ALPHABET.indexOf(s.charAt(i)) < 0) return null;
        return s;
    }

    /**
     * @param expiresAt     wall-clock end of a timed share, 0 for "until I arrive"
     * @param distToDestM   distance to the destination, or a negative number when there is none
     * @param batteryPct    0-100
     * @param charging      the phone is plugged in
     */
    public static StopReason stopReason(long now, long expiresAt, boolean untilArrive, double distToDestM,
                                        int batteryPct, boolean charging) {
        if (expiresAt > 0 && now >= expiresAt) return StopReason.TIME_UP;
        if (untilArrive && distToDestM >= 0 && distToDestM <= ARRIVE_METERS) return StopReason.ARRIVED;
        if (!charging && batteryPct <= LOW_BATTERY_PERCENT) return StopReason.LOW_BATTERY;
        return StopReason.NONE;
    }
}
