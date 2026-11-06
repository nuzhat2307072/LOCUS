package com.locus.app.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

/** Opening hours, relevance and the "best match" ordering of Explore Places. */
public class ExploreRankingTest {
    private static final int MO = 0, TU = 1, WE = 2, SA = 5, SU = 6;

    private static int m(int h, int min) {
        return h * 60 + min;
    }

    @Test
    public void simpleDailyHours() {
        OpeningHours.Status s = OpeningHours.check("Mo-Su 08:00-22:00", WE, m(10, 0));
        assertEquals(OpeningHours.State.OPEN, s.state);
        assertEquals(m(22, 0), s.changeAtMinute);
        s = OpeningHours.check("Mo-Su 08:00-22:00", WE, m(23, 0));
        assertEquals(OpeningHours.State.CLOSED, s.state);
        s = OpeningHours.check("Mo-Su 08:00-22:00", WE, m(6, 0));
        assertEquals(OpeningHours.State.CLOSED, s.state);
        assertEquals(m(8, 0), s.changeAtMinute);
    }

    @Test
    public void differentDaysAndOff() {
        String h = "Mo-Fr 09:00-17:00; Sa 10:00-14:00; Su off";
        assertEquals(OpeningHours.State.OPEN, OpeningHours.check(h, SA, m(12, 0)).state);
        assertEquals(OpeningHours.State.CLOSED, OpeningHours.check(h, SA, m(15, 0)).state);
        assertEquals(OpeningHours.State.CLOSED, OpeningHours.check(h, SU, m(12, 0)).state);
        assertEquals(OpeningHours.State.OPEN, OpeningHours.check(h, TU, m(9, 0)).state);
    }

    @Test
    public void lunchBreakAndCommaSeparatedRules() {
        String h = "Mo-Fr 09:00-12:00,14:00-18:00, Sa 09:00-14:00";
        OpeningHours.Status s = OpeningHours.check(h, MO, m(13, 0));
        assertEquals(OpeningHours.State.CLOSED, s.state);
        assertEquals(m(14, 0), s.changeAtMinute);
        assertEquals(OpeningHours.State.OPEN, OpeningHours.check(h, SA, m(10, 0)).state);
    }

    @Test
    public void allDayAndOvernight() {
        OpeningHours.Status s = OpeningHours.check("24/7", TU, m(3, 0));
        assertEquals(OpeningHours.State.OPEN, s.state);
        assertTrue(s.allDay);
        // opened Monday 18:00, still open Tuesday 01:00
        assertEquals(OpeningHours.State.OPEN, OpeningHours.check("Mo-Su 18:00-02:00", TU, m(1, 0)).state);
        assertEquals(OpeningHours.State.CLOSED, OpeningHours.check("Mo-Su 18:00-02:00", TU, m(3, 0)).state);
    }

    @Test
    public void unsupportedTextIsUnknownNotClosed() {
        assertEquals(OpeningHours.State.UNKNOWN, OpeningHours.check("sunrise-sunset", MO, m(12, 0)).state);
        assertEquals(OpeningHours.State.UNKNOWN, OpeningHours.check("Jan-Mar Mo 09:00-10:00", MO, m(9, 30)).state);
        assertEquals(OpeningHours.State.UNKNOWN, OpeningHours.check("", MO, 0).state);
        assertEquals(OpeningHours.State.UNKNOWN, OpeningHours.check(null, MO, 0).state);
        assertEquals(OpeningHours.State.UNKNOWN, OpeningHours.check("08:00+", MO, m(9, 0)).state);
    }

    @Test
    public void publicHolidayRulesAreIgnored() {
        assertEquals(OpeningHours.State.OPEN,
                OpeningHours.check("Mo-Su 09:00-18:00; PH off", WE, m(10, 0)).state);
    }

    @Test
    public void calendarDayMapsToMondayFirst() {
        assertEquals(0, OpeningHours.dayIndex(java.util.Calendar.MONDAY));
        assertEquals(6, OpeningHours.dayIndex(java.util.Calendar.SUNDAY));
        assertEquals(5, OpeningHours.dayIndex(java.util.Calendar.SATURDAY));
    }

    @Test
    public void typeMatchBeatsNameOnlyMatch() {
        Set<String> types = QueryPlanner.typeValuesFor("medicine");
        assertTrue(types.contains("pharmacy"));
        int real = Relevance.tier("medicine", types, "", Arrays.asList("pharmacy"));
        int nameOnly = Relevance.tier("medicine", types, "Medicine Corner Tea Stall", Arrays.asList("cafe"));
        int loose = Relevance.tier("medicine", types, "Rahim Store", Arrays.asList("convenience"));
        assertEquals(Relevance.TYPE, real);
        assertEquals(Relevance.NAME, nameOnly);
        assertEquals(Relevance.WEAK, loose);
    }

    @Test
    public void cuisineAndWordsCountAsType() {
        Set<String> types = QueryPlanner.typeValuesFor("pizza");
        assertEquals(Relevance.TYPE, Relevance.tier("pizza", types, "Dough House",
                Arrays.asList("restaurant", "pizza")));
        assertEquals(Relevance.NAME, Relevance.tier("pizza", types, "Pizza Hut", Arrays.asList("restaurant")));
        // an unknown word still matches a place of that exact type
        assertEquals(Relevance.TYPE, Relevance.tier("florist", QueryPlanner.typeValuesFor("florist"), "",
                Collections.singletonList("florist")));
    }

    @Test
    public void nameMatchNeedsWholeWords() {
        Set<String> none = Collections.emptySet();
        assertEquals(Relevance.WEAK, Relevance.tier("pen", none, "Open Air Cafe", Collections.<String>emptyList()));
        assertEquals(Relevance.NAME, Relevance.tier("pen", none, "Pen House", Collections.<String>emptyList()));
        assertEquals(Relevance.NAME, Relevance.tier("laptop repair", none, "Best Repair for Laptop",
                Collections.<String>emptyList()));
    }

    @Test
    public void rankPrefersARelevantPlaceAFewHundredMetresFurther() {
        double realPharmacy = Relevance.rank(Relevance.TYPE, 700);
        double nameOnly = Relevance.rank(Relevance.NAME, 150);
        assertTrue(realPharmacy < nameOnly);
        // ... but not one that is much further away
        assertTrue(Relevance.rank(Relevance.TYPE, 5000) > Relevance.rank(Relevance.NAME, 150));
        // equal match: nearer wins
        assertTrue(Relevance.rank(Relevance.TYPE, 300) < Relevance.rank(Relevance.TYPE, 400));
    }

    @Test
    public void dictionaryTypesNeverIncludeNameFilters() {
        Set<String> t = QueryPlanner.typeValuesFor("mosque");
        assertTrue(t.contains("place_of_worship"));
        assertTrue(!t.contains("name"));
        assertTrue(!t.contains("brand"));
    }
}
