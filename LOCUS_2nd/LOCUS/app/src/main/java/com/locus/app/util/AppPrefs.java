package com.locus.app.util;

import android.content.Context;
import android.content.SharedPreferences;

/** Small SharedPreferences wrapper (guest flag + last known location). */
public final class AppPrefs {
    private static final String FILE = "locus_prefs";
    private static final String KEY_GUEST = "guest";
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

    public static boolean isGuest(Context c) {
        return sp(c).getBoolean(KEY_GUEST, false);
    }

    public static void setGuest(Context c, boolean guest) {
        sp(c).edit().putBoolean(KEY_GUEST, guest).apply();
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
