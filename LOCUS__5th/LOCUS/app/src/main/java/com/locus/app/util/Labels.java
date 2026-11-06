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

    /** Roads are longer than the straight line to a place; real routes are about this much further. */
    private static final double DETOUR = 1.3;

    /** "Walk 5 min   Bike 2 min   Car 1 min" in the app language (straight line x 1.3 for the road). */
    public static String travelTimes(Context c, double meters) {
        double road = meters * DETOUR;
        return c.getString(R.string.times_line,
                GeoUtils.durationText(c, GeoUtils.walkSeconds(road)),
                GeoUtils.durationText(c, GeoUtils.bikeSeconds(road)),
                GeoUtils.durationText(c, GeoUtils.driveSeconds(road)));
    }

    /** Whether a place is open right now, as text; state UNKNOWN (text null) when the hours are missing or unusual. */
    public static final class Hours {
        public final com.locus.app.net.OpeningHours.State state;
        public final String text;

        Hours(com.locus.app.net.OpeningHours.State state, String text) {
            this.state = state;
            this.text = text;
        }
    }

    public static Hours hours(Context c, String openingHours) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        int day = com.locus.app.net.OpeningHours.dayIndex(now.get(java.util.Calendar.DAY_OF_WEEK));
        int minute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
        com.locus.app.net.OpeningHours.Status s = com.locus.app.net.OpeningHours.check(openingHours, day, minute);
        switch (s.state) {
            case OPEN:
                return new Hours(s.state, c.getString(s.allDay ? R.string.hours_open_24
                        : R.string.hours_open_until, com.locus.app.net.OpeningHours.hhmm(s.changeAtMinute)));
            case CLOSED:
                return new Hours(s.state, s.changeAtMinute >= 0
                        ? c.getString(R.string.hours_closed_opens, com.locus.app.net.OpeningHours.hhmm(s.changeAtMinute))
                        : c.getString(R.string.hours_closed));
            default:
                return new Hours(s.state, null);
        }
    }
}
