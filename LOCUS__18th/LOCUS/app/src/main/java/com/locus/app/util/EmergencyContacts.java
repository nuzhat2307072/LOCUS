package com.locus.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The people an SOS goes to (kept on this phone only). */
public final class EmergencyContacts {
    public static final int MAX = 5;

    public static final class Contact {
        public final String name;
        public final String phone;

        public Contact(String name, String phone) {
            this.name = name;
            this.phone = phone;
        }
    }

    private EmergencyContacts() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("locus_emergency", Context.MODE_PRIVATE);
    }

    public static List<Contact> load(Context c) {
        List<Contact> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("contacts", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Contact(o.optString("name"), o.optString("phone")));
            }
        } catch (Exception ignored) {
            // damaged data: start with an empty list
        }
        return out;
    }

    private static void save(Context c, List<Contact> list) {
        JSONArray a = new JSONArray();
        try {
            for (Contact k : list) a.put(new JSONObject().put("name", k.name).put("phone", k.phone));
        } catch (Exception ignored) {
            return;
        }
        sp(c).edit().putString("contacts", a.toString()).apply();
    }

    /** Keeps digits and a leading plus only. */
    public static String cleanPhone(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (Character.isDigit(ch) || (ch == '+' && sb.length() == 0)) sb.append(ch);
        }
        return sb.toString();
    }

    public static boolean validPhone(String phone) {
        int digits = 0;
        for (int i = 0; i < phone.length(); i++) if (Character.isDigit(phone.charAt(i))) digits++;
        return digits >= 5 && digits <= 15;
    }

    /** @return false when the number is not valid, the list is full, or the number is already there. */
    public static boolean add(Context c, String name, String phone) {
        String p = cleanPhone(phone);
        List<Contact> list = load(c);
        if (!validPhone(p) || list.size() >= MAX) return false;
        for (Contact k : list) if (k.phone.equals(p)) return false;
        list.add(new Contact(name == null || name.trim().isEmpty() ? p : name.trim(), p));
        save(c, list);
        return true;
    }

    public static void remove(Context c, int index) {
        List<Contact> list = load(c);
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            save(c, list);
        }
    }

    /** Bangladesh national emergency number: always offered first (police, fire, ambulance). */
    public static final String BANGLADESH_EMERGENCY = "999";

    /**
     * The emergency number of the phone's own country when it is not 999 (for example 911 or 112 when travelling),
     * otherwise null: the person then sees only 999.
     */
    public static String localNumberOrNull() {
        String n = nationalNumber();
        return BANGLADESH_EMERGENCY.equals(n) ? null : n;
    }

    /** The national emergency number for the phone's country (Bangladesh 999, India 112 ...). */
    public static String nationalNumber() {
        String country = Locale.getDefault().getCountry();
        switch (country) {
            case "BD":
            case "GB":
                return "999";
            case "US":
            case "CA":
                return "911";
            default:
                return "112";
        }
    }
}
