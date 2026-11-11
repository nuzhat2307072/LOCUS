package com.locus.app.net;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Rain at the time of ARRIVAL, not only now. */
public class WeatherForecastTest {
    private static WeatherForecast sample() {
        long h = 3600L; // hour ends (UTC seconds): 10:00, 11:00, 12:00, 13:00
        long base = 1_700_000_000L - (1_700_000_000L % h);
        long[] ends = {base + h, base + 2 * h, base + 3 * h, base + 4 * h};
        double[] mm = {0.0, 0.0, 3.2, 0.4};
        int[] code = {1, 2, 65, 61};
        int[] prob = {5, 10, 90, 60};
        return new WeatherForecast(30.0, 0.0, 1, ends, mm, code, prob);
    }

    @Test
    public void dryNowButRainLater() {
        WeatherForecast f = sample();
        long base = (1_700_000_000L - (1_700_000_000L % 3600L)) * 1000;
        assertEquals(0, f.levelNow());
        assertEquals(0, f.levelAt(base + 30 * 60 * 1000));            // within the first hour
        assertEquals(2, f.levelAt(base + 2 * 3600 * 1000 + 600_000)); // heavy rain in the third hour
        assertEquals(90, f.probabilityAt(base + 2 * 3600 * 1000 + 600_000));
        assertEquals(1, f.levelAt(base + 3 * 3600 * 1000 + 600_000)); // light rain after that
    }

    @Test
    public void beyondTheForecastTheLastHourIsUsed() {
        WeatherForecast f = sample();
        assertEquals(1, f.levelAt(Long.MAX_VALUE / 2));
    }

    @Test
    public void withoutHourlyDataTheCurrentWeatherIsUsed() {
        WeatherForecast f = new WeatherForecast(28.0, 3.0, 65, new long[0], new double[0], new int[0], new int[0]);
        assertEquals(2, f.levelAt(System.currentTimeMillis()));
        assertEquals(-1, f.probabilityAt(0));
    }
}
