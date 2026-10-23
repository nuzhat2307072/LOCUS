package com.locus.app.net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Photon (komoot) - a forgiving OpenStreetMap search that ranks results around a position. It is the
 * third, independent source for Explore Places, so one overloaded service never empties the screen.
 */
public final class PhotonApi {
    private static final Set<String> PLACE_KEYS = new HashSet<>(Arrays.asList(
            "amenity", "shop", "tourism", "leisure", "office", "craft", "healthcare", "historic"));

    private PhotonApi() {
    }

    public static final class Found {
        public final String osmKey;   // "node123" - same format as the Overpass results
        public final String name;
        public final String label;
        public final String address;
        public final double lat;
        public final double lon;

        Found(String osmKey, String name, String label, String address, double lat, double lon) {
            this.osmKey = osmKey;
            this.name = name;
            this.label = label;
            this.address = address;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** Blocking. */
    public static List<Found> search(String query, double lat, double lon) throws Exception {
        String url = String.format(Locale.US,
                "https://photon.komoot.io/api/?q=%s&lat=%.5f&lon=%.5f&limit=40",
                URLEncoder.encode(query, "UTF-8"), lat, lon);
        String body = HttpClient.request("GET", url, null, null, 8000, 15000).body;
        JSONArray features = new JSONObject(body).optJSONArray("features");
        List<Found> out = new ArrayList<>();
        if (features == null) return out;
        for (int i = 0; i < features.length(); i++) {
            JSONObject f = features.getJSONObject(i);
            JSONObject props = f.optJSONObject("properties");
            JSONObject geo = f.optJSONObject("geometry");
            if (props == null || geo == null) continue;
            String key = props.optString("osm_key");
            String name = props.optString("name");
            if (!PLACE_KEYS.contains(key) || name.isEmpty()) continue;
            JSONArray c = geo.optJSONArray("coordinates");
            if (c == null || c.length() < 2) continue;
            String type = props.optString("osm_type");
            String osmType = "W".equals(type) ? "way" : "R".equals(type) ? "relation" : "node";
            StringBuilder addr = new StringBuilder();
            append(addr, props.optString("housenumber"));
            append(addr, props.optString("street"));
            append(addr, props.optString("district"));
            if (addr.length() == 0) append(addr, props.optString("city"));
            out.add(new Found(osmType + props.optLong("osm_id"), name,
                    QueryPlanner.humanize(props.optString("osm_value", key)), addr.toString(),
                    c.getDouble(1), c.getDouble(0)));
        }
        return out;
    }

    private static void append(StringBuilder sb, String part) {
        if (part == null || part.isEmpty()) return;
        if (sb.length() > 0) sb.append(", ");
        sb.append(part);
    }
}
