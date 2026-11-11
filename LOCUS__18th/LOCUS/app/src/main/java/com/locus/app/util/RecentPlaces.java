package com.locus.app.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Recent destinations": the last places the person navigated to, newest first, saved as text lines
 * "name|lat|lon". Pure Java (unit-tested); AppPrefs only stores the lines.
 */
public final class RecentPlaces {
    public static final int MAX = 8;

    public static final class Item {
        public final String name;
        public final double lat;
        public final double lon;

        public Item(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private RecentPlaces() {
    }

    /** Small-distance approximation (plenty for "the same place"). */
    private static double metres(double lat1, double lon1, double lat2, double lon2) {
        double dy = (lat2 - lat1) * 111_320.0;
        double dx = (lon2 - lon1) * 111_320.0 * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        return Math.sqrt(dx * dx + dy * dy);
    }

    public static List<Item> parse(List<String> lines) {
        List<Item> out = new ArrayList<>();
        for (String line : lines) {
            String[] p = line.split("\\|");
            if (p.length < 3) continue;
            try {
                out.add(new Item(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2])));
            } catch (NumberFormatException ignored) {
                // a damaged line is skipped
            }
        }
        return out;
    }

    /** The new list of lines: this place first; the same place (same name within 50 m) is not repeated. */
    public static List<String> push(List<String> lines, String name, double lat, double lon) {
        String clean = name == null ? "" : name.replace('|', ' ').replace('\n', ' ').trim();
        if (clean.isEmpty()) return new ArrayList<>(lines);
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.US, "%s|%.6f|%.6f", clean, lat, lon));
        for (Item it : parse(lines)) {
            boolean same = it.name.equalsIgnoreCase(clean) && metres(it.lat, it.lon, lat, lon) < 50;
            if (!same && out.size() < MAX) {
                out.add(String.format(Locale.US, "%s|%.6f|%.6f", it.name, it.lat, it.lon));
            }
        }
        return out;
    }
}
