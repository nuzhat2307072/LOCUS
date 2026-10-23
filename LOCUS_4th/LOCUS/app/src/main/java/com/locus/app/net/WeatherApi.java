package com.locus.app.net;

import com.locus.app.graph.RainRules;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Current weather from Open-Meteo (free, no API key). The app uses it to decide by itself whether it
 * is raining; the user never has to say so.
 */
public final class WeatherApi {
    private WeatherApi() {
    }

    public static final class Weather {
        public final int rainLevel;          // 0 dry, 1 rain, 2 heavy rain
        public final double temperatureC;

        Weather(int rainLevel, double temperatureC) {
            this.rainLevel = rainLevel;
            this.temperatureC = temperatureC;
        }
    }

    /** Blocking. */
    public static Weather current(double lat, double lon) throws Exception {
        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=precipitation,rain,weather_code,temperature_2m&timezone=auto", lat, lon);
        JSONObject cur = new JSONObject(HttpClient.get(url).body).getJSONObject("current");
        double precip = cur.optDouble("precipitation", -1);
        double rain = cur.optDouble("rain", -1);
        int code = cur.optInt("weather_code", -1);
        return new Weather(RainRules.level(Math.max(precip, rain), code), cur.optDouble("temperature_2m", Double.NaN));
    }
}
