package com.locus.app.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Destination search: the place that was meant comes first, duplicates vanish, coordinates work. */
public class GeoRankTest {
    private static final double LAT = 22.8456, LON = 89.5403; // Khulna

    @Test
    public void exactNameBeatsLooseMatch() {
        assertTrue(GeoRank.textScore("kuet", "KUET", "") > GeoRank.textScore("kuet", "Kuet Road Pharmacy", ""));
        assertEquals(1.0, GeoRank.textScore("Khulna Medical College", "khulna medical college", ""), 0.001);
        assertTrue(GeoRank.textScore("medical college", "Khulna Medical College Hospital", "") >= 0.75);
    }

    @Test
    public void nearbyNamesakeBeatsFarOne() {
        List<GeoRank.Hit> hits = new ArrayList<>();
        hits.add(new GeoRank.Hit("City Hospital", "Dhaka", 23.78, 90.41, "photon"));      // about 270 km away
        hits.add(new GeoRank.Hit("City Hospital", "Khulna", 22.83, 89.55, "nominatim"));  // 2 km away
        List<GeoRank.Hit> ranked = GeoRank.rank(hits, "city hospital", LAT, LON);
        assertEquals(2, ranked.size());
        assertEquals("Khulna", ranked.get(0).address);
    }

    @Test
    public void sameShopFromTwoServicesIsListedOnce() {
        List<GeoRank.Hit> hits = new ArrayList<>();
        hits.add(new GeoRank.Hit("Star Pharmacy", "", 22.8460, 89.5410, "photon"));
        hits.add(new GeoRank.Hit("Star Pharmacy", "Boyra, Khulna", 22.8461, 89.5411, "nominatim"));
        List<GeoRank.Hit> ranked = GeoRank.rank(hits, "star pharmacy", LAT, LON);
        assertEquals(1, ranked.size());
        assertEquals("Boyra, Khulna", ranked.get(0).address); // the one with the fuller address is kept
    }

    @Test
    public void sameNameFarApartIsNotADuplicate() {
        List<GeoRank.Hit> hits = new ArrayList<>();
        hits.add(new GeoRank.Hit("Star Pharmacy", "", 22.8460, 89.5410, "photon"));
        hits.add(new GeoRank.Hit("Star Pharmacy", "", 22.9000, 89.6000, "photon"));
        assertEquals(2, GeoRank.rank(hits, "star pharmacy", LAT, LON).size());
    }

    @Test
    public void banglaNamesMatch() {
        assertEquals(1.0, GeoRank.textScore("খুলনা", "খুলনা", ""), 0.001);
        assertTrue(GeoRank.textScore("খুলনা", "খুলনা বিশ্ববিদ্যালয়", "") >= 0.9);
    }

    @Test
    public void coordinatesAreUnderstood() {
        double[] a = GeoRank.parseCoordinates("22.8456, 89.5403");
        assertEquals(22.8456, a[0], 1e-9);
        assertEquals(89.5403, a[1], 1e-9);
        double[] b = GeoRank.parseCoordinates("22.8456 89.5403");
        assertEquals(89.5403, b[1], 1e-9);
        double[] c = GeoRank.parseCoordinates("33.9S 18.4E");
        assertEquals(-33.9, c[0], 1e-9);
        assertNull(GeoRank.parseCoordinates("Khulna 2"));
        assertNull(GeoRank.parseCoordinates("120.5, 10.1")); // latitude out of range
        assertNull(GeoRank.parseCoordinates("12345"));
    }
}
