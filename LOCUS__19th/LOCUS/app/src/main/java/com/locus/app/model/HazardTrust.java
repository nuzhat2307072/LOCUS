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

    /** A person can say "still there" / "cleared" only when they are this close to the hazard (checked on the phone). */
    public static final int VOTE_RADIUS_M = 500;
    /** GPS is not exact: a stored vote distance up to this still counts as "nearby" when verifying. */
    public static final int COUNT_RADIUS_M = 600;
    /** People nearby needed to call a report verified. */
    public static final int VERIFY_MIN = 2;

    private HazardTrust() {
    }

    /** Whether a person {@code distanceM} metres away may vote (negative = position unknown = not allowed). */
    public static boolean canVote(double distanceM) {
        return distanceM >= 0 && distanceM <= VOTE_RADIUS_M;
    }

    /** Whether a stored vote distance counts as "was nearby" (negative / missing = unknown = does not count). */
    public static boolean countsAsNearby(double voteDistanceM) {
        return voteDistanceM >= 0 && voteDistanceM <= COUNT_RADIUS_M;
    }

    /**
     * VERIFIED: at least {@link #VERIFY_MIN} different people nearby (not the reporter) said "still there" and
     * they outnumber the nearby people who said "cleared".
     */
    public static boolean verified(int nearStill, int nearCleared) {
        return nearStill >= VERIFY_MIN && nearStill > nearCleared;
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

    /** How long a hazard stays without a new "Still there": verified reports last half as long again. */
    public static long lifetimeMillis(int ttlHours, boolean verified) {
        return (long) (ttlHours * 3600_000L * (verified ? 1.5 : 1.0));
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
