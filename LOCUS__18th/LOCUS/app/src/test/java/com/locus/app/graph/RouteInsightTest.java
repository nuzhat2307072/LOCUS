package com.locus.app.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Hazards on a route and the "shortest or safest" recommendation. */
public class RouteInsightTest {
    // a straight road going east along latitude 22.80; 0.001 degrees of longitude is about 102 m
    private static List<double[]> eastRoad() {
        List<double[]> p = new ArrayList<>();
        p.add(new double[]{22.80, 89.500});
        p.add(new double[]{22.80, 89.510});
        return p;
    }

    @Test
    public void distanceIsMeasuredToTheSegmentNotJustTheCorners() {
        // 0.0009 degrees north of the middle of the road = about 99 m from the road, but ~500 m from its corners
        double d = RouteInsight.distanceToPath(eastRoad(), 22.8009, 89.505);
        assertEquals(99.5, d, 3.0);
        assertEquals(0, RouteInsight.distanceToPath(eastRoad(), 22.80, 89.505), 0.5);
        // beyond the end of the road the nearest point is the corner
        assertEquals(102, RouteInsight.distanceToPath(eastRoad(), 22.80, 89.511), 3.0);
        assertTrue(Double.isInfinite(RouteInsight.distanceToPath(new ArrayList<>(), 22.8, 89.5)));
    }

    @Test
    public void aRoutePassesAHazardOnlyWhenItComesWithinItsRadius() {
        List<RouteInsight.Spot> spots = Arrays.asList(
                new RouteInsight.Spot(22.8002, 89.505, 80, false),   // 22 m from the road: passes
                new RouteInsight.Spot(22.8009, 89.505, 50, true),    // 99 m away, radius 50 (+30): does not pass
                new RouteInsight.Spot(22.8005, 89.502, 100, true));  // 55 m away, radius 100: passes, verified
        RouteInsight.Exposure e = RouteInsight.exposure(eastRoad(), spots);
        assertEquals(2, e.hazards);
        assertEquals(1, e.verified);
    }

    @Test
    public void verifiedHazardsCostMoreThanUnverifiedOnes() {
        assertEquals(0, RouteInsight.riskSeconds(new RouteInsight.Exposure(0, 0)), 0.001);
        assertEquals(120, RouteInsight.riskSeconds(new RouteInsight.Exposure(1, 0)), 0.001);
        assertEquals(180, RouteInsight.riskSeconds(new RouteInsight.Exposure(1, 1)), 0.001);
        assertEquals(300, RouteInsight.riskSeconds(new RouteInsight.Exposure(2, 1)), 0.001);
        assertEquals(900, RouteInsight.safetyScore(600, new RouteInsight.Exposure(2, 1)), 0.001);
    }

    @Test
    public void safestModePrefersALongerRouteThatAvoidsHazardsShortestModeDoesNot() {
        double[] meters = {4000, 4600};
        double[] seconds = {600, 700};
        int[] hazards = {2, 0};
        double[] score = {
                RouteInsight.safetyScore(600, new RouteInsight.Exposure(2, 1)),   // 900
                RouteInsight.safetyScore(700, new RouteInsight.Exposure(0, 0))};  // 700
        assertEquals(0, RouteInsight.best(meters, score, false));
        assertEquals(1, RouteInsight.best(meters, score, true));
        RouteInsight.Why w = RouteInsight.why(1, meters, seconds, hazards);
        assertEquals(RouteInsight.SAFER, w.type);
        assertEquals(2, w.avoided);
        assertEquals(600, w.extraMeters, 0.001);
        assertEquals(100, w.extraSeconds, 0.001);
    }

    @Test
    public void aSmallDetourIsNotWorthAHarmlessHazardCostingNothingExtra() {
        // the longer route is 10 minutes slower: even with a 2-minute hazard penalty the short one still wins
        double[] meters = {4000, 9000};
        double[] score = {RouteInsight.safetyScore(600, new RouteInsight.Exposure(1, 0)),
                RouteInsight.safetyScore(1200, new RouteInsight.Exposure(0, 0))};
        assertEquals(0, RouteInsight.best(meters, score, true));
        RouteInsight.Why w = RouteInsight.why(0, meters, new double[]{600, 1200}, new int[]{1, 0});
        assertEquals(RouteInsight.EXPOSED, w.type);
        assertEquals(1, w.hazards);
    }

    @Test
    public void aCleanRecommendedRouteIsReportedAsClear() {
        RouteInsight.Why w = RouteInsight.why(0, new double[]{3000, 3500}, new double[]{400, 450}, new int[]{0, 1});
        assertEquals(RouteInsight.CLEAR, w.type);
        assertEquals(0, w.hazards);
    }
}
