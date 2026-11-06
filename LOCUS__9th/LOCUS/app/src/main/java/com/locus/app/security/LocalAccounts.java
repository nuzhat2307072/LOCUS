package com.locus.app.security;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Accounts stored on this phone (name, e-mail, salted password hash). Used when Firebase is not
 * configured, and as the offline check for returning Firebase users. Blocking: PBKDF2 takes about
 * a tenth of a second, so call from a background thread.
 */
public final class LocalAccounts {
    private static final String FILE = "locus_accounts";
    private static final String KEY_CURRENT = "current";

    private LocalAccounts() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static String id(String email) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(email.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean exists(Context c, String email) {
        return sp(c).contains("h_" + id(email));
    }

    /** Creates or replaces the account's stored credentials. */
    public static void save(Context c, String name, String email, String password) {
        String k = id(email);
        sp(c).edit()
                .putString("h_" + k, PasswordHasher.hash(password))
                .putString("n_" + k, name == null ? "" : name)
                .putString("e_" + k, email.trim())
                .apply();
    }

    public static boolean verify(Context c, String email, String password) {
        return PasswordHasher.verify(password, sp(c).getString("h_" + id(email), null));
    }

    public static String name(Context c, String email) {
        return sp(c).getString("n_" + id(email), "");
    }

    /** Stable id for data ownership when there is no Firebase uid. */
    public static String uid(String email) {
        return "local-" + id(email);
    }

    public static String current(Context c) {
        return sp(c).getString(KEY_CURRENT, null);
    }

    public static void setCurrent(Context c, String email) {
        sp(c).edit().putString(KEY_CURRENT, email == null ? null : email.trim()).apply();
    }
}
