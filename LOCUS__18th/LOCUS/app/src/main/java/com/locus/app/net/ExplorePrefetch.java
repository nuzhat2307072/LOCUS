package com.locus.app.net;

import android.content.Context;
import android.os.SystemClock;

import com.locus.app.data.db.LocusDatabase;
import com.locus.app.model.Place;
import com.locus.app.util.AppExecutors;
import com.locus.app.util.AppPrefs;

import java.util.List;

/**
 * Warms the cache while the person is on the dashboard: the most useful searches (hospital, pharmacy) around the
 * last known position are fetched quietly, so tapping them in Explore shows the answer at once. At most once per
 * 10 minutes, never blocks the screen, and every failure is ignored.
 */
public final class ExplorePrefetch {
    private static final String[] QUERIES = {"hospital", "pharmacy", "restaurant"};
    private static final long EVERY_MS = 10 * 60_000L;
    private static long lastRun = -EVERY_MS;
    private static boolean running;

    private ExplorePrefetch() {
    }

    public static void warm(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (!AppPrefs.hasLastLocation(app)) return;
        synchronized (ExplorePrefetch.class) {
            if (running || SystemClock.elapsedRealtime() - lastRun < EVERY_MS) return;
            running = true;
            lastRun = SystemClock.elapsedRealtime();
        }
        final double[] at = AppPrefs.lastLocation(app);
        AppExecutors.io().execute(() -> {
            try {
                for (String q : QUERIES) {
                    String key = ExploreCache.key(q, at[0], at[1]);
                    long now = System.currentTimeMillis();
                    if (ExploreCache.isFresh(ExploreCache.get(key, now), now)) continue;
                    try {
                        List<Place> got = PlaceSearch.search(q, at[0], at[1], null);
                        ExploreCache.put(key, got, System.currentTimeMillis());
                        LocusDatabase.get(app).placeDao().replaceSearch(q, got);
                    } catch (Exception e) {
                        break; // offline or the servers are busy: try again at the next dashboard visit
                    }
                }
            } finally {
                synchronized (ExplorePrefetch.class) {
                    running = false;
                }
            }
        });
    }
}
