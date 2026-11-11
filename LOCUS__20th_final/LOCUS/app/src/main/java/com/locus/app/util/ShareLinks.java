package com.locus.app.util;

import java.util.Locale;

/** Web links for sharing a place or a route. They open in any browser or maps app, with or without LOCUS. Pure Java. */
public final class ShareLinks {
    private ShareLinks() {
    }

    /** OpenStreetMap page with a pin on the place. */
    public static String placeUrl(double lat, double lon) {
        return String.format(Locale.US, "https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=17/%.6f/%.6f",
                lat, lon, lat, lon);
    }

    /** OpenStreetMap directions from one point to another; mode is "walk", "cycle" or "car". */
    public static String routeUrl(String mode, double fromLat, double fromLon, double toLat, double toLon) {
        String engine = "car".equals(mode) ? "fossgis_osrm_car" : "cycle".equals(mode) ? "fossgis_osrm_bike"
                : "fossgis_osrm_foot";
        return String.format(Locale.US, "https://www.openstreetmap.org/directions?engine=%s&route=%.6f,%.6f;%.6f,%.6f",
                engine, fromLat, fromLon, toLat, toLon);
    }
}
