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
import java.util.function.BooleanSupplier;

/**
 * "Find me <anything> nearby". Three independent OpenStreetMap services are asked at the same time and
 * their answers are merged as they arrive, so one slow or failing server never leaves the screen empty:
 *  - Overpass, driven by {@link QueryPlanner} (tags, synonyms, names),
 *  - Nominatim's own search (understands brands and odd wording),
 *  - Photon (forgiving, ranks results around you).
 * The search area is widened automatically (2 km, 5 km, 12 km, 25 km) until there are enough results.
 */
public final class PlaceSearch {
    private static final int MAX_RESULTS = 200;
    private static final long OVERALL_TIMEOUT_MS = 50_000;
    /** After Overpass has answered, Nominatim / Photon get this long to add their extra places. */
    private static final long HELPER_GRACE_MS = 6_000;
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
        return search(rawQuery, lat, lon, progress, () -> false);
    }

    /** Same, and stops early (returning what it has) as soon as {@code cancelled} says the answer is no longer wanted. */
    public static List<Place> search(String rawQuery, final double lat, final double lon, Progress progress,
                                     BooleanSupplier cancelled) throws Exception {
        final String q = QueryPlanner.normalize(rawQuery);
        if (q.length() < 2) throw new IllegalArgumentException("Type at least 2 letters");
        List<String> planned = QueryPlanner.filtersFor(q);
        Set<String> types = QueryPlanner.typeValuesFor(q);
        // A word the dictionary does not know may be a typing mistake ("pharmcy"): plan for the fixed word too.
        String fixedQuery = types.isEmpty() ? QueryPlanner.correct(q) : q;
        if (!fixedQuery.equals(q)) {
            java.util.LinkedHashSet<String> both = new java.util.LinkedHashSet<>(planned);
            both.addAll(QueryPlanner.filtersFor(fixedQuery));
            planned = new ArrayList<>(both);
            types = new java.util.HashSet<>(types);
            types.addAll(QueryPlanner.typeValuesFor(fixedQuery));
        }
        final List<String> filters = planned;
        final Set<String> typeValues = types;
        final String searchText = fixedQuery; // what the free-text services are asked

        final List<Place> found = new ArrayList<>();
        Exception overpassError = null;
        Exception anyError = null;
        boolean overpassWorked = false;

        // Rings, nearest first. Small rings come back fast and never hit the servers' result cap, so the
        // closest places are always complete; wider rings are only searched when the near ones are not enough.
        // The first two rings (about 5 km around you) are ALWAYS searched, so nothing close is missed;
        // after that the area only widens while there are still few good matches.
        int[] overpassRadius = {2000, 5000, 12000, 25000};
        int[] nominatimRadius = {5000, 10000, 20000, 30000};
        int[] enough = {0, 20, 12}; // good (type/name) matches needed to stop widening after ring 1, 2, 3
        for (int round = 0; round < overpassRadius.length; round++) {
            if (cancelled.getAsBoolean()) break;
            if (round > 0 && goodMatches(found) >= enough[round - 1]) break;
            final int op = overpassRadius[round];
            final int nr = nominatimRadius[round];
            List<Callable<Outcome>> jobs = new ArrayList<>();
            jobs.add(() -> run("overpass", () -> OverpassApi.searchPlaces(q, filters, typeValues, lat, lon, op)));
            jobs.add(() -> run("nominatim", () -> fromNominatim(q, searchText, typeValues, lat, lon, nr)));
            if (round == 0) jobs.add(() -> run("photon", () -> fromPhoton(q, searchText, typeValues, lat, lon)));

            ExecutorCompletionService<Outcome> done = new ExecutorCompletionService<>(POOL);
            for (Callable<Outcome> j : jobs) done.submit(j);
            long deadline = System.currentTimeMillis() + OVERALL_TIMEOUT_MS;
            for (int i = 0; i < jobs.size(); i++) {
                if (cancelled.getAsBoolean()) break;
                Future<Outcome> f = done.poll(Math.max(1, Math.min(500, deadline - System.currentTimeMillis())),
                        TimeUnit.MILLISECONDS);
                if (f == null) {
                    if (System.currentTimeMillis() >= deadline) break; // out of time: use what we have
                    i--; // not yet: keep waiting (and keep checking for cancel)
                    continue;
                }
                Outcome o = f.get();
                if (o.error != null) {
                    anyError = o.error;
                    if (o.source.equals("overpass")) overpassError = o.error;
                    continue;
                }
                if (o.source.equals("overpass")) {
                    overpassWorked = true;
                    overpassError = null;
                    // the main source answered: a slow helper may only hold the screen for a few more seconds
                    deadline = Math.min(deadline, System.currentTimeMillis() + HELPER_GRACE_MS);
                }
                int before = found.size();
                merge(found, o.places);
                if (found.size() != before && progress != null) progress.onResults(sorted(found, lat, lon));
            }
        }

        // Overpass is the main source: if it failed and nothing else found anything, say so instead of "nothing found"
        if (found.isEmpty() && !overpassWorked && anyError != null && !cancelled.getAsBoolean()) {
            throw overpassError != null ? overpassError : anyError;
        }
        return sorted(found, lat, lon);
    }

    private static int goodMatches(List<Place> found) {
        int n = 0;
        for (Place p : found) {
            if (p.relevance >= Relevance.NAME) n++;
        }
        return n;
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
        Collections.sort(copy, (a, b) -> Double.compare(Relevance.rank(a.relevance, a.distanceMeters),
                Relevance.rank(b.relevance, b.distanceMeters)));
        return copy.size() > MAX_RESULTS ? new ArrayList<>(copy.subList(0, MAX_RESULTS)) : copy;
    }

    private static List<Place> fromNominatim(String q, String text, Set<String> typeValues, double lat, double lon,
                                             int radius) throws Exception {
        List<Place> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (NominatimApi.Found f : NominatimApi.searchNear(text, lat, lon, radius)) {
            out.add(place(q, typeValues, f.osmKey, f.name, f.label, f.address, f.lat, f.lon, lat, lon, now));
        }
        return out;
    }

    private static List<Place> fromPhoton(String q, String text, Set<String> typeValues, double lat, double lon)
            throws Exception {
        List<Place> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (PhotonApi.Found f : PhotonApi.search(text, lat, lon)) {
            if (GeoUtils.distance(lat, lon, f.lat, f.lon) > 25000) continue; // only places you could reach today
            out.add(place(q, typeValues, f.osmKey, f.name, f.label, f.address, f.lat, f.lon, lat, lon, now));
        }
        return out;
    }

    private static Place place(String q, Set<String> typeValues, String osmKey, String name, String label,
                               String address, double pLat, double pLon, double lat, double lon, long now) {
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
        p.relevance = Relevance.tier(q, typeValues, name,
                Collections.singletonList(label.toLowerCase(java.util.Locale.ROOT).replace(' ', '_')));
        return p;
    }

    /** Adds the places that are not already there (same OSM object, or same name within 100 m). */
    private static void merge(List<Place> base, List<Place> extra) {
        java.util.Map<String, Place> byId = new java.util.HashMap<>();
        for (Place p : base) byId.put(p.id, p);
        for (Place c : extra) {
            Place same = byId.get(c.id);
            if (same == null) {
                for (Place p : base) {
                    if (p.name != null && p.name.equalsIgnoreCase(c.name)
                            && GeoUtils.distance(p.lat, p.lon, c.lat, c.lon) < 100) {
                        same = p;
                        break;
                    }
                }
            }
            if (same == null) {
                base.add(c);
                byId.put(c.id, c);
                continue;
            }
            // the same place from two services: keep the richer record (Overpass has phone / hours)
            if (c.relevance > same.relevance) same.relevance = c.relevance;
            if (isEmpty(same.phone) && !isEmpty(c.phone)) same.phone = c.phone;
            if (isEmpty(same.openingHours) && !isEmpty(c.openingHours)) same.openingHours = c.openingHours;
            if (isEmpty(same.address) && !isEmpty(c.address)) same.address = c.address;
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
