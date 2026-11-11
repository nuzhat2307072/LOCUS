package com.locus.app.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.locus.app.model.Place;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** The in-memory Explore cache: grid keys, fresh / stale / gone, size limit. */
public class ExploreCacheTest {
    @Before
    public void reset() {
        ExploreCache.clear();
    }

    private static List<Place> one() {
        List<Place> l = new ArrayList<>();
        l.add(new Place());
        return l;
    }

    @Test
    public void nearbySpotsShareAKeyButFarOnesDoNot() {
        String a = ExploreCache.key("pharmacy", 22.8000, 89.5500);
        assertEquals(a, ExploreCache.key("pharmacy", 22.8002, 89.5501));
        assertFalse(a.equals(ExploreCache.key("pharmacy", 22.8300, 89.5500)));
        assertFalse(a.equals(ExploreCache.key("hospital", 22.8000, 89.5500)));
    }

    @Test
    public void freshThenStaleThenGone() {
        ExploreCache.put("k", one(), 1000);
        ExploreCache.Entry e = ExploreCache.get("k", 1000 + 60_000);
        assertNotNull(e);
        assertTrue(ExploreCache.isFresh(e, 1000 + 60_000));
        ExploreCache.Entry old = ExploreCache.get("k", 1000 + 10 * 60_000);
        assertNotNull(old);
        assertFalse(ExploreCache.isFresh(old, 1000 + 10 * 60_000));
        assertNull(ExploreCache.get("k", 1000 + ExploreCache.STALE_MS + 1));
        assertNull(ExploreCache.get("k", 1000 + 60_000)); // removed once it was too old
    }

    @Test
    public void unknownKeyIsNull() {
        assertNull(ExploreCache.get("nothing", 5));
        assertFalse(ExploreCache.isFresh(null, 5));
    }

    @Test
    public void theCacheDoesNotGrowForever() {
        for (int i = 0; i < 100; i++) ExploreCache.put("k" + i, one(), 1);
        assertNull(ExploreCache.get("k0", 2));
        assertNotNull(ExploreCache.get("k99", 2));
    }
}
