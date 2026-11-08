package com.locus.app.model;

/**
 * The rules of hazard voting in one place (plain Java, so they are unit-tested):
 * confidence level, when a hazard is removed by "Cleared" votes, and reputation points.
 */
public final class HazardTrust {
    public enum Confidence {NEW, CONFIRMED, DISPUTED}

    /** A hazard older than this that nobody has confirmed makes the app ask people who pass it. */
    public static final long ASK_AFTER_MS = 30 * 60 * 1000L;
    /** A report cleared this soon after it was made was most likely wrong. */
    public static final long QUICK_CLEAR_MS = 15 * 60 * 1000L;

    private HazardTrust() {
    }

    /**
     * CONFIRMED: at least 2 "Still there" votes and more of them than "Cleared".
     * DISPUTED: at least one "Cleared" vote and not fewer than "Still there".
     * NEW: everything else (no votes yet, or not enough to say).
     */
    public static Confidence confidence(int still, int cleared) {
        if (still >= 2 && still > cleared) return Confidence.CONFIRMED;
        if (cleared >= 1 && cleared >= still) return Confidence.DISPUTED;
        return Confidence.NEW;
    }

    /** The hazard is taken off the board when at least 2 people say it is gone and they outnumber the "still there" votes. */
    public static boolean removedByVotes(int still, int cleared) {
        return cleared >= 2 && cleared > still;
    }

    /** Hazards expire when nobody has confirmed them for {@code ttlMillis}; a "Still there" vote restarts the clock. */
    public static long expiresAt(long createdAt, long lastConfirmedAt, long ttlMillis) {
        return Math.max(createdAt, lastConfirmedAt) + ttlMillis;
    }

    /** Points for the reporter when somebody else changes their vote from {@code previous} to {@code now} (null = no vote yet). */
    public static int reputationDelta(String previous, String now, long hazardAgeMs, boolean removedNow) {
        int d = 0;
        boolean wasStill = "still".equals(previous);
        boolean isStill = "still".equals(now);
        if (isStill && !wasStill) d += 1;     // confirmed by someone else
        if (!isStill && wasStill) d -= 1;     // that confirmation was taken back
        if (removedNow && hazardAgeMs < QUICK_CLEAR_MS) d -= 2; // cleared almost at once: probably a wrong report
        return d;
    }
}
