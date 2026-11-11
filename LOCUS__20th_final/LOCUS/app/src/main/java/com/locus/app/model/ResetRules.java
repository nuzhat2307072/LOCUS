package com.locus.app.model;

import java.util.Locale;

/**
 * Password reset by e-mail: how the typed e-mail is cleaned, how often a link may be asked for, which language the
 * e-mail is written in, and what a Firebase error means. Pure Java, tested on the JVM.
 */
public final class ResetRules {
    /** A new link can be asked for this often (Firebase also limits it; this avoids hitting that limit). */
    public static final long COOLDOWN_MS = 60_000L;

    public enum Problem { NO_ACCOUNT, INVALID_EMAIL, TOO_MANY, NETWORK, DISABLED, OTHER }

    private ResetRules() {
    }

    /** Trimmed and lower case: "  Nuzhat@Mail.COM " and "nuzhat@mail.com" are the same account. */
    public static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Milliseconds still to wait before the next link may be sent (0 when it may be sent now). */
    public static long cooldownLeftMs(long lastSentAt, long now) {
        if (lastSentAt <= 0 || now < lastSentAt) return lastSentAt > now ? COOLDOWN_MS : 0;
        return Math.max(0, COOLDOWN_MS - (now - lastSentAt));
    }

    /** Whole seconds, rounded up, for the countdown text. */
    public static int secondsLeft(long lastSentAt, long now) {
        return (int) ((cooldownLeftMs(lastSentAt, now) + 999) / 1000);
    }

    /** The e-mail languages LOCUS has: English, Bangla, Hindi. Anything else gets English. */
    public static String emailLanguage(String appLanguage) {
        if (appLanguage == null) return "en";
        String l = appLanguage.toLowerCase(Locale.ROOT);
        return l.equals("bn") || l.equals("hi") ? l : "en";
    }

    /** Maps a Firebase error code (FirebaseAuthException.getErrorCode()) to what the person should be told. */
    public static Problem classify(String errorCode, boolean networkError) {
        if (networkError) return Problem.NETWORK;
        if (errorCode == null) return Problem.OTHER;
        switch (errorCode) {
            case "ERROR_USER_NOT_FOUND":
            case "ERROR_USER_DISABLED":
                return Problem.NO_ACCOUNT;
            case "ERROR_INVALID_EMAIL":
                return Problem.INVALID_EMAIL;
            case "ERROR_TOO_MANY_REQUESTS":
                return Problem.TOO_MANY;
            case "ERROR_OPERATION_NOT_ALLOWED":
                return Problem.DISABLED;
            default:
                return Problem.OTHER;
        }
    }

    /**
     * What is known about the e-mail before sending: a cloud account (a link will really arrive), a phone-only
     * account made before Firebase was connected (no link will arrive until the person logs in once), or unknown.
     */
    public enum Knowledge { CLOUD, LOCAL_ONLY, UNKNOWN }

    public static Knowledge knowledge(boolean knownCloud, boolean knownLocal) {
        if (knownCloud) return Knowledge.CLOUD;
        return knownLocal ? Knowledge.LOCAL_ONLY : Knowledge.UNKNOWN;
    }
}
