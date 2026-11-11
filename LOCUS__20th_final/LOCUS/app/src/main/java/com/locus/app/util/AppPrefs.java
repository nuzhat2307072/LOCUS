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

    /** 0 = follow the system, 1 = light, 2 = dark. */
    public static int themeMode(Context c) {
        return sp(c).getInt("theme_mode", 0);
    }

    public static void setThemeMode(Context c, int mode) {
        sp(c).edit().putInt("theme_mode", mode).apply();
    }

    /** Dark map tiles after sunset. Off by default: the map background is light unless the person turns this on. */
    public static boolean autoDarkMap(Context c) {
        return sp(c).getBoolean("auto_dark_map_v2", false);
    }

    public static void setAutoDarkMap(Context c, boolean on) {
        sp(c).edit().putBoolean("auto_dark_map_v2", on).apply();
    }

    /** E-mails known to have an account in Firebase (so a password reset e-mail can really arrive). */
    public static void markCloudAccount(Context c, String email) {
        if (email == null) return;
        java.util.Set<String> s = new java.util.HashSet<>(sp(c).getStringSet("cloud_accounts", new java.util.HashSet<>()));
        s.add(email.trim().toLowerCase(java.util.Locale.ROOT));
        sp(c).edit().putStringSet("cloud_accounts", s).apply();
    }

    /** Community posts and SOS are also shown in my family-and-friends groups (on by default). */
    public static boolean shareWithGroups(Context c) {
        return sp(c).getBoolean("share_with_groups", true);
    }

    public static void setShareWithGroups(Context c, boolean on) {
        sp(c).edit().putBoolean("share_with_groups", on).apply();
    }

    /** When a password reset link was last asked for this e-mail (milliseconds), 0 if never. */
    public static long lastResetMail(Context c, String email) {
        return sp(c).getLong("reset_mail_" + email.trim().toLowerCase(java.util.Locale.ROOT), 0);
    }

    public static void setLastResetMail(Context c, String email, long at) {
        sp(c).edit().putLong("reset_mail_" + email.trim().toLowerCase(java.util.Locale.ROOT), at).apply();
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

    /** Switch for one kind of notification (see NotifyRules: chat, hazard, community); all on by default. */
    public static boolean notifyOn(Context c, int kind) {
        return sp(c).getBoolean("notify_" + kind, true);
    }

    public static void setNotifyOn(Context c, int kind, boolean on) {
        sp(c).edit().putBoolean("notify_" + kind, on).apply();
    }

    public static boolean quietEnabled(Context c) {
        return sp(c).getBoolean("quiet_on", false);
    }

    public static void setQuietEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("quiet_on", on).apply();
    }

    public static int quietFrom(Context c) {
        return sp(c).getInt("quiet_from", 22);
    }

    public static int quietTo(Context c) {
        return sp(c).getInt("quiet_to", 7);
    }

    public static void setQuietHours(Context c, int from, int to) {
        sp(c).edit().putInt("quiet_from", from).putInt("quiet_to", to).apply();
    }

    /** Safest-route mode: reported hazards weigh more than distance when the route is chosen. */
    public static boolean safestRoute(Context c) {
        return sp(c).getBoolean("safest_route", false);
    }

    public static void setSafestRoute(Context c, boolean on) {
        sp(c).edit().putBoolean("safest_route", on).apply();
    }

    /** Live alerts with the app closed (a small ongoing notification); on by default. */
    public static boolean liveAlerts(Context c) {
        return sp(c).getBoolean("live_alerts", true);
    }

    public static void setLiveAlerts(Context c, boolean on) {
        sp(c).edit().putBoolean("live_alerts", on).apply();
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

    // ---- the destination of the latest route (used by "share until I arrive") ----------------------

    public static void setLastDestination(Context c, double lat, double lon, String name) {
        sp(c).edit().putString("dest_lat", String.valueOf(lat)).putString("dest_lon", String.valueOf(lon))
                .putString("dest_name", name == null ? "" : name).apply();
    }

    public static boolean hasLastDestination(Context c) {
        return sp(c).contains("dest_lat");
    }

    /** @return {lat, lon} of the last destination (0, 0 when there is none). */
    public static double[] lastDestination(Context c) {
        try {
            return new double[]{Double.parseDouble(sp(c).getString("dest_lat", "0")),
                    Double.parseDouble(sp(c).getString("dest_lon", "0"))};
        } catch (NumberFormatException e) {
            return new double[]{0, 0};
        }
    }

    public static String lastDestinationName(Context c) {
        return sp(c).getString("dest_name", "");
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

    // ---- upgrades: voice, shake SOS, recent destinations ----------------------------------------

    /** Spoken turn-by-turn directions (default on). */
    public static boolean voiceGuidance(Context c) {
        return sp(c).getBoolean("voice_guidance", true);
    }

    public static void setVoiceGuidance(Context c, boolean on) {
        sp(c).edit().putBoolean("voice_guidance", on).apply();
    }

    /** Shake the phone three times to start SOS (default off, so nobody triggers it by accident). */
    public static boolean sosShake(Context c) {
        return sp(c).getBoolean("sos_shake", false);
    }

    public static void setSosShake(Context c, boolean on) {
        sp(c).edit().putBoolean("sos_shake", on).apply();
    }

    /** Last destinations, newest first: lines "name|lat|lon" (at most 8). */
    public static java.util.List<String> recentDestinations(Context c) {
        String raw = sp(c).getString("recent_destinations", "");
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String line : raw.split("\n")) {
            if (!line.trim().isEmpty()) out.add(line);
        }
        return out;
    }

    public static void setRecentDestinations(Context c, java.util.List<String> lines) {
        sp(c).edit().putString("recent_destinations", android.text.TextUtils.join("\n", lines)).apply();
    }
}
