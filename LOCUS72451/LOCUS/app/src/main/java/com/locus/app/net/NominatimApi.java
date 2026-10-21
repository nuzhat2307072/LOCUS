package com.locus.app.net;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** OSM Nominatim geocoder. Uses the XML output on purpose, to demonstrate XML parsing (XmlPullParser). */
public final class NominatimApi {
    private NominatimApi() {
    }

    public static final class Result {
        public final String name;
        public final double lat;
        public final double lon;

        Result(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** Blocking. Up to 6 matches for the text. */
    public static List<Result> search(String query) throws Exception {
        String url = "https://nominatim.openstreetmap.org/search?format=xml&limit=6&q="
                + URLEncoder.encode(query, "UTF-8");
        String xml = HttpClient.get(url).body;

        List<Result> out = new ArrayList<>();
        XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
        p.setInput(new StringReader(xml));
        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && "place".equals(p.getName())) {
                String lat = p.getAttributeValue(null, "lat");
                String lon = p.getAttributeValue(null, "lon");
                String name = p.getAttributeValue(null, "display_name");
                if (lat != null && lon != null) {
                    out.add(new Result(name == null ? query : name, Double.parseDouble(lat), Double.parseDouble(lon)));
                }
            }
            ev = p.next();
        }
        return out;
    }

    /** Blocking. Searches nearby places matching the query/category and returns them as Place models. */
    public static List<com.locus.app.model.Place> searchPlacesNearby(String query, double userLat, double userLon) throws Exception {
        String url = String.format(java.util.Locale.US,
                "https://nominatim.openstreetmap.org/search?format=xml&limit=20&q=%s&lat=%.6f&lon=%.6f",
                URLEncoder.encode(query, "UTF-8"), userLat, userLon);
        String xml = HttpClient.get(url).body;

        List<com.locus.app.model.Place> out = new ArrayList<>();
        XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
        p.setInput(new StringReader(xml));
        int ev = p.getEventType();
        long now = System.currentTimeMillis();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && "place".equals(p.getName())) {
                String latStr = p.getAttributeValue(null, "lat");
                String lonStr = p.getAttributeValue(null, "lon");
                String displayName = p.getAttributeValue(null, "display_name");
                String placeId = p.getAttributeValue(null, "place_id");
                String type = p.getAttributeValue(null, "type");
                if (latStr != null && lonStr != null && displayName != null) {
                    double lat = Double.parseDouble(latStr);
                    double lon = Double.parseDouble(lonStr);
                    com.locus.app.model.Place place = new com.locus.app.model.Place();
                    place.id = "nom_" + (placeId != null ? placeId : System.nanoTime());
                    place.name = displayName.split(",")[0].trim();
                    place.address = displayName;
                    place.category = type != null ? type : "SEARCH";
                    place.lat = lat;
                    place.lon = lon;
                    place.distanceMeters = com.locus.app.util.GeoUtils.distance(userLat, userLon, lat, lon);
                    place.fetchedAt = now;
                    out.add(place);
                }
            }
            ev = p.next();
        }
        java.util.Collections.sort(out, (a, b) -> Double.compare(a.distanceMeters, b.distanceMeters));
        return out;
    }
}
