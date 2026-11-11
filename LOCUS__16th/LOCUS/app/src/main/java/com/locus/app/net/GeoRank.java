package com.locus.app.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Orders destination-search results so the place the person MEANT comes first: a name that matches what was
 * typed beats a loose match, and among equally good matches the one near the start point beats a far one.
 * Also removes the same place reported by two services, and understands typed coordinates.
 * Pure Java, unit-tested.
 */
public final class GeoRank {
    private GeoRank() {
    }

    /** One candidate destination. */
    public static final class Hit {
        public final String name;       // short name, e.g. "Khulna Medical College Hospital"
        public final String address;    // rest of the address, may be empty
        public final double lat;
        public final double lon;
        public final String source;     // "photon", "nominatim", "coordinates"
        public double distanceM;        // from the search bias point
        public double textScore;        // 0..1, how well the name matches the typed words

        public Hit(String name, String address, double lat, double lon, String source) {
            this.name = name == null ? "" : name;
            this.address = address == null ? "" : address;
            this.lat = lat;
            this.lon = lon;
            this.source = source;
        }

        /** "Name, address" - one line for lists. */
        public String label() {
            return address.isEmpty() ? name : name + ", " + address;
        }
    }

    /** Lower case, punctuation removed, letters of every script kept (Bangla and Hindi vowel signs too). */
    public static String norm(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ").trim();
        return t.replaceAll("\\s+", " ");
    }

    /** 0..1: exact name 1.0, name starts with the text 0.9, every typed word in the name 0.75, in the address 0.5. */
    public static double textScore(String query, String name, String address) {
        String q = norm(query);
        String n = norm(name);
        if (q.isEmpty() || n.isEmpty()) return 0.2;
        if (n.equals(q)) return 1.0;
        if (n.startsWith(q)) return 0.9;
        String[] words = q.split(" ");
        boolean allInName = true;
        boolean allInBoth = true;
        String a = norm(address);
        for (String w : words) {
            boolean inName = containsWordStart(n, w);
            if (!inName) allInName = false;
            if (!inName && !containsWordStart(a, w)) allInBoth = false;
        }
        if (n.contains(q)) return 0.8;
        if (allInName) return 0.75;
        if (allInBoth) return 0.5;
        return 0.2;
    }

    private static boolean containsWordStart(String text, String word) {
        if (word.isEmpty()) return true;
        int i = text.indexOf(word);
        while (i >= 0) {
            if (i == 0 || text.charAt(i - 1) == ' ') return true;
            i = text.indexOf(word, i + 1);
        }
        return false;
    }

    /** Smaller is better. A near exact match wins; a far exact match still beats a near loose one. */
    public static double rankKey(double textScore, double distanceM) {
        return (1.6 - textScore) * (2.0 + distanceM / 1000.0);
    }

    /** Scores, removes duplicates (same name within 150 m, or one name inside the other within 60 m), sorts. */
    public static List<Hit> rank(List<Hit> hits, String query, double biasLat, double biasLon) {
        List<Hit> out = new ArrayList<>();
        for (Hit h : hits) {
            h.distanceM = haversine(biasLat, biasLon, h.lat, h.lon);
            h.textScore = textScore(query, h.name, h.address);
            Hit same = null;
            for (Hit o : out) {
                double d = haversine(o.lat, o.lon, h.lat, h.lon);
                String a = norm(o.name);
                String b = norm(h.name);
                if ((d < 150 && a.equals(b)) || (d < 60 && (a.contains(b) || b.contains(a)))) {
                    same = o;
                    break;
                }
            }
            if (same == null) {
                out.add(h);
            } else if (h.textScore > same.textScore || (h.textScore == same.textScore && h.address.length() > same.address.length())) {
                out.set(out.indexOf(same), h);
            }
        }
        Collections.sort(out, (x, y) -> Double.compare(rankKey(x.textScore, x.distanceM), rankKey(y.textScore, y.distanceM)));
        return out;
    }

    /** "22.8456, 89.5403" / "22.8456 89.5403" / "22.8456N 89.5403E" -> {lat, lon}, or null when it is not a coordinate pair. */
    public static double[] parseCoordinates(String s) {
        if (s == null) return null;
        String t = s.trim().replace('°', ' ').replace(';', ',');
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^([+-]?\\d{1,3}(?:\\.\\d+)?)\\s*([NSns])?\\s*[, ]\\s*([+-]?\\d{1,3}(?:\\.\\d+)?)\\s*([EWew])?$").matcher(t);
        if (!m.matches()) return null;
        double lat = Double.parseDouble(m.group(1));
        double lon = Double.parseDouble(m.group(3));
        if (m.group(2) != null && m.group(2).equalsIgnoreCase("S")) lat = -Math.abs(lat);
        if (m.group(4) != null && m.group(4).equalsIgnoreCase("W")) lon = -Math.abs(lon);
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
        return new double[]{lat, lon};
    }

    static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
