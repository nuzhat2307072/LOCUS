package com.locus.app.util;

import com.locus.app.graph.Graph;

import android.content.Context;

import com.locus.app.R;

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

    /** Localised: "850 m" / "1.2 km" (digits follow the app language). */
    public static String distanceText(Context c, double meters) {
        if (meters < 1000) return c.getString(R.string.unit_m, Math.round(meters));
        return c.getString(R.string.unit_km, String.format(Locale.getDefault(), "%.1f", meters / 1000.0));
    }

    /** Localised: "< 1 min" / "12 min" / "1 h 5 min". */
    public static String durationText(Context c, double seconds) {
        long mins = Math.round(seconds / 60.0);
        if (mins < 1) return c.getString(R.string.less_than_min);
        if (mins < 60) return c.getString(R.string.unit_min, mins);
        return c.getString(R.string.unit_h_min, mins / 60, mins % 60);
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
