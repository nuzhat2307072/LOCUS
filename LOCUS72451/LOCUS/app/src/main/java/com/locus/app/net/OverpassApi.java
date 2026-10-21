package com.locus.app.net;

import com.locus.app.graph.Graph;
import com.locus.app.model.Place;
import com.locus.app.model.PlaceCategory;
import com.locus.app.util.GeoUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** OpenStreetMap Overpass API: nearby places (JSON) and the road network (streamed JSON -> Graph). */
public final class OverpassApi {
    private static final String[] ENDPOINTS = {
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.nchc.org.tw/api/interpreter"
    };
    private static final String FORM = "application/x-www-form-urlencoded; charset=utf-8";

    private OverpassApi() {
    }

    /** Blocking. Returns places sorted by distance from (lat, lon). */
    public static List<Place> searchPlaces(double lat, double lon, PlaceCategory category, int radiusMeters)
            throws IOException, JSONException {
        StringBuilder q = new StringBuilder("[out:json][timeout:25];(");
        for (String filter : category.filters) {
            q.append(String.format(Locale.US, "nwr%s(around:%d,%.6f,%.6f);", filter, radiusMeters, lat, lon));
        }
        q.append(");out center 60;");

        String body = postWithFallback(q.toString());
        JSONArray elements = new JSONObject(body).getJSONArray("elements");
        return parsePlacesFromElements(elements, lat, lon, category.name());
    }

    /** Blocking. Searches nearby places by text query in 5km radius. */
    public static List<Place> searchPlacesByQuery(double lat, double lon, String query, int radiusMeters)
            throws IOException, JSONException {
        String sanitized = query.replaceAll("[\"\\\\]", "").trim();
        if (sanitized.isEmpty()) return Collections.emptyList();

        StringBuilder q = new StringBuilder("[out:json][timeout:25];(");
        q.append(String.format(Locale.US, "nwr[\"name\"~\"%s\",i](around:%d,%.6f,%.6f);", sanitized, radiusMeters, lat, lon));
        q.append(String.format(Locale.US, "nwr[\"amenity\"~\"%s\",i](around:%d,%.6f,%.6f);", sanitized, radiusMeters, lat, lon));
        q.append(String.format(Locale.US, "nwr[\"shop\"~\"%s\",i](around:%d,%.6f,%.6f);", sanitized, radiusMeters, lat, lon));
        q.append(String.format(Locale.US, "nwr[\"cuisine\"~\"%s\",i](around:%d,%.6f,%.6f);", sanitized, radiusMeters, lat, lon));
        q.append(");out center 60;");

        String body = postWithFallback(q.toString());
        JSONArray elements = new JSONObject(body).getJSONArray("elements");
        return parsePlacesFromElements(elements, lat, lon, "SEARCH");
    }

    private static List<Place> parsePlacesFromElements(JSONArray elements, double lat, double lon, String categoryName)
            throws JSONException {
        List<Place> out = new ArrayList<>();
        long now = System.currentTimeMillis();
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
            JSONObject tags = e.optJSONObject("tags");
            if (tags == null) tags = new JSONObject();

            String name = tags.optString("name", "").trim();
            if (name.isEmpty()) name = tags.optString("name:en", "").trim();
            if (name.isEmpty()) name = tags.optString("brand", "").trim();
            if (name.isEmpty()) name = tags.optString("operator", "").trim();
            if (name.isEmpty()) {
                // Skip unnamed elements to avoid random generic objects
                continue;
            }

            String cat = categoryName;
            if ("SEARCH".equalsIgnoreCase(categoryName)) {
                if (tags.has("amenity")) cat = tags.optString("amenity");
                else if (tags.has("shop")) cat = tags.optString("shop");
                else if (tags.has("cuisine")) cat = tags.optString("cuisine");
                else cat = "Place";
            }

            Place p = new Place();
            p.id = e.optString("type", "n") + e.optLong("id");
            p.name = name;
            p.category = cat;
            p.lat = pLat;
            p.lon = pLon;
            p.phone = tags.optString("phone", tags.optString("contact:phone", ""));
            p.openingHours = tags.optString("opening_hours", "");
            p.address = buildAddress(tags);
            p.distanceMeters = GeoUtils.distance(lat, lon, pLat, pLon);
            p.fetchedAt = now;
            out.add(p);
        }
        Collections.sort(out, (a, b) -> Double.compare(a.distanceMeters, b.distanceMeters));
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

    private static String postWithFallback(String query) throws IOException {
        String data = "data=" + URLEncoder.encode(query, "UTF-8");
        IOException last = null;
        for (String endpoint : ENDPOINTS) {
            try {
                return HttpClient.request("POST", endpoint, data, FORM).body;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("No Overpass server available") : last;
    }

    private static String buildAddress(JSONObject tags) {
        StringBuilder sb = new StringBuilder();
        append(sb, tags.optString("addr:housenumber", ""));
        append(sb, tags.optString("addr:street", ""));
        append(sb, tags.optString("addr:city", ""));
        return sb.toString();
    }

    private static void append(StringBuilder sb, String part) {
        if (part.isEmpty()) return;
        if (sb.length() > 0) sb.append(", ");
        sb.append(part);
    }
}
