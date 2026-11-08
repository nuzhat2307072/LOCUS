package com.locus.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/** Sunset detection for the automatic dark map. */
public class SunTimesTest {
    private static long utc(int y, int m, int d, int h) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, m - 1, d, h, 0, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void khulnaIsDarkAtMidnightAndLightAtNoon() {
        // Khulna is UTC+6: 06:00 UTC is noon, 18:00 UTC is midnight
        assertFalse(SunTimes.isNight(22.80, 89.54, utc(2026, 11, 7, 6)));
        assertTrue(SunTimes.isNight(22.80, 89.54, utc(2026, 11, 7, 18)));
        assertTrue(SunTimes.isNight(22.80, 89.54, utc(2026, 6, 21, 20)));
    }

    @Test
    public void polarDayAndPolarNight() {
        assertFalse(SunTimes.isNight(78.2, 15.6, utc(2026, 6, 21, 0)));  // Svalbard midsummer
        assertTrue(SunTimes.isNight(78.2, 15.6, utc(2026, 12, 21, 12))); // Svalbard midwinter
    }
}
