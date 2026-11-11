package com.locus.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** On this phone: the live-location share I started, and the shares other people invited me to. */
public final class ShareState {
    private ShareState() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("locus_share", Context.MODE_PRIVATE);
    }

    // ---- my own share ------------------------------------------------------------------------------

    public static boolean active(Context c) {
        return !code(c).isEmpty();
    }

    public static String code(Context c) {
        return sp(c).getString("code", "");
    }

    /** Wall-clock end time; 0 when the share lasts until I arrive. */
    public static long expiresAt(Context c) {
        return sp(c).getLong("expires", 0L);
    }

    public static boolean untilArrive(Context c) {
        return sp(c).getBoolean("until_arrive", false);
    }

    public static double[] dest(Context c) {
        return new double[]{Double.longBitsToDouble(sp(c).getLong("dest_lat", 0)),
                Double.longBitsToDouble(sp(c).getLong("dest_lon", 0))};
    }

    public static void start(Context c, String code, long expiresAt, boolean untilArrive, double destLat, double destLon) {
        sp(c).edit().putString("code", code).putLong("expires", expiresAt).putBoolean("until_arrive", untilArrive)
                .putLong("dest_lat", Double.doubleToLongBits(destLat)).putLong("dest_lon", Double.doubleToLongBits(destLon))
                .apply();
    }

    public static void clear(Context c) {
        sp(c).edit().remove("code").remove("expires").remove("until_arrive").remove("dest_lat").remove("dest_lon").apply();
    }

    // ---- shares I was invited to: [code, owner name] ---------------------------------------------------

    public static List<String[]> joined(Context c) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("joined", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new String[]{o.optString("code"), o.optString("name")});
            }
        } catch (Exception ignored) {
            // damaged data: empty list
        }
        return out;
    }

    private static void saveJoined(Context c, List<String[]> list) {
        JSONArray a = new JSONArray();
        try {
            for (String[] s : list) a.put(new JSONObject().put("code", s[0]).put("name", s[1]));
        } catch (Exception ignored) {
            return;
        }
        sp(c).edit().putString("joined", a.toString()).apply();
    }

    public static void addJoined(Context c, String code, String name) {
        List<String[]> list = joined(c);
        for (String[] s : list) {
            if (s[0].equals(code)) {
                s[1] = name;
                saveJoined(c, list);
                return;
            }
        }
        list.add(new String[]{code, name});
        saveJoined(c, list);
    }

    public static void removeJoined(Context c, String code) {
        List<String[]> list = joined(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i)[0].equals(code)) list.remove(i);
        saveJoined(c, list);
    }
}
