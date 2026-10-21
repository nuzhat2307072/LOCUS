package com.locus.app.net;

import com.locus.app.model.Place;
import com.locus.app.util.GeoUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * "Find me <anything> nearby". Two independent OpenStreetMap services are asked at the same time and
 * their answers are merged, so one slow or failing server does not leave the screen empty:
 *  - Overpass, driven by {@link QueryPlanner} (tags, synonyms, names),
 *  - Nominatim's own search (understands brands and odd wording).
 * If there are few results the search area is widened automatically (2 km, then 6 km, then 15 km).
 */
public final class PlaceSearch {
    private static final int MAX_RESULTS = 50;
    // Own pool on purpose: this method itself runs on the shared IO pool and must not wait for it.
    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    private PlaceSearch() {
    }

    /** Blocking - call from a background thread. */
    public static List<Place> search(String rawQuery, final double lat, final double lon) throws Exception {
        final String q = QueryPlanner.normalize(rawQuery);
        if (q.length() < 2) throw new IllegalArgumentException("Type at least 2 letters");
        final List<String> filters = QueryPlanner.filtersFor(q);

        Future<List<Place>> nominatim = POOL.submit(() -> fromNominatim(q, lat, lon, 6000));

        List<Place> found = new ArrayList<>();
        Exception overpassError = null;
        try {
            merge(found, OverpassApi.searchPlaces(q, filters, lat, lon, 2000));
        } catch (Exception e) {
            overpassError = e;
        }
        Exception nominatimError = null;
        try {
            merge(found, nominatim.get(30, TimeUnit.SECONDS));
        } catch (Exception e) {
            nominatimError = e;
        }

        // not much nearby: look further away
        int[] wider = {6000, 15000};
        int[] needLessThan = {5, 3};
        for (int i = 0; i < wider.length && overpassError == null; i++) {
            if (found.size() >= needLessThan[i]) break;
            try {
                merge(found, OverpassApi.searchPlaces(q, filters, lat, lon, wider[i]));
            } catch (Exception e) {
                overpassError = e;
            }
        }

        if (found.isEmpty() && overpassError != null && nominatimError != null) throw overpassError;

        for (Place p : found) p.distanceMeters = GeoUtils.distance(lat, lon, p.lat, p.lon);
        Collections.sort(found, (a, b) -> Double.compare(a.distanceMeters, b.distanceMeters));
        return found.size() > MAX_RESULTS ? new ArrayList<>(found.subList(0, MAX_RESULTS)) : found;
    }

    private static List<Place> fromNominatim(String q, double lat, double lon, int radius) throws Exception {
        List<Place> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (NominatimApi.Found f : NominatimApi.searchNear(q, lat, lon, radius)) {
            double d = GeoUtils.distance(lat, lon, f.lat, f.lon);
            if (d > radius * 1.2) continue;
            Place p = new Place();
            p.id = q + "#" + f.osmKey;
            p.searchKey = q;
            p.name = f.name;
            p.category = f.label;
            p.lat = f.lat;
            p.lon = f.lon;
            p.address = f.address;
            p.phone = "";
            p.openingHours = "";
            p.distanceMeters = d;
            p.fetchedAt = now;
            out.add(p);
        }
        return out;
    }

    /** Adds the places that are not already there (same OSM object, or same name within 60 m). */
    private static void merge(List<Place> base, List<Place> extra) {
        Set<String> ids = new HashSet<>();
        for (Place p : base) ids.add(p.id);
        for (Place c : extra) {
            if (ids.contains(c.id)) continue;
            boolean duplicate = false;
            for (Place p : base) {
                if (p.name != null && p.name.equalsIgnoreCase(c.name)
                        && GeoUtils.distance(p.lat, p.lon, c.lat, c.lon) < 60) {
                    duplicate = true;
                    // keep the richer record (Overpass usually has phone / hours)
                    if ((p.phone == null || p.phone.isEmpty()) && c.phone != null && !c.phone.isEmpty()) {
                        p.phone = c.phone;
                    }
                    if ((p.openingHours == null || p.openingHours.isEmpty()) && c.openingHours != null) {
                        p.openingHours = c.openingHours;
                    }
                    break;
                }
            }
            if (!duplicate) {
                base.add(c);
                ids.add(c.id);
            }
        }
    }
}
