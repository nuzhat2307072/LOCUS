package com.locus.app.net;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * How well does a place answer what the person typed?
 *  TYPE  (3) - it IS that kind of place (amenity=pharmacy for "medicine", cuisine=pizza for "pizza"),
 *  NAME  (2) - its name contains what was typed ("Pizza Hut", "Lazz Pharma"),
 *  WEAK  (1) - only a loose text match.
 * The result list is ordered by {@link #rank}, so a real pharmacy 600 m away beats a shop that merely has
 * "pharma" in its name at 100 m. Pure Java, unit-tested.
 */
public final class Relevance {
    public static final int TYPE = 3;
    public static final int NAME = 2;
    public static final int WEAK = 1;

    /** One relevance step is worth this many metres of extra walking. */
    private static final double METRES_PER_TIER = 1200;

    private Relevance() {
    }

    /**
     * @param norm        the normalised query
     * @param typeValues  OSM values the query stands for (from {@link QueryPlanner#typeValuesFor}), e.g. "pharmacy"
     * @param name        the place's name (may be empty)
     * @param placeValues the place's own type values (amenity, shop, cuisine ...), lower case, "_" allowed
     */
    public static int tier(String norm, Set<String> typeValues, String name, Collection<String> placeValues) {
        List<String> terms = QueryPlanner.terms(norm);
        for (String v : placeValues) {
            if (v == null || v.isEmpty()) continue;
            if (typeValues.contains(v)) return TYPE;
            String spaced = v.replace('_', ' ');
            for (String t : terms) {
                if (t.length() >= 3 && (spaced.equals(t) || spaced.equals(t + "s") || (spaced + "s").equals(t))) {
                    return TYPE;
                }
            }
            if (spaced.equals(norm)) return TYPE;
        }
        String n = QueryPlanner.normalize(name);
        if (!n.isEmpty()) {
            if (containsWord(n, norm)) return NAME;
            boolean all = !terms.isEmpty();
            for (String t : terms) {
                if (!containsWord(n, t)) {
                    all = false;
                    break;
                }
            }
            if (all) return NAME;
        }
        return WEAK;
    }

    /** Smaller is better: distance in metres, minus a bonus for a better match. */
    public static double rank(int tier, double distanceMeters) {
        return distanceMeters - tier * METRES_PER_TIER;
    }

    private static boolean containsWord(String text, String word) {
        if (word.isEmpty()) return false;
        int from = 0;
        while (true) {
            int i = text.indexOf(word, from);
            if (i < 0) return false;
            boolean startOk = i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1));
            int e = i + word.length();
            boolean endOk = e == text.length() || !Character.isLetterOrDigit(text.charAt(e))
                    || (text.charAt(e) == 's' && (e + 1 == text.length() || !Character.isLetterOrDigit(text.charAt(e + 1))));
            if (startOk && endOk) return true;
            from = i + 1;
        }
    }
}
