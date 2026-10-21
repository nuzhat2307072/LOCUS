package com.locus.app.net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** OSRM public demo server (driving profile). Used as a quick online comparison route. */
public final class OsrmApi {
    private OsrmApi() {
    }

    public static final class Route {
        public final List<double[]> points = new ArrayList<>(); // {lat, lon}
        public double distanceMeters;
        public double durationSeconds;
    }

    /** Blocking. */
    public static Route route(double fromLat, double fromLon, double toLat, double toLon) throws Exception {
        String url = String.format(Locale.US,
                "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
                fromLon, fromLat, toLon, toLat);
        JSONObject root = new JSONObject(HttpClient.get(url).body);
        if (!"Ok".equals(root.optString("code"))) {
            throw new Exception("OSRM could not find a route");
        }
        JSONObject r = root.getJSONArray("routes").getJSONObject(0);
        Route route = new Route();
        route.distanceMeters = r.getDouble("distance");
        route.durationSeconds = r.getDouble("duration");
        JSONArray coords = r.getJSONObject("geometry").getJSONArray("coordinates");
        for (int i = 0; i < coords.length(); i++) {
            JSONArray c = coords.getJSONArray(i);
            route.points.add(new double[]{c.getDouble(1), c.getDouble(0)});
        }
        return route;
    }
}
