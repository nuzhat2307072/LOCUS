package com.locus.app.util;

/**
 * Recognises "shake the phone three times" from accelerometer readings. Pure Java (unit-tested).
 * A shake is a reading well above gravity; three of them, each at least 150 ms apart and all inside 2 seconds,
 * count as the gesture. After it fires there is a 10 second pause, so one shake never starts SOS twice.
 */
public final class ShakeDetector {
    /** Acceleration (m/s^2, gravity included) above which a reading counts as one shake. About 2.2 g. */
    public static final double THRESHOLD = 22.0;
    private static final long MIN_GAP_MS = 150;
    private static final long WINDOW_MS = 2000;
    private static final long PAUSE_MS = 10_000;
    private static final int SHAKES_NEEDED = 3;

    private final long[] times = new long[SHAKES_NEEDED];
    private int count;
    private long lastShakeAt = Long.MIN_VALUE / 2;
    private long pausedUntil = Long.MIN_VALUE / 2;

    /** @return true exactly when the three-shake gesture has just been completed */
    public boolean onReading(double x, double y, double z, long nowMs) {
        if (nowMs < pausedUntil) return false;
        double magnitude = Math.sqrt(x * x + y * y + z * z);
        if (magnitude < THRESHOLD) return false;
        if (nowMs - lastShakeAt < MIN_GAP_MS) return false; // the same jolt seen twice
        lastShakeAt = nowMs;
        if (count > 0 && nowMs - times[0] > WINDOW_MS) { // too slow: start counting again
            shiftOutOld(nowMs);
        }
        if (count < SHAKES_NEEDED) times[count++] = nowMs;
        if (count == SHAKES_NEEDED && nowMs - times[0] <= WINDOW_MS) {
            count = 0;
            pausedUntil = nowMs + PAUSE_MS;
            return true;
        }
        if (count == SHAKES_NEEDED) shiftOutOld(nowMs);
        return false;
    }

    private void shiftOutOld(long nowMs) {
        int keep = 0;
        long[] copy = new long[SHAKES_NEEDED];
        for (int i = 0; i < count; i++) {
            if (nowMs - times[i] <= WINDOW_MS) copy[keep++] = times[i];
        }
        System.arraycopy(copy, 0, times, 0, SHAKES_NEEDED);
        count = keep;
    }

    public void reset() {
        count = 0;
    }
}
