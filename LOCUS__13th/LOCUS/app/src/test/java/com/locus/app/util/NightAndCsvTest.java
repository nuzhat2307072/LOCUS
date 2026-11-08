package com.locus.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.locus.app.model.Trip;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** Sunset detection for the automatic dark map, and the trip CSV / path encoding. */
public class NightAndCsvTest {
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

    @Test
    public void csvQuotesCommasAndQuotes() {
        assertEquals("plain", TripCsv.field("plain"));
        assertEquals("\"a,b\"", TripCsv.field("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", TripCsv.field("say \"hi\""));
        Trip t = new Trip();
        t.startedAt = 0;
        t.startName = "Home";
        t.destName = "Khulna, Sonadanga";
        t.mode = "WALK";
        t.meters = 1500;
        t.seconds = 1080;
        t.completed = true;
        List<Trip> list = new ArrayList<>();
        list.add(t);
        String[] lines = TripCsv.build(list).split("\n");
        assertEquals(2, lines.length);
        assertEquals("date,start,destination,mode,distance_km,duration_min,completed", lines[0]);
        assertTrue(lines[1].endsWith(",Home,\"Khulna, Sonadanga\",WALK,1.50,18.0,yes"));
    }

    @Test
    public void pathEncodingKeepsEndsAndLimitsPoints() {
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < 1000; i++) pts.add(new double[]{22.0 + i * 0.0001, 89.0 + i * 0.0001});
        String s = Trip.encodePath(pts, 300);
        List<double[]> back = Trip.decodePath(s);
        assertTrue(back.size() <= 302 && back.size() > 200);
        assertEquals(22.0, back.get(0)[0], 1e-5);
        assertEquals(22.0999, back.get(back.size() - 1)[0], 1e-5);
        assertEquals(0, Trip.decodePath("").size());
        assertEquals(1, Trip.decodePath("1.0,2.0;bad;3").size());
    }
}
