package com.locus.app.net;

import com.locus.app.graph.Graph;
import com.locus.app.model.Place;
import com.locus.app.util.GeoUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** OpenStreetMap Overpass API: places matching a planned query (JSON) and the road network (streamed JSON -> Graph). */
public final class OverpassApi {
    private static final String[] ENDPOINTS = {
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    };
    /** A second server is tried if the first has not answered after this long (they are often overloaded). */
    private static final long HEDGE_AFTER_MS = 4000;
    private static final long GIVE_UP_AFTER_MS = 32000;
    private static final ExecutorService POOL = Executors.newCachedThreadPool();
    private static final String FORM = "application/x-www-form-urlencoded; charset=utf-8";

    private OverpassApi() {
    }

    /**
     * Blocking. Runs the planner's filters inside a bounding box around (lat, lon) - bounding boxes are far
     * cheaper for the public servers than "around" - then keeps only places within radiusMeters.
     */
    public static List<Place> searchPlaces(String searchKey, List<String> filters, double lat, double lon,
                                           int radiusMeters) throws IOException, JSONException {
        double dLat = radiusMeters / 111320.0;
        double dLon = radiusMeters / (111320.0 * Math.max(0.2, Math.cos(Math.toRadians(lat))));
        String bbox = String.format(Locale.US, "(%.6f,%.6f,%.6f,%.6f)",
                lat - dLat, lon - dLon, lat + dLat, lon + dLon);

        StringBuilder q = new StringBuilder("[out:json][timeout:20];(");
        for (String f : filters) q.append("nwr").append(f).append(bbox).append(';');
        q.append(");out center 200;");

        String body = postHedged(q.toString());
        JSONArray elements = new JSONObject(body).getJSONArray("elements");
        List<Place> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        final String lang = Locale.getDefault().getLanguage();
        for (int i = 0; i < elements.length(); i++) {
            JSONObject e = elements.getJSONObject(i);
            double pLat, pLon;
            if (e.has("lat")) {
                pLat = e.getDouble("lat");
                pLon = e.getDouble("lon");
            } else if (e.has("center")) {
                pLat = e.getJSONObject("center").getDouble("lat");
                pLon = e.getJSONObject("center").getDouble("lon");
            } else {
                continue;
            }
            double dist = GeoUtils.distance(lat, lon, pLat, pLon);
            if (dist > radiusMeters) continue;

            Map<String, String> tags = new HashMap<>();
            JSONObject t = e.optJSONObject("tags");
            if (t != null) {
                Iterator<String> keys = t.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    tags.put(k, t.optString(k));
                }
            }

            Place p = new Place();
            p.id = searchKey + "#" + e.optString("type", "node") + e.optLong("id");
            p.searchKey = searchKey;
            p.category = QueryPlanner.labelFor(tags);
            // the name in the app language first (name:bn, name:hi), then the local name
            String name = firstNonEmpty(lang.equals("en") ? null : tags.get("name:" + lang), tags.get("name"),
                    tags.get("name:en"), tags.get("brand"));
            p.name = name.isEmpty() ? p.category : name;
            p.lat = pLat;
            p.lon = pLon;
            p.phone = firstNonEmpty(tags.get("phone"), tags.get("contact:phone"), tags.get("contact:mobile"));
            p.openingHours = firstNonEmpty(tags.get("opening_hours"));
            p.address = buildAddress(tags);
            p.distanceMeters = dist;
            p.fetchedAt = now;
            out.add(p);
        }
        return out;
    }

    /** Blocking. Downloads all drivable/walkable ways in the box and builds the routing graph. */
    public static Graph fetchRoadNetwork(double south, double west, double north, double east) throws Exception {
        String q = String.format(Locale.US,
                "[out:json][timeout:60];way[\"highway\"][\"highway\"!~\"proposed|construction|raceway|bus_guideway|"
                        + "elevator|corridor|platform|escape|service|track|bridleway|via_ferrata\"](%.6f,%.6f,%.6f,%.6f);out geom;",
                south, west, north, east);
        String data = "data=" + URLEncoder.encode(q, "UTF-8");
        Exception last = null;
        for (String endpoint : ENDPOINTS) {
            try {
                return HttpClient.stream("POST", endpoint, data, FORM, RoadNetworkParser::parse);
            } catch (Exception e) {
                last = e;
            }
        }
        throw last == null ? new IOException("No Overpass server available") : last;
    }

    /**
     * Asks the servers one after another but without waiting for a slow one: the next server is
     * started when the previous failed, or has been silent for a few seconds. The first good answer wins.
     */
    private static String postHedged(String query) throws IOException {
        final String data = "data=" + URLEncoder.encode(query, "UTF-8");
        ExecutorCompletionService<String> done = new ExecutorCompletionService<>(POOL);
        int launched = 0;
        int failed = 0;
        IOException last = null;
        long deadline = System.currentTimeMillis() + GIVE_UP_AFTER_MS;
        launch(done, ENDPOINTS[launched++], data);
        while (failed < launched || launched < ENDPOINTS.length) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            Future<String> f;
            try {
                f = done.poll(launched < ENDPOINTS.length ? Math.min(HEDGE_AFTER_MS, left) : left,
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Search was interrupted");
            }
            if (f == null) { // silent so far: start the next server alongside
                if (launched < ENDPOINTS.length) launch(done, ENDPOINTS[launched++], data);
                continue;
            }
            try {
                return f.get();
            } catch (ExecutionException e) {
                failed++;
                last = e.getCause() instanceof IOException ? (IOException) e.getCause()
                        : new IOException(String.valueOf(e.getCause()));
                if (launched < ENDPOINTS.length) launch(done, ENDPOINTS[launched++], data);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Search was interrupted");
            }
        }
        throw last == null ? new IOException("Server returned HTTP 504") : last;
    }

    private static void launch(ExecutorCompletionService<String> done, final String endpoint, final String data) {
        done.submit(() -> {
            String body = HttpClient.request("POST", endpoint, data, FORM, 8000, 26000).body;
            String head = body.trim();
            // an overloaded server answers 200 with an HTML page or a "runtime error" remark and no results
            if (!head.startsWith("{") || head.contains("\"remark\"") && head.contains("runtime error")) {
                throw new IOException("Server returned HTTP 503");
            }
            return body;
        });
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    private static String buildAddress(Map<String, String> tags) {
        StringBuilder sb = new StringBuilder();
        append(sb, tags.get("addr:housenumber"));
        append(sb, tags.get("addr:street"));
        append(sb, firstNonEmpty(tags.get("addr:suburb"), tags.get("addr:city")));
        return sb.toString();
    }

    private static void append(StringBuilder sb, String part) {
        if (part == null || part.isEmpty()) return;
        if (sb.length() > 0) sb.append(", ");
        sb.append(part);
    }
}
