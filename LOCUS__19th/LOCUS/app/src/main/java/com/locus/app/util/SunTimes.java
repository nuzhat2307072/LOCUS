package com.locus.app.util;

import java.util.Calendar;
import java.util.TimeZone;

/** Is it dark outside? A small solar calculation (NOAA approximation, good to a few minutes) - no network needed. */
public final class SunTimes {
    private SunTimes() {
    }

    /** @return true between sunset and the next sunrise at this place. */
    public static boolean isNight(double lat, double lon, long nowMillis) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(nowMillis);
        int day = c.get(Calendar.DAY_OF_YEAR);
        double nowMin = c.get(Calendar.HOUR_OF_DAY) * 60.0 + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60.0;
        double g = 2 * Math.PI / 365.0 * (day - 1 + (c.get(Calendar.HOUR_OF_DAY) - 12) / 24.0);
        double eqTime = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
                - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g)
                + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double latR = Math.toRadians(lat);
        double cosHa = Math.cos(Math.toRadians(90.833)) / (Math.cos(latR) * Math.cos(decl))
                - Math.tan(latR) * Math.tan(decl);
        if (cosHa > 1) return true;   // sun never rises today
        if (cosHa < -1) return false; // sun never sets today
        double ha = Math.toDegrees(Math.acos(cosHa));
        double sunrise = mod(720 - 4 * (lon + ha) - eqTime, 1440); // minutes after 00:00 UTC
        double sunset = mod(720 - 4 * (lon - ha) - eqTime, 1440);
        if (sunrise < sunset) return nowMin < sunrise || nowMin >= sunset;
        return nowMin >= sunset && nowMin < sunrise; // the day crosses 00:00 UTC (far east or west)
    }

    private static double mod(double v, double m) {
        double r = v % m;
        return r < 0 ? r + m : r;
    }
}
