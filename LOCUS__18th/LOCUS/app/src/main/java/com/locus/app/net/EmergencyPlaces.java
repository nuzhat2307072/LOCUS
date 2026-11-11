package com.locus.app.net;

import com.locus.app.model.Place;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Nearest hospital and police station from OpenStreetMap (Overpass). Blocking - call from a background thread. */
public final class EmergencyPlaces {
    private EmergencyPlaces() {
    }

    /** The nearest place of the OSM amenity type ("hospital" or "police") within 15 km, or null. */
    public static Place nearest(String amenity, double lat, double lon) {
        List<String> filters = new ArrayList<>();
        filters.add("[\"amenity\"=\"" + amenity + "\"]");
        List<Place> found;
        try {
            found = OverpassApi.searchPlaces("sos " + amenity, filters, Collections.<String>emptySet(), lat, lon, 15000);
        } catch (Exception e) {
            return null;
        }
        Place best = null;
        for (Place p : found) {
            // a real, named place with a phone number is more useful in an emergency than a closer unnamed one
            if (best == null || p.distanceMeters < best.distanceMeters) best = p;
        }
        return best;
    }
}
