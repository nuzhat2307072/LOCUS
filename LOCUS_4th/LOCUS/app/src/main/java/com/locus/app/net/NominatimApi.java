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

    /** Place names come back in the app language when OpenStreetMap has them (e.g. Bangla). */
    static String appLanguage() {
        String l = java.util.Locale.getDefault().getLanguage();
        return l == null || l.isEmpty() ? "en" : l + ",en";
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

    /** A nearby place found by Nominatim's own search (it understands many words Overpass tags do not). */
    public static final class Found {
        public final String osmKey;   // e.g. "node123456" - same format as the Overpass results, used to merge
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

    /** Blocking. Free-text search restricted to a box of radiusM around (lat, lon). */
    public static List<Found> searchNear(String query, double lat, double lon, int radiusM) throws Exception {
        double dLat = radiusM / 111320.0;
        double dLon = radiusM / (111320.0 * Math.max(0.2, Math.cos(Math.toRadians(lat))));
        // viewbox = left,top,right,bottom
        String url = String.format(java.util.Locale.US,
                "https://nominatim.openstreetmap.org/search?format=xml&limit=25&bounded=1&viewbox=%.6f,%.6f,%.6f,%.6f&q=%s&accept-language=%s",
                lon - dLon, lat + dLat, lon + dLon, lat - dLat, URLEncoder.encode(query, "UTF-8"), appLanguage());
        String xml = HttpClient.get(url).body;

        List<Found> out = new ArrayList<>();
        XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
        p.setInput(new StringReader(xml));
        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && "place".equals(p.getName())) {
                String cls = p.getAttributeValue(null, "class");
                // skip districts, cities, postcodes ... - we want somewhere you can go to
                if (!"boundary".equals(cls) && !"place".equals(cls)) {
                    String la = p.getAttributeValue(null, "lat");
                    String lo = p.getAttributeValue(null, "lon");
                    String type = p.getAttributeValue(null, "type");
                    String display = p.getAttributeValue(null, "display_name");
                    if (la != null && lo != null && display != null) {
                        String[] parts = display.split(",");
                        String name = parts[0].trim();
                        StringBuilder addr = new StringBuilder();
                        for (int i = 1; i < parts.length && i <= 2; i++) {
                            if (addr.length() > 0) addr.append(", ");
                            addr.append(parts[i].trim());
                        }
                        String label = QueryPlanner.humanize(
                                type == null || type.equals("yes") ? cls : type);
                        String key = p.getAttributeValue(null, "osm_type") + p.getAttributeValue(null, "osm_id");
                        out.add(new Found(key, name, label, addr.toString(),
                                Double.parseDouble(la), Double.parseDouble(lo)));
                    }
                }
            }
            ev = p.next();
        }
        return out;
    }

    /** Blocking. Up to 6 matches for the text. */
    public static List<Result> search(String query) throws Exception {
        String url = "https://nominatim.openstreetmap.org/search?format=xml&limit=6&accept-language="
                + appLanguage() + "&q=" + URLEncoder.encode(query, "UTF-8");
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
}
