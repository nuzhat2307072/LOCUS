package com.locus.app.net;

import com.locus.app.graph.CostModel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Online routing (OSRM). Each travel mode asks the server built for it - walking uses footpaths and 5 km/h,
 * cycling uses bike roads, driving uses car roads - so the distance and the time are right for that mode.
 * (The public demo server project-osrm.org only knows cars; it is used as a last resort for driving.)
 */
public final class OsrmApi {
    private static final String FOSSGIS = "https://routing.openstreetmap.de/";
    private static final String DEMO = "https://router.project-osrm.org/route/v1/driving/";

    private OsrmApi() {
    }

    public static final class Route {
        public final List<double[]> points = new ArrayList<>(); // {lat, lon}
        public double distanceMeters;
        public double durationSeconds;
    }

    /** Server base URLs (ending with ".../route/v1/driving/") to try for a mode, best first. */
    static List<String> servers(CostModel.Mode mode) {
        List<String> s = new ArrayList<>();
        switch (mode) {
            case WALK:
                s.add(FOSSGIS + "routed-foot/route/v1/driving/");
                break;
            case CYCLE:
                s.add(FOSSGIS + "routed-bike/route/v1/driving/");
                break;
            default:
                s.add(FOSSGIS + "routed-car/route/v1/driving/");
                s.add(DEMO);
                break;
        }
        return s;
    }

    /** Blocking. The recommended route first, then up to two alternatives. */
    public static List<Route> routes(CostModel.Mode mode, double fromLat, double fromLon,
                                     double toLat, double toLon) throws Exception {
        return request(mode, fromLat, fromLon, toLat, toLon, true);
    }

    /** Blocking. Trip time with no traffic and no rain for this mode, or 0 when the server cannot say. */
    public static double freeFlowSeconds(CostModel.Mode mode, double fromLat, double fromLon,
                                         double toLat, double toLon) {
        try {
            List<Route> r = request(mode, fromLat, fromLon, toLat, toLon, false);
            return r.isEmpty() ? 0 : r.get(0).durationSeconds;
        } catch (Exception e) {
            return 0;
        }
    }

    private static List<Route> request(CostModel.Mode mode, double fromLat, double fromLon,
                                       double toLat, double toLon, boolean full) throws Exception {
        Exception last = null;
        for (String base : servers(mode)) {
            try {
                String url = String.format(Locale.US, "%s%.6f,%.6f;%.6f,%.6f?overview=%s&geometries=geojson&alternatives=%s",
                        base, fromLon, fromLat, toLon, toLat, full ? "full" : "false", full ? "3" : "false");
                JSONObject root = new JSONObject(HttpClient.request("GET", url, null, null, 8000, 20000).body);
                if (!"Ok".equals(root.optString("code"))) {
                    last = new Exception("No route found");
                    continue;
                }
                JSONArray arr = root.getJSONArray("routes");
                List<Route> out = new ArrayList<>();
                for (int k = 0; k < arr.length() && k < 3; k++) {
                    JSONObject r = arr.getJSONObject(k);
                    Route route = new Route();
                    route.distanceMeters = r.getDouble("distance");
                    route.durationSeconds = r.getDouble("duration");
                    JSONObject geo = r.optJSONObject("geometry");
                    if (geo != null) {
                        JSONArray coords = geo.getJSONArray("coordinates");
                        for (int i = 0; i < coords.length(); i++) {
                            JSONArray c = coords.getJSONArray(i);
                            route.points.add(new double[]{c.getDouble(1), c.getDouble(0)});
                        }
                    }
                    out.add(route);
                }
                if (!out.isEmpty()) return out;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("No route found");
    }
}
