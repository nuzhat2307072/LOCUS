package com.locus.app.graph;

/** Turns weather-service readings into a rain level for the cost model (pure Java, unit-tested). */
public final class RainRules {
    private RainRules() {
    }

    /**
     * @param precipitationMm rain in the last hour (mm), negative if unknown
     * @param weatherCode     WMO weather code from the weather service, negative if unknown
     * @return 0 = dry, 1 = rain, 2 = heavy rain or thunderstorm
     */
    public static int level(double precipitationMm, int weatherCode) {
        int level = 0;
        if (precipitationMm >= 2.5) level = 2;
        else if (precipitationMm >= 0.1) level = 1;
        // WMO codes: 51-57 drizzle, 61-67 rain, 80-82 showers, 95-99 thunderstorm
        if ((weatherCode >= 51 && weatherCode <= 57) || (weatherCode >= 61 && weatherCode <= 63)
                || weatherCode == 80 || weatherCode == 81) {
            level = Math.max(level, 1);
        }
        if (weatherCode == 65 || weatherCode == 66 || weatherCode == 67 || weatherCode == 82
                || (weatherCode >= 95 && weatherCode <= 99)) {
            level = Math.max(level, 2);
        }
        return level;
    }
}
