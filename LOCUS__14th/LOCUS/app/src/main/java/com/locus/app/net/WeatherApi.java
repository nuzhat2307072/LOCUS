package com.locus.app.net;

import com.locus.app.graph.RainRules;

import org.json.JSONArray;
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

    /**
     * Blocking. Weather now AND hour by hour for the next 12 hours at a point (Open-Meteo, free, no key), so the
     * rain at the time of arrival can be taken into account.
     */
    public static WeatherForecast forecast(double lat, double lon) throws Exception {
        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=precipitation,rain,weather_code,temperature_2m"
                        + "&hourly=precipitation,precipitation_probability,weather_code"
                        + "&forecast_hours=12&timeformat=unixtime&timezone=auto", lat, lon);
        JSONObject root = new JSONObject(HttpClient.request("GET", url, null, null, 8000, 12000).body);
        JSONObject cur = root.getJSONObject("current");
        double now = Math.max(cur.optDouble("precipitation", 0), cur.optDouble("rain", 0));
        JSONObject h = root.optJSONObject("hourly");
        int n = 0;
        long[] ends = new long[0];
        double[] precip = new double[0];
        int[] code = new int[0];
        int[] prob = new int[0];
        if (h != null && h.optJSONArray("time") != null) {
            JSONArray t = h.getJSONArray("time");
            JSONArray p = h.optJSONArray("precipitation");
            JSONArray c = h.optJSONArray("weather_code");
            JSONArray pp = h.optJSONArray("precipitation_probability");
            n = t.length();
            ends = new long[n];
            precip = new double[n];
            code = new int[n];
            prob = new int[n];
            for (int i = 0; i < n; i++) {
                ends[i] = t.getLong(i);
                precip[i] = p == null ? 0 : p.optDouble(i, 0);
                code[i] = c == null ? -1 : c.optInt(i, -1);
                prob[i] = pp == null || pp.isNull(i) ? -1 : pp.optInt(i, -1);
            }
        }
        return new WeatherForecast(cur.optDouble("temperature_2m", Double.NaN), now,
                cur.optInt("weather_code", -1), ends, precip, code, prob);
    }
}
