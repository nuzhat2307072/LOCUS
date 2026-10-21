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

    public static void saveAccount(Context c, String email, String password, String name) {
        String keyEmail = email.toLowerCase(java.util.Locale.US).trim();
        sp(c).edit()
                .putString("acc_pwd_" + keyEmail, password)
                .putString("acc_name_" + keyEmail, name)
                .apply();
    }

    public static boolean hasAccount(Context c, String email) {
        String keyEmail = email.toLowerCase(java.util.Locale.US).trim();
        return sp(c).contains("acc_pwd_" + keyEmail);
    }

    public static String getAccountPassword(Context c, String email) {
        String keyEmail = email.toLowerCase(java.util.Locale.US).trim();
        return sp(c).getString("acc_pwd_" + keyEmail, null);
    }

    public static String getAccountName(Context c, String email) {
        String keyEmail = email.toLowerCase(java.util.Locale.US).trim();
        return sp(c).getString("acc_name_" + keyEmail, "User");
    }

    public static void setUserSession(Context c, String email, String name, String uid) {
        setGuest(c, false);
        sp(c).edit()
                .putString("session_email", email)
                .putString("session_name", name)
                .putString("session_uid", uid)
                .apply();
    }

    public static String getSessionEmail(Context c) {
        return sp(c).getString("session_email", null);
    }

    public static String getSessionName(Context c) {
        return sp(c).getString("session_name", "User");
    }

    public static String getSessionUid(Context c) {
        return sp(c).getString("session_uid", null);
    }

    public static void clearUserSession(Context c) {
        sp(c).edit()
                .remove("session_email")
                .remove("session_name")
                .remove("session_uid")
                .putBoolean(KEY_GUEST, false)
                .apply();
    }

    /** @return {lat, lon} - last GPS fix, or the default city centre. */
    public static double[] lastLocation(Context c) {
        SharedPreferences p = sp(c);
        if (!p.contains(KEY_LAT)) return new double[]{DEFAULT_LAT, DEFAULT_LON};
        return new double[]{p.getFloat(KEY_LAT, (float) DEFAULT_LAT), p.getFloat(KEY_LON, (float) DEFAULT_LON)};
    }
}
