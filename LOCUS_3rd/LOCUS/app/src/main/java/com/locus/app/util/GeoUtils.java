package com.locus.app.util;

import com.locus.app.graph.Graph;

import java.util.Locale;

public final class GeoUtils {
    private GeoUtils() {
    }

    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        return Graph.haversine(lat1, lon1, lat2, lon2);
    }

    /** Distance in metres from a point to the segment a-b (flat-earth approximation, fine for city scale). */
    public static double distanceToSegment(double lat, double lon, double aLat, double aLon,
                                           double bLat, double bLon) {
        double cos = Math.cos(Math.toRadians(lat));
        double px = (lon - aLon) * cos;
        double py = lat - aLat;
        double bx = (bLon - aLon) * cos;
        double by = bLat - aLat;
        double len2 = bx * bx + by * by;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, (px * bx + py * by) / len2));
        double dx = px - t * bx;
        double dy = py - t * by;
        return Math.sqrt(dx * dx + dy * dy) * 111320.0;
    }

    public static String formatDistance(double meters) {
        if (meters < 1000) return String.format(Locale.US, "%d m", Math.round(meters));
        return String.format(Locale.US, "%.1f km", meters / 1000.0);
    }

    public static String formatDuration(double seconds) {
        long mins = Math.round(seconds / 60.0);
        if (mins < 1) return "< 1 min";
        if (mins < 60) return mins + " min";
        return (mins / 60) + " h " + (mins % 60) + " min";
    }

    public static double walkSeconds(double meters) {
        return meters / 1.4;
    }

    public static double bikeSeconds(double meters) {
        return meters / 4.2;
    }

    public static double driveSeconds(double meters) {
        return meters / 6.9; // ~25 km/h average city speed
    }
}
