package com.locus.app.net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Finds the DESTINATION the person typed. Two independent OpenStreetMap geocoders are asked at the same time
 * (Photon and Nominatim), both biased towards the start point, so a result close to the person wins over a
 * namesake in another country. Typed coordinates work too. If one service is down the other still answers.
 */
public final class GeoSearch {
    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    private GeoSearch() {
    }

    /** Full search (Search button): both services, up to 12 ranked results. Blocking. */
    public static List<GeoRank.Hit> search(String query, double biasLat, double biasLon) throws Exception {
        String q = query == null ? "" : query.trim();
        if (q.length() < 2) return new ArrayList<>();
        double[] xy = GeoRank.parseCoordinates(q);
        if (xy != null) {
            List<GeoRank.Hit> one = new ArrayList<>();
            one.add(new GeoRank.Hit(String.format(Locale.US, "%.5f, %.5f", xy[0], xy[1]), "", xy[0], xy[1], "coordinates"));
            return GeoRank.rank(one, q, biasLat, biasLon);
        }
        final String qq = q;
        Future<List<GeoRank.Hit>> photon = POOL.submit((Callable<List<GeoRank.Hit>>) () -> photon(qq, biasLat, biasLon, 15));
        Future<List<GeoRank.Hit>> nominatim = POOL.submit((Callable<List<GeoRank.Hit>>) () -> nominatim(qq, biasLat, biasLon, 12));
        List<GeoRank.Hit> all = new ArrayList<>();
        Exception error = null;
        try {
            all.addAll(photon.get(12, TimeUnit.SECONDS));
        } catch (Exception e) {
            error = e;
        }
        try {
            all.addAll(nominatim.get(12, TimeUnit.SECONDS));
        } catch (Exception e) {
            error = e;
        }
        if (all.isEmpty() && error != null) {
            throw error instanceof java.util.concurrent.ExecutionException && error.getCause() instanceof Exception
                    ? (Exception) error.getCause() : error;
        }
        List<GeoRank.Hit> ranked = GeoRank.rank(all, q, biasLat, biasLon);
        return ranked.size() > 12 ? new ArrayList<>(ranked.subList(0, 12)) : ranked;
    }

    /** Quick suggestions while typing: Photon only (built for it), 6 results. Blocking. */
    public static List<GeoRank.Hit> suggest(String query, double biasLat, double biasLon) throws Exception {
        List<GeoRank.Hit> ranked = GeoRank.rank(photon(query.trim(), biasLat, biasLon, 10), query, biasLat, biasLon);
        return ranked.size() > 6 ? new ArrayList<>(ranked.subList(0, 6)) : ranked;
    }

    /** Name of the place at a point ("Boyra, Khulna"), for a start point chosen on the map. Blocking. */
    public static String reverse(double lat, double lon) throws Exception {
        String url = String.format(Locale.US,
                "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=17&lat=%.6f&lon=%.6f&accept-language=%s",
                lat, lon, NominatimApi.appLanguage());
        JSONObject o = new JSONObject(HttpClient.request("GET", url, null, null, 8000, 12000).body);
        String display = o.optString("display_name", "");
        if (display.isEmpty()) return String.format(Locale.US, "%.5f, %.5f", lat, lon);
        String[] parts = display.split(",");
        StringBuilder sb = new StringBuilder(parts[0].trim());
        for (int i = 1; i < parts.length && i < 3; i++) sb.append(", ").append(parts[i].trim());
        return sb.toString();
    }

    // ---- Photon ----------------------------------------------------------------------------------

    static List<GeoRank.Hit> photon(String q, double lat, double lon, int limit) throws Exception {
        String url = String.format(Locale.US, "https://photon.komoot.io/api/?q=%s&lat=%.5f&lon=%.5f&limit=%d",
                URLEncoder.encode(q, "UTF-8"), lat, lon, limit);
        JSONArray features = new JSONObject(HttpClient.request("GET", url, null, null, 6000, 10000).body)
                .optJSONArray("features");
        List<GeoRank.Hit> out = new ArrayList<>();
        if (features == null) return out;
        for (int i = 0; i < features.length(); i++) {
            JSONObject f = features.optJSONObject(i);
            if (f == null) continue;
            JSONObject p = f.optJSONObject("properties");
            JSONObject g = f.optJSONObject("geometry");
            if (p == null || g == null) continue;
            JSONArray c = g.optJSONArray("coordinates");
            if (c == null || c.length() < 2) continue;
            String name = p.optString("name", "");
            String street = p.optString("street", "");
            if (name.isEmpty()) name = street;
            if (name.isEmpty()) name = p.optString("city", p.optString("state", ""));
            if (name.isEmpty()) continue;
            StringBuilder addr = new StringBuilder();
            if (!street.isEmpty() && !street.equals(name)) {
                String house = p.optString("housenumber", "");
                add(addr, house.isEmpty() ? street : house + " " + street);
            }
            add(addr, p.optString("district", ""));
            add(addr, p.optString("city", ""));
            if (addr.length() == 0) add(addr, p.optString("county", ""));
            add(addr, p.optString("state", ""));
            add(addr, p.optString("country", ""));
            out.add(new GeoRank.Hit(name, addr.toString(), c.getDouble(1), c.getDouble(0), "photon"));
        }
        return out;
    }

    // ---- Nominatim -------------------------------------------------------------------------------

    static List<GeoRank.Hit> nominatim(String q, double lat, double lon, int limit) throws Exception {
        double d = 0.5; // a viewbox of about 110 km around the start: a preference, not a limit
        String url = String.format(Locale.US,
                "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=%d&dedupe=1&addressdetails=0"
                        + "&viewbox=%.5f,%.5f,%.5f,%.5f&q=%s&accept-language=%s",
                limit, lon - d, lat + d, lon + d, lat - d, URLEncoder.encode(q, "UTF-8"), NominatimApi.appLanguage());
        JSONArray arr = new JSONArray(HttpClient.request("GET", url, null, null, 6000, 12000).body);
        List<GeoRank.Hit> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String display = o.optString("display_name", "");
            if (display.isEmpty()) continue;
            String[] parts = display.split(",");
            String name = o.optString("name", "");
            if (name.isEmpty()) name = parts[0].trim();
            StringBuilder addr = new StringBuilder();
            for (int k = 1; k < parts.length && k <= 3; k++) {
                if (parts[k].trim().equalsIgnoreCase(name)) continue;
                add(addr, parts[k].trim());
            }
            out.add(new GeoRank.Hit(name, addr.toString(),
                    Double.parseDouble(o.getString("lat")), Double.parseDouble(o.getString("lon")), "nominatim"));
        }
        return out;
    }

    private static void add(StringBuilder sb, String part) {
        if (part == null || part.isEmpty()) return;
        if (sb.length() > 0) sb.append(", ");
        sb.append(part);
    }
}
