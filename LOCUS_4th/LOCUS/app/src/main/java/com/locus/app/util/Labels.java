package com.locus.app.util;

import android.content.Context;

import com.locus.app.R;

import java.util.Locale;

/** Turns stored English labels into text in the app language. */
public final class Labels {
    private Labels() {
    }

    /**
     * "Fast food" -> the string resource cat_fast_food (translated). Labels that have no translation
     * (rare cuisines, odd shops) are shown as OpenStreetMap names them.
     */
    public static String category(Context c, String label) {
        if (label == null || label.trim().isEmpty()) return c.getString(R.string.place_generic);
        String key = "cat_" + label.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        int id = key.matches("cat_[a-z_]+")
                ? c.getResources().getIdentifier(key, "string", c.getPackageName()) : 0;
        return id != 0 ? c.getString(id) : label;
    }

    /** Sentence for a {@link com.locus.app.security.PasswordRules#check} code. */
    public static String passwordProblem(Context c, int code) {
        switch (code) {
            case com.locus.app.security.PasswordRules.TOO_SHORT:
                return c.getString(R.string.pw_too_short, com.locus.app.security.PasswordRules.MIN_LENGTH);
            case com.locus.app.security.PasswordRules.NEEDS_MIX:
                return c.getString(R.string.pw_mix);
            case com.locus.app.security.PasswordRules.TOO_COMMON:
                return c.getString(R.string.pw_common);
            case com.locus.app.security.PasswordRules.HAS_EMAIL:
                return c.getString(R.string.pw_has_email);
            default:
                return c.getString(R.string.pw_repeat);
        }
    }

    /** "Strength: Good" from PasswordRules.strength (0..4). */
    public static String strengthText(Context c, int strength) {
        int word = strength <= 1 ? R.string.strength_weak : strength == 2 ? R.string.strength_fair
                : strength == 3 ? R.string.strength_good : R.string.strength_strong;
        return c.getString(R.string.password_strength, c.getString(word));
    }

    /** "Walk 5 min   Bike 2 min   Car 1 min" in the app language. */
    public static String travelTimes(Context c, double meters) {
        return c.getString(R.string.times_line,
                GeoUtils.durationText(c, GeoUtils.walkSeconds(meters)),
                GeoUtils.durationText(c, GeoUtils.bikeSeconds(meters)),
                GeoUtils.durationText(c, GeoUtils.driveSeconds(meters)));
    }
}
