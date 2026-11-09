package com.locus.app.data.firebase;

import android.content.Context;
import android.content.SharedPreferences;

import com.locus.app.BuildConfig;

/**
 * The three EmailJS values used to send the "password changed" e-mail. They can be typed into the app
 * (Connect Firebase screen) - no rebuild needed - and fall back to the values built in from local.properties.
 */
public final class EmailConfig {
    private static final String FILE = "locus_email_config";

    private EmailConfig() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String serviceId(Context c) {
        return sp(c).getString("service", BuildConfig.EMAILJS_SERVICE_ID);
    }

    public static String templateId(Context c) {
        return sp(c).getString("template", BuildConfig.EMAILJS_TEMPLATE_ID);
    }

    public static String publicKey(Context c) {
        return sp(c).getString("key", BuildConfig.EMAILJS_PUBLIC_KEY);
    }

    public static void save(Context c, String service, String template, String key) {
        sp(c).edit().putString("service", service.trim()).putString("template", template.trim())
                .putString("key", key.trim()).apply();
    }

    public static boolean isConfigured(Context c) {
        return !serviceId(c).isEmpty() && !templateId(c).isEmpty() && !publicKey(c).isEmpty();
    }
}
