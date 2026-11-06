package com.locus.app.net;

import com.locus.app.graph.RainRules;

/**
 * Weather at one place: what it is like now, and hour by hour for the next hours - so the app can tell whether
 * it will be raining when you ARRIVE, not only when you leave. Pure Java, unit-tested.
 */
public final class WeatherForecast {
    public final double temperatureC;
    public final double rainNowMm;
    public final int codeNow;
    /** Hour ends (UTC epoch seconds), ascending; hourly rain is the amount in the hour BEFORE that time. */
    private final long[] hourEnd;
    private final double[] precip;
    private final int[] code;
    private final int[] probability;

    public WeatherForecast(double temperatureC, double rainNowMm, int codeNow,
                           long[] hourEnd, double[] precip, int[] code, int[] probability) {
        this.temperatureC = temperatureC;
        this.rainNowMm = rainNowMm;
        this.codeNow = codeNow;
        this.hourEnd = hourEnd;
        this.precip = precip;
        this.code = code;
        this.probability = probability;
    }

    /** 0 dry, 1 rain, 2 heavy rain, right now. */
    public int levelNow() {
        return RainRules.level(rainNowMm, codeNow);
    }

    private int indexAt(long epochMillis) {
        long t = epochMillis / 1000;
        for (int i = 0; i < hourEnd.length; i++) {
            if (hourEnd[i] >= t) return i;
        }
        return hourEnd.length - 1;
    }

    /** Rain level expected at a moment (the hour containing it); falls back to "now" without hourly data. */
    public int levelAt(long epochMillis) {
        if (hourEnd.length == 0) return levelNow();
        int i = indexAt(epochMillis);
        return RainRules.level(precip[i], code[i]);
    }

    /** Chance of rain 0..100 at a moment, or -1 when unknown. */
    public int probabilityAt(long epochMillis) {
        if (hourEnd.length == 0) return -1;
        return probability[indexAt(epochMillis)];
    }
}
