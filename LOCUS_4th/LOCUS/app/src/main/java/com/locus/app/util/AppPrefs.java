package com.locus.app.util;

import android.content.Context;
import android.content.SharedPreferences;

/** Small SharedPreferences wrapper (sign-in attempt counter, notification flag, last known location). */
public final class AppPrefs {
    private static final String FILE = "locus_prefs";
    private static final String KEY_LAT = "last_lat";
    private static final String KEY_LON = "last_lon";

    /** Fallback when no GPS fix is available yet (Khulna city centre). */
    public static final double DEFAULT_LAT = 22.8456;
    public static final double DEFAULT_LON = 89.5403;

    private AppPrefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Consecutive wrong passwords (reset by a successful sign-in) - see LockoutPolicy. */
    public static int failedLogins(Context c) {
        return sp(c).getInt("failed_logins", 0);
    }

    public static void setFailedLogins(Context c, int n) {
        sp(c).edit().putInt("failed_logins", n).apply();
    }

    /** Wall-clock time until which sign-in is locked (0 = not locked). */
    public static long lockedUntil(Context c) {
        return sp(c).getLong("locked_until", 0L);
    }

    public static void setLockedUntil(Context c, long t) {
        sp(c).edit().putLong("locked_until", t).apply();
    }

    /** The notification permission is asked once, at the moment it becomes useful (Community screen). */
    public static boolean notificationsAsked(Context c) {
        return sp(c).getBoolean("notif_asked", false);
    }

    public static void setNotificationsAsked(Context c) {
        sp(c).edit().putBoolean("notif_asked", true).apply();
    }

    public static void setLastLocation(Context c, double lat, double lon) {
        sp(c).edit().putFloat(KEY_LAT, (float) lat).putFloat(KEY_LON, (float) lon).apply();
    }

    public static boolean hasLastLocation(Context c) {
        return sp(c).contains(KEY_LAT);
    }

    /** @return {lat, lon} - last GPS fix, or the default city centre. */
    public static double[] lastLocation(Context c) {
        SharedPreferences p = sp(c);
        if (!p.contains(KEY_LAT)) return new double[]{DEFAULT_LAT, DEFAULT_LON};
        return new double[]{p.getFloat(KEY_LAT, (float) DEFAULT_LAT), p.getFloat(KEY_LON, (float) DEFAULT_LON)};
    }
}
