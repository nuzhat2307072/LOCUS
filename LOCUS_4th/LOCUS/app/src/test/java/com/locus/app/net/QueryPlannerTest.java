package com.locus.app.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** JVM unit tests for the free-text place search planner. */
public class QueryPlannerTest {

    private static boolean anyContains(List<String> filters, String text) {
        for (String f : filters) if (f.contains(text)) return true;
        return false;
    }

    @Test
    public void normalizeRemovesAnythingDangerous() {
        String n = QueryPlanner.normalize("  Pizza\"]; out;  (x)[y]\\ ");
        assertFalse(n.contains("\""));
        assertFalse(n.contains("["));
        assertFalse(n.contains("("));
        assertFalse(n.contains("\\"));
        assertFalse(n.contains(";"));
        assertEquals("pizza out x y", n);
    }

    @Test
    public void normalizeKeepsLettersOfAnyScriptAndLimitsLength() {
        assertEquals("ফার্মেসি", QueryPlanner.normalize("ফার্মেসি"));
        assertEquals("domino's pizza", QueryPlanner.normalize("Domino's   Pizza"));
        assertTrue(QueryPlanner.normalize("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").length() <= 40);
        assertEquals("", QueryPlanner.normalize(null));
    }

    @Test
    public void unknownWordStillSearchesTagsAndNames() {
        List<String> f = QueryPlanner.filtersFor("dentist");
        assertTrue(anyContains(f, "[\"amenity\"~\"^(dentist)$\"]")); // dictionary
        assertTrue(anyContains(f, "(^|;)dentists?(;|$)"));            // generic tag value
        assertTrue(anyContains(f, "\"^(name|name:en|brand)$\"~\"dentist\""));
        List<String> g = QueryPlanner.filtersFor("zebra cafe");
        assertTrue(anyContains(g, "zebra"));                          // not in the dictionary, still searched
    }

    @Test
    public void synonymsMapToTheRightPlaces() {
        assertTrue(anyContains(QueryPlanner.filtersFor("medicine"), "pharmacy"));
        assertTrue(anyContains(QueryPlanner.filtersFor("petrol"), "fuel"));
        assertTrue(anyContains(QueryPlanner.filtersFor("parlour"), "hairdresser"));
        assertTrue(anyContains(QueryPlanner.filtersFor("laptop repair"), "electronics_repair"));
        assertTrue(anyContains(QueryPlanner.filtersFor("masjid"), "muslim"));
        assertTrue(anyContains(QueryPlanner.filtersFor("ফার্মেসি"), "pharmacy"));
    }

    @Test
    public void hindiAndBanglaWordsAreUnderstood() {
        assertTrue(anyContains(QueryPlanner.filtersFor("दवाई"), "pharmacy"));
        assertTrue(anyContains(QueryPlanner.filtersFor("अस्पताल"), "hospital"));
        assertTrue(anyContains(QueryPlanner.filtersFor("পেট্রোল"), "fuel"));
        assertTrue(anyContains(QueryPlanner.filtersFor("पुलिस"), "police"));
    }

    @Test
    public void dictionaryTriggersAreWholeWords() {
        // "lab" (diagnostic labs) must not fire for "label"
        assertFalse(anyContains(QueryPlanner.filtersFor("label"), "laborator"));
        assertTrue(anyContains(QueryPlanner.filtersFor("lab"), "laborator"));
        // plural of a trigger word still matches ("pharmacys" is the planner's plural form of "pharmacy")
        assertTrue(anyContains(QueryPlanner.filtersFor("drugs"), "pharmacy"));
    }

    @Test
    public void fillerWordsAreIgnored() {
        List<String> terms = QueryPlanner.terms("best pizza near me");
        assertEquals(1, terms.size());
        assertEquals("pizza", terms.get(0));
        // a query made only of filler words still searches something
        assertEquals(1, QueryPlanner.terms("shop").size());
    }

    @Test
    public void filtersAreBoundedAndCompleteStatements() {
        List<String> f = QueryPlanner.filtersFor("pharmacy medicine doctor clinic hospital dentist");
        assertTrue(f.size() < 40);
        for (String s : f) {
            assertTrue(s, s.startsWith("["));
            assertTrue(s, s.endsWith("]"));
        }
    }

    @Test
    public void labelsAreReadable() {
        Map<String, String> t = new HashMap<>();
        t.put("amenity", "restaurant");
        t.put("cuisine", "pizza;italian");
        assertEquals("Pizza restaurant", QueryPlanner.labelFor(t));
        t.clear();
        t.put("shop", "bakery");
        assertEquals("Bakery", QueryPlanner.labelFor(t));
        t.clear();
        t.put("amenity", "fast_food");
        assertEquals("Fast food", QueryPlanner.labelFor(t));
        assertEquals("Place", QueryPlanner.labelFor(new HashMap<String, String>()));
    }
}
