package com.locus.app.net;

import com.locus.app.BuildConfig;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * REAL road traffic, when a TomTom key is configured (TOMTOM_API_KEY in local.properties, free at
 * developer.tomtom.com). Without a key no free live-traffic service exists for OpenStreetMap, so the app falls
 * back to its time-of-day estimate and says "estimated".
 *
 * A few points between start and destination are measured: current speed against free-flow speed.
 */
public final class LiveTraffic {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(5);

    private LiveTraffic() {
    }

    public static boolean isConfigured() {
        return BuildConfig.TOMTOM_API_KEY != null && !BuildConfig.TOMTOM_API_KEY.isEmpty();
    }

    /** Up to 5 evenly spaced points on the straight line start..destination (start and destination included). */
    public static List<double[]> samplePoints(double sLat, double sLon, double dLat, double dLon) {
        List<double[]> pts = new ArrayList<>();
        int n = GeoRank.haversine(sLat, sLon, dLat, dLon) < 1500 ? 2 : 5;
        for (int i = 0; i < n; i++) {
            double f = n == 1 ? 0 : i / (double) (n - 1);
            pts.add(new double[]{sLat + (dLat - sLat) * f, sLon + (dLon - sLon) * f});
        }
        return pts;
    }

    /**
     * Blocking. @return {lat, lon, speedLoss} per point that could be measured (may be empty).
     */
    public static List<double[]> measure(List<double[]> points) throws Exception {
        if (!isConfigured()) return new ArrayList<>();
        List<Future<double[]>> jobs = new ArrayList<>();
        for (final double[] p : points) {
            jobs.add(POOL.submit((Callable<double[]>) () -> one(p[0], p[1])));
        }
        List<double[]> out = new ArrayList<>();
        Exception last = null;
        for (Future<double[]> f : jobs) {
            try {
                double[] r = f.get(10, TimeUnit.SECONDS);
                if (r != null) out.add(r);
            } catch (Exception e) {
                last = e;
            }
        }
        if (out.isEmpty() && last != null) throw last;
        return out;
    }

    private static double[] one(double lat, double lon) throws Exception {
        String url = String.format(Locale.US,
                "https://api.tomtom.com/traffic/services/4/flowSegmentData/absolute/10/json?point=%.5f,%.5f&unit=KMPH&key=%s",
                lat, lon, BuildConfig.TOMTOM_API_KEY);
        JSONObject f = new JSONObject(HttpClient.request("GET", url, null, null, 6000, 10000).body)
                .getJSONObject("flowSegmentData");
        double cur = f.optDouble("currentSpeed", 0);
        double free = f.optDouble("freeFlowSpeed", 0);
        if (f.optBoolean("roadClosure", false)) return new double[]{lat, lon, 1.0};
        if (free <= 0 || cur <= 0) return null;
        return new double[]{lat, lon, Math.max(0, Math.min(1, 1.0 - cur / free))};
    }

    /** Free-flow time / current time for the measured points: 1 = free, 2 = twice as slow. NaN without data. */
    public static double slowdown(List<double[]> samples) {
        if (samples == null || samples.isEmpty()) return Double.NaN;
        double sum = 0;
        for (double[] s : samples) sum += 1.0 / Math.max(0.2, 1.0 - s[2]);
        return sum / samples.size();
    }
}
