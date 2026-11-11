package com.locus.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.locus.app.model.ImageRules;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Voice cues, shake gesture, recent destinations, share links and photo sizes. */
public class UpgradeRulesTest {
    // ---- voice cues
    @Test
    public void aTurnIsAnnouncedEarlyAndThenNowEachOnce() {
        VoiceCues c = new VoiceCues(300, 100);
        assertEquals(VoiceCues.NONE, c.check(500, 800));
        assertEquals(VoiceCues.EARLY, c.check(500, 290));
        assertEquals(VoiceCues.NONE, c.check(500, 250));
        assertEquals(VoiceCues.NOW, c.check(500, 90));
        assertEquals(VoiceCues.NONE, c.check(500, 60));
    }

    @Test
    public void joiningInsideTheNowZoneSpeaksOnlyOnce() {
        VoiceCues c = new VoiceCues(300, 100);
        assertEquals(VoiceCues.NOW, c.check(900, 50));
        assertEquals(VoiceCues.NONE, c.check(900, 40));
    }

    @Test
    public void nextTurnStartsFresh() {
        VoiceCues c = VoiceCues.forSpeed(12);
        assertEquals(VoiceCues.EARLY, c.check(1, 350));
        assertEquals(VoiceCues.NOW, c.check(1, 100));
        assertEquals(VoiceCues.EARLY, c.check(2, 380));
        c.reset();
        assertEquals(VoiceCues.EARLY, c.check(2, 380));
    }

    @Test
    public void walkingCuesComeLaterThanDrivingCues() {
        assertEquals(VoiceCues.NONE, VoiceCues.forSpeed(1.4).check(1, 150));
        assertEquals(VoiceCues.EARLY, VoiceCues.forSpeed(12).check(1, 150));
    }

    // ---- shake
    private static final double SHAKE = 30; // well above 22

    @Test
    public void threeQuickShakesTrigger() {
        ShakeDetector d = new ShakeDetector();
        assertFalse(d.onReading(SHAKE, 0, 0, 1000));
        assertFalse(d.onReading(0, SHAKE, 0, 1300));
        assertTrue(d.onReading(0, 0, SHAKE, 1600));
    }

    @Test
    public void gravityAndSlowShakesDoNot() {
        ShakeDetector d = new ShakeDetector();
        for (int i = 0; i < 50; i++) assertFalse(d.onReading(0, 0, 9.8, 1000 + i * 20L));
        assertFalse(d.onReading(SHAKE, 0, 0, 5000));
        assertFalse(d.onReading(SHAKE, 0, 0, 6500));
        assertFalse(d.onReading(SHAKE, 0, 0, 8000)); // 3 s for three shakes: too slow
    }

    @Test
    public void oneJoltSeenTwiceIsOneShakeAndItPausesAfterFiring() {
        ShakeDetector d = new ShakeDetector();
        assertFalse(d.onReading(SHAKE, 0, 0, 1000));
        assertFalse(d.onReading(SHAKE, 0, 0, 1050)); // same jolt
        assertFalse(d.onReading(SHAKE, 0, 0, 1300));
        assertTrue(d.onReading(SHAKE, 0, 0, 1600));
        assertFalse(d.onReading(SHAKE, 0, 0, 1900)); // paused for 10 s
        assertFalse(d.onReading(SHAKE, 0, 0, 2200));
        assertFalse(d.onReading(SHAKE, 0, 0, 2500));
    }

    // ---- recent destinations
    @Test
    public void recentsAreNewestFirstWithoutRepeats() {
        List<String> lines = new ArrayList<>();
        lines = RecentPlaces.push(lines, "Hospital", 22.80, 89.54);
        lines = RecentPlaces.push(lines, "Market", 22.81, 89.55);
        lines = RecentPlaces.push(lines, "hospital", 22.80001, 89.54001); // same place again
        List<RecentPlaces.Item> items = RecentPlaces.parse(lines);
        assertEquals(2, items.size());
        assertEquals("hospital", items.get(0).name);
        assertEquals("Market", items.get(1).name);
    }

    @Test
    public void recentsAreLimitedAndBadLinesIgnored() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 12; i++) lines = RecentPlaces.push(lines, "Place " + i, 22 + i * 0.01, 89);
        assertEquals(RecentPlaces.MAX, lines.size());
        lines.add("broken");
        lines.add("x|abc|def");
        assertEquals(RecentPlaces.MAX, RecentPlaces.parse(lines).size());
        assertEquals(RecentPlaces.MAX, RecentPlaces.push(lines, "  ", 1, 1).size() > 0 ? RecentPlaces.MAX : 0);
        assertEquals("Place 11", RecentPlaces.parse(lines).get(0).name);
    }

    @Test
    public void namesWithSeparatorsAreCleaned() {
        List<String> lines = RecentPlaces.push(new ArrayList<>(), "A|B\nC", 1, 2);
        assertEquals("A B C", RecentPlaces.parse(lines).get(0).name);
    }

    // ---- share links
    @Test
    public void placeAndRouteLinks() {
        assertEquals("https://www.openstreetmap.org/?mlat=22.800000&mlon=89.540000#map=17/22.800000/89.540000",
                ShareLinks.placeUrl(22.8, 89.54));
        String url = ShareLinks.routeUrl("car", 22.8, 89.5, 22.9, 89.6);
        assertTrue(url.contains("engine=fossgis_osrm_car"));
        assertTrue(url.endsWith("route=22.800000,89.500000;22.900000,89.600000"));
        assertTrue(ShareLinks.routeUrl("cycle", 1, 2, 3, 4).contains("osrm_bike"));
        assertTrue(ShareLinks.routeUrl("walk", 1, 2, 3, 4).contains("osrm_foot"));
    }

    // ---- photo sizes
    @Test
    public void photosAreShrunkToTheLimit() {
        assertEquals(1, ImageRules.sampleSize(640, 480));
        assertEquals(4, ImageRules.sampleSize(4000, 3000)); // 1000 px still >= 720
        int[] f = ImageRules.fit(1000, 500);
        assertEquals(720, f[0]);
        assertEquals(360, f[1]);
        int[] same = ImageRules.fit(300, 200);
        assertEquals(300, same[0]);
        assertEquals(200, same[1]);
    }

    @Test
    public void base64LimitMatchesTheDatabaseRule() {
        assertEquals(4, ImageRules.base64Length(3));
        assertEquals(8, ImageRules.base64Length(4));
        assertTrue(ImageRules.fits(100_000));       // 133,336 characters
        assertFalse(ImageRules.fits(115_000));      // 153,336 characters: over 150,000
    }
}
