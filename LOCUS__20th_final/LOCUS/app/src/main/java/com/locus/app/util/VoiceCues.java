package com.locus.app.util;

/**
 * Decides WHEN a spoken direction is given. Pure Java (unit-tested).
 * For every manoeuvre there are at most two announcements: an early one ("In 300 metres turn left") and a
 * last one right before the turn ("Turn left now"). Faster travel modes get them earlier.
 */
public final class VoiceCues {
    public static final int NONE = 0;
    public static final int EARLY = 1;
    public static final int NOW = 2;

    private final double earlyMeters;
    private final double nowMeters;
    private long stepKey = Long.MIN_VALUE;
    private boolean earlyDone;
    private boolean nowDone;

    /** Thresholds for walking, cycling and driving. */
    public static VoiceCues forSpeed(double metresPerSecond) {
        if (metresPerSecond >= 10) return new VoiceCues(400, 120); // car
        if (metresPerSecond >= 3) return new VoiceCues(200, 60);   // bicycle
        return new VoiceCues(80, 25);                              // on foot
    }

    public VoiceCues(double earlyMeters, double nowMeters) {
        this.earlyMeters = earlyMeters;
        this.nowMeters = nowMeters;
    }

    /**
     * @param key        identifies the manoeuvre (its distance from the start of the route, rounded)
     * @param toNext     metres to that manoeuvre
     * @return NONE, EARLY or NOW - each at most once per manoeuvre
     */
    public int check(long key, double toNext) {
        if (key != stepKey) {
            stepKey = key;
            earlyDone = false;
            nowDone = false;
        }
        if (toNext <= nowMeters) {
            if (nowDone) return NONE;
            nowDone = true;
            earlyDone = true; // never an "in 300 m" after "now"
            return NOW;
        }
        if (toNext <= earlyMeters && !earlyDone) {
            earlyDone = true;
            return EARLY;
        }
        return NONE;
    }

    /** Forget everything (new route after a re-route). */
    public void reset() {
        stepKey = Long.MIN_VALUE;
        earlyDone = false;
        nowDone = false;
    }
}
