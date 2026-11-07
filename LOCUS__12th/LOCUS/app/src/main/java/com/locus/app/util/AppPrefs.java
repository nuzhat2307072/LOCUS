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

    /** E-mails known to have an account in Firebase (so a password reset e-mail can really arrive). */
    public static void markCloudAccount(Context c, String email) {
        if (email == null) return;
        java.util.Set<String> s = new java.util.HashSet<>(sp(c).getStringSet("cloud_accounts", new java.util.HashSet<>()));
        s.add(email.trim().toLowerCase(java.util.Locale.ROOT));
        sp(c).edit().putStringSet("cloud_accounts", s).apply();
    }

    public static boolean isCloudAccount(Context c, String email) {
        return email != null && sp(c).getStringSet("cloud_accounts", new java.util.HashSet<>())
                .contains(email.trim().toLowerCase(java.util.Locale.ROOT));
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

    // ---- starting point of navigation: current GPS location, or a place the person set --------------

    /** True when the person chose "Set location" (otherwise navigation starts from the live GPS position). */
    public static boolean originIsSet(Context c) {
        return sp(c).getBoolean("origin_set", false);
    }

    public static void setOrigin(Context c, String name, double lat, double lon) {
        sp(c).edit().putBoolean("origin_set", true).putString("origin_name", name)
                .putLong("origin_lat", Double.doubleToRawLongBits(lat))
                .putLong("origin_lon", Double.doubleToRawLongBits(lon)).apply();
    }

    public static void useCurrentOrigin(Context c) {
        sp(c).edit().putBoolean("origin_set", false).apply();
    }

    public static String originName(Context c) {
        return sp(c).getString("origin_name", "");
    }

    /** @return {lat, lon} of the set start place. */
    public static double[] originPoint(Context c) {
        SharedPreferences p = sp(c);
        return new double[]{Double.longBitsToDouble(p.getLong("origin_lat", Double.doubleToRawLongBits(DEFAULT_LAT))),
                Double.longBitsToDouble(p.getLong("origin_lon", Double.doubleToRawLongBits(DEFAULT_LON)))};
    }
}
