package com.locus.app.security;

/** Brute-force protection: after 5 wrong passwords the sign-in is locked, and the wait doubles. */
public final class LockoutPolicy {
    public static final int FREE_ATTEMPTS = 5;
    private static final int BASE_SECONDS = 30;
    private static final int MAX_SECONDS = 15 * 60;

    private LockoutPolicy() {
    }

    /** Seconds to wait after the given number of consecutive failures (0 = no lock yet). */
    public static int lockSeconds(int failures) {
        if (failures < FREE_ATTEMPTS) return 0;
        long s = (long) BASE_SECONDS << Math.min(failures - FREE_ATTEMPTS, 10);
        return (int) Math.min(s, MAX_SECONDS);
    }

    public static int attemptsLeft(int failures) {
        return Math.max(0, FREE_ATTEMPTS - failures);
    }
}
