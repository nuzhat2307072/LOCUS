package com.locus.app.net;

import com.locus.app.model.Place;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Results of recent Explore searches kept in memory for the whole app run, shared by the dashboard prefetch and the
 * Explore screen: opening Explore (or tapping a category) can show the answer at once, and a slightly old answer is
 * still shown immediately while a fresh one loads. Pure Java, tested on the JVM.
 */
public final class ExploreCache {
    /** Younger than this: the answer is used as it is, no new request. */
    public static final long FRESH_MS = 3 * 60_000L;
    /** Older than FRESH but younger than this: shown at once, then refreshed in the background. */
    public static final long STALE_MS = 30 * 60_000L;
    private static final int MAX_ENTRIES = 40;
    /** About 300 m grid: the same search a few steps away reuses the answer. */
    private static final double GRID = 350;

    private ExploreCache() {
    }

    public static final class Entry {
        public final List<Place> places;
        public final long at;

        Entry(List<Place> places, long at) {
            this.places = places;
            this.at = at;
        }
    }

    private static final Map<String, Entry> MAP = new LinkedHashMap<String, Entry>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, ExploreCache.Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public static String key(String normalizedQuery, double lat, double lon) {
        return normalizedQuery + "|" + Math.round(lat * GRID) + "," + Math.round(lon * GRID);
    }

    public static synchronized void put(String key, List<Place> places, long now) {
        MAP.put(key, new Entry(new ArrayList<>(places), now));
    }

    /** The saved answer (any age up to STALE_MS), or null. */
    public static synchronized Entry get(String key, long now) {
        Entry e = MAP.get(key);
        if (e == null) return null;
        if (now - e.at > STALE_MS) {
            MAP.remove(key);
            return null;
        }
        return e;
    }

    public static boolean isFresh(Entry e, long now) {
        return e != null && now - e.at < FRESH_MS;
    }

    public static synchronized void clear() {
        MAP.clear();
    }
}
