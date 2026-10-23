package com.locus.app.net;

import com.locus.app.model.Place;
import com.locus.app.util.GeoUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * "Find me <anything> nearby". Three independent OpenStreetMap services are asked at the same time and
 * their answers are merged as they arrive, so one slow or failing server never leaves the screen empty:
 *  - Overpass, driven by {@link QueryPlanner} (tags, synonyms, names),
 *  - Nominatim's own search (understands brands and odd wording),
 *  - Photon (forgiving, ranks results around you).
 * If there are few results the search area is widened automatically (2 km, then 6 km, then 15 km).
 */
public final class PlaceSearch {
    private static final int MAX_RESULTS = 60;
    private static final long OVERALL_TIMEOUT_MS = 50_000;
    // Own pool on purpose: search() itself runs on the shared IO pool and must not wait for it.
    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    private PlaceSearch() {
    }

    /** Called (on the searching thread) every time new results have been merged. */
    public interface Progress {
        void onResults(List<Place> sortedSoFar);
    }

    private static final class Outcome {
        final String source;
        final List<Place> places;
        final Exception error;

        Outcome(String source, List<Place> places, Exception error) {
            this.source = source;
            this.places = places;
            this.error = error;
        }
    }

    /** Blocking - call from a background thread. Throws only when nothing could be found because of errors. */
    public static List<Place> search(String rawQuery, final double lat, final double lon, Progress progress)
            throws Exception {
        final String q = QueryPlanner.normalize(rawQuery);
        if (q.length() < 2) throw new IllegalArgumentException("Type at least 2 letters");
        final List<String> filters = QueryPlanner.filtersFor(q);

        final List<Place> found = new ArrayList<>();
        Exception overpassError = null;
        Exception anyError = null;
        boolean overpassWorked = false;

        // round 1: everything at once; later rounds look further away when there is little nearby
        int[] overpassRadius = {2000, 6000, 15000};
        int[] nominatimRadius = {6000, 15000, 30000};
        int[] enoughResults = {5, 3, 1};
        for (int round = 0; round < overpassRadius.length; round++) {
            if (round > 0 && found.size() >= enoughResults[round - 1]) break;
            final int op = overpassRadius[round];
            final int nr = nominatimRadius[round];
            List<Callable<Outcome>> jobs = new ArrayList<>();
            jobs.add(() -> run("overpass", () -> OverpassApi.searchPlaces(q, filters, lat, lon, op)));
            jobs.add(() -> run("nominatim", () -> fromNominatim(q, lat, lon, nr)));
            if (round == 0) jobs.add(() -> run("photon", () -> fromPhoton(q, lat, lon)));

            ExecutorCompletionService<Outcome> done = new ExecutorCompletionService<>(POOL);
            for (Callable<Outcome> j : jobs) done.submit(j);
            long deadline = System.currentTimeMillis() + OVERALL_TIMEOUT_MS;
            for (int i = 0; i < jobs.size(); i++) {
                Future<Outcome> f = done.poll(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (f == null) break; // out of time: use what we have
                Outcome o = f.get();
                if (o.error != null) {
                    anyError = o.error;
                    if (o.source.equals("overpass")) overpassError = o.error;
                    continue;
                }
                if (o.source.equals("overpass")) {
                    overpassWorked = true;
                    overpassError = null;
                }
                int before = found.size();
                merge(found, o.places);
                if (found.size() != before && progress != null) progress.onResults(sorted(found, lat, lon));
            }
        }

        // Overpass is the main source: if it failed and nothing else found anything, say so instead of "nothing found"
        if (found.isEmpty() && !overpassWorked && anyError != null) {
            throw overpassError != null ? overpassError : anyError;
        }
        return sorted(found, lat, lon);
    }

    private interface Source {
        List<Place> get() throws Exception;
    }

    private static Outcome run(String name, Source s) {
        try {
            return new Outcome(name, s.get(), null);
        } catch (Exception e) {
            return new Outcome(name, null, e);
        }
    }

    private static List<Place> sorted(List<Place> found, double lat, double lon) {
        List<Place> copy = new ArrayList<>(found.size());
        for (Place p : found) {
            p.distanceMeters = GeoUtils.distance(lat, lon, p.lat, p.lon);
            copy.add(p);
        }
        Collections.sort(copy, (a, b) -> Double.compare(a.distanceMeters, b.distanceMeters));
        return copy.size() > MAX_RESULTS ? new ArrayList<>(copy.subList(0, MAX_RESULTS)) : copy;
    }

    private static List<Place> fromNominatim(String q, double lat, double lon, int radius) throws Exception {
        List<Place> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (NominatimApi.Found f : NominatimApi.searchNear(q, lat, lon, radius)) {
            out.add(place(q, f.osmKey, f.name, f.label, f.address, f.lat, f.lon, lat, lon, now));
        }
        return out;
    }

    private static List<Place> fromPhoton(String q, double lat, double lon) throws Exception {
        List<Place> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (PhotonApi.Found f : PhotonApi.search(q, lat, lon)) {
            if (GeoUtils.distance(lat, lon, f.lat, f.lon) > 30000) continue; // only places you could reach today
            out.add(place(q, f.osmKey, f.name, f.label, f.address, f.lat, f.lon, lat, lon, now));
        }
        return out;
    }

    private static Place place(String q, String osmKey, String name, String label, String address,
                               double pLat, double pLon, double lat, double lon, long now) {
        Place p = new Place();
        p.id = q + "#" + osmKey;
        p.searchKey = q;
        p.name = name;
        p.category = label;
        p.lat = pLat;
        p.lon = pLon;
        p.address = address;
        p.phone = "";
        p.openingHours = "";
        p.distanceMeters = GeoUtils.distance(lat, lon, pLat, pLon);
        p.fetchedAt = now;
        return p;
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
