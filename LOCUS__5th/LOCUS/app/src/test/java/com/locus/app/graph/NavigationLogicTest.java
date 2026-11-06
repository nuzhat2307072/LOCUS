package com.locus.app.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** Traffic estimation, alternative routes, red/amber/green runs and turn-by-turn guidance. */
public class NavigationLogicTest {

    private static Graph.Edge firstEdge(Graph g, long from) {
        return g.edges(from).get(0);
    }

    // ---- traffic model -----------------------------------------------------------------------

    @Test
    public void rushHoursAreBusyAndNightsAreQuiet() {
        assertTrue(TrafficModel.timeFactorAt(18.0, false) > 0.9);
        assertTrue(TrafficModel.timeFactorAt(9.0, false) > 0.9);
        assertTrue(TrafficModel.timeFactorAt(3.0, false) < 0.15);
        assertTrue(TrafficModel.timeFactorAt(18.0, true) < TrafficModel.timeFactorAt(18.0, false));
        assertTrue(TrafficModel.timeFactorAt(14.0, false) < TrafficModel.timeFactorAt(18.0, false));
    }

    @Test
    public void mainRoadsJamBeforeLanesAndFootpathsNever() {
        Graph g = new Graph();
        g.addNode(1, 22.0, 89.0);
        g.addNode(2, 22.0, 89.001);
        g.addNode(3, 22.001, 89.0);
        g.addNode(4, 22.001, 89.001);
        g.addSegment(1, 2, "primary", 0);
        g.addSegment(3, 4, "residential", 0);
        g.addSegment(1, 3, "footway", 0);
        TrafficModel rush = TrafficModel.rushHour();
        assertEquals(TrafficModel.HEAVY, TrafficModel.levelOf(rush.load(firstEdge(g, 2))));
        assertTrue(rush.load(firstEdge(g, 1)) > rush.load(firstEdge(g, 4)));
        assertEquals(0.0, rush.load(g.edges(3).get(0).highway.equals("footway") ? g.edges(3).get(0) : g.edges(3).get(1)), 1e-9);
    }

    @Test
    public void trafficEstimateIsStableForTheSameMoment() {
        Graph g = new Graph();
        g.addNode(1, 22.0, 89.0);
        g.addNode(2, 22.0, 89.001);
        g.addSegment(1, 2, "secondary", 0);
        TrafficModel a = new TrafficModel(0.8, 5);
        TrafficModel b = new TrafficModel(0.8, 5);
        assertEquals(a.load(firstEdge(g, 1)), b.load(firstEdge(g, 1)), 0.0);
    }

    @Test
    public void walkingIgnoresTrafficCarsDoNot() {
        Graph g = new Graph();
        g.addNode(1, 22.0, 89.0);
        g.addNode(2, 22.0, 89.002);
        g.addSegment(1, 2, "primary", 0);
        Graph.Edge e = firstEdge(g, 1);
        CostModel walkFree = new CostModel(CostModel.Mode.WALK, 0, null);
        CostModel walkRush = new CostModel(CostModel.Mode.WALK, 0, TrafficModel.rushHour());
        assertEquals(walkFree.cost(e), walkRush.cost(e), 1e-9);
        assertEquals(TrafficModel.FREE, walkRush.trafficLevel(e));
        CostModel carFree = new CostModel(CostModel.Mode.CAR, 0, null);
        CostModel carRush = new CostModel(CostModel.Mode.CAR, 0, TrafficModel.rushHour());
        assertTrue(carRush.cost(e) > carFree.cost(e) * 2);
        assertEquals(TrafficModel.HEAVY, carRush.trafficLevel(e));
    }

    @Test
    public void rainSlowsEveryoneDown() {
        Graph g = new Graph();
        g.addNode(1, 22.0, 89.0);
        g.addNode(2, 22.0, 89.002);
        g.addSegment(1, 2, "residential", 0);
        Graph.Edge e = firstEdge(g, 1);
        for (CostModel.Mode m : CostModel.Mode.values()) {
            double dry = new CostModel(m, 0, null).cost(e);
            double wet = new CostModel(m, 1, null).cost(e);
            double storm = new CostModel(m, 2, null).cost(e);
            assertTrue(m + " wet slower", wet > dry);
            assertTrue(m + " storm slower still", storm > wet);
        }
    }

    @Test
    public void reportedHazardShowsRed() {
        Graph g = new Graph();
        g.addNode(1, 22.0, 89.0);
        g.addNode(2, 22.0, 89.002);
        g.addSegment(1, 2, "residential", 0);
        g.applyHazard(22.0, 89.001, 300, false, 3.0);
        CostModel night = new CostModel(CostModel.Mode.CAR, 0, new TrafficModel(0.0, 1));
        assertEquals(TrafficModel.HEAVY, night.trafficLevel(firstEdge(g, 1)));
    }

    // ---- alternatives + coloured runs ----------------------------------------------------------

    /** Two corridors from 1 to 4: a short primary road on top and a longer residential lane below. */
    private static Graph twoCorridors() {
        Graph g = new Graph();
        g.addNode(1, 22.0000, 89.000);
        g.addNode(2, 22.0000, 89.002);
        g.addNode(3, 22.0000, 89.004);
        g.addNode(4, 22.0000, 89.006);
        g.addNode(5, 22.0010, 89.000);
        g.addNode(6, 22.0012, 89.002);
        g.addNode(7, 22.0012, 89.004);
        g.addNode(8, 22.0010, 89.006);
        g.addSegment(1, 2, "primary", 0);
        g.addSegment(2, 3, "primary", 0);
        g.addSegment(3, 4, "primary", 0);
        g.addSegment(1, 5, "residential", 0);
        g.addSegment(5, 6, "residential", 0);
        g.addSegment(6, 7, "residential", 0);
        g.addSegment(7, 8, "residential", 0);
        g.addSegment(8, 4, "residential", 0);
        return g;
    }

    @Test
    public void alternativesAreDifferentAndSortedFastestFirst() {
        Graph g = twoCorridors();
        CostModel cm = new CostModel(CostModel.Mode.CAR, 0, TrafficModel.rushHour());
        List<Router.Result> all = Router.alternatives(g, 1, 4, cm, 3);
        assertTrue("found at least two routes", all.size() >= 2);
        for (int i = 1; i < all.size(); i++) {
            assertTrue(all.get(i).costSeconds >= all.get(i - 1).costSeconds);
        }
        assertTrue(all.get(0).found && all.get(1).found);
        assertFalse(all.get(0).path.equals(all.get(1).path));
    }

    @Test
    public void jammedRouteIsRedAndTheQuietOneIsGreen() {
        Graph g = twoCorridors();
        CostModel cm = new CostModel(CostModel.Mode.CAR, 0, TrafficModel.rushHour());
        List<Router.Result> all = Router.alternatives(g, 1, 4, cm, 3);
        boolean sawRed = false;
        boolean sawGreen = false;
        for (Router.Result r : all) {
            boolean viaPrimary = r.path.size() == 4;
            List<Router.Run> runs = Router.runs(g, r.path, cm);
            assertFalse(runs.isEmpty());
            if (viaPrimary) {
                assertEquals(1, runs.size());
                assertEquals(TrafficModel.HEAVY, runs.get(0).level);
                sawRed = true;
            } else {
                assertEquals(TrafficModel.FREE, runs.get(0).level);
                sawGreen = true;
            }
        }
        assertTrue(sawRed && sawGreen);
    }

    @Test
    public void withoutTrafficTheShortPrimaryRoadWins() {
        Graph g = twoCorridors();
        CostModel cm = new CostModel(CostModel.Mode.CAR, 0, null);
        Router.Result best = Router.route(g, 1, 4, cm);
        assertEquals(4, best.path.size());
    }

    @Test
    public void closedRoadLeavesOnlyOneRoute() {
        Graph g = twoCorridors();
        g.applyHazard(22.0, 89.003, 120, true, 1.0); // primary road closed
        CostModel cm = new CostModel(CostModel.Mode.CAR, 0, null);
        List<Router.Result> all = Router.alternatives(g, 1, 4, cm, 3);
        assertEquals(1, all.size());
        assertTrue(all.get(0).path.size() > 4);
    }

    // ---- turn-by-turn ---------------------------------------------------------------------------

    private static Graph lShape(boolean turnRight) {
        Graph g = new Graph();
        g.addNode(1, 22.000, 89.000);
        g.addNode(2, 22.000, 89.001);
        g.addNode(3, 22.000, 89.002);
        double dir = turnRight ? -1 : 1;
        g.addNode(4, 22.000 + dir * 0.001, 89.002);
        g.addNode(5, 22.000 + dir * 0.002, 89.002);
        g.addSegment(1, 2, "residential", "Main Street", 0);
        g.addSegment(2, 3, "residential", "Main Street", 0);
        g.addSegment(3, 4, "residential", "Side Road", 0);
        g.addSegment(4, 5, "residential", "Side Road", 0);
        return g;
    }

    private static RouteGuide guide(Graph g) {
        Router.Result r = Router.route(g, 1, 5, new CostModel(CostModel.Mode.CAR, 0, null));
        assertTrue(r.found);
        return new RouteGuide(g, r.path);
    }

    @Test
    public void rightTurnIsAnnouncedWithTheStreetName() {
        RouteGuide rg = guide(lShape(true));
        List<RouteGuide.Step> steps = rg.steps();
        assertEquals(3, steps.size());
        assertEquals(RouteGuide.Maneuver.DEPART, steps.get(0).maneuver);
        assertEquals("Main Street", steps.get(0).street);
        assertEquals(RouteGuide.Maneuver.RIGHT, steps.get(1).maneuver);
        assertEquals("Side Road", steps.get(1).street);
        assertEquals(RouteGuide.Maneuver.ARRIVE, steps.get(2).maneuver);
        assertEquals(204.0, steps.get(1).atMeters, 6.0);
    }

    @Test
    public void leftTurnIsAnnounced() {
        RouteGuide rg = guide(lShape(false));
        assertEquals(RouteGuide.Maneuver.LEFT, rg.steps().get(1).maneuver);
    }

    @Test
    public void nextStepAdvancesAsYouDrive() {
        RouteGuide rg = guide(lShape(true));
        assertEquals(RouteGuide.Maneuver.RIGHT, rg.nextStep(10).maneuver);
        assertEquals(RouteGuide.Maneuver.RIGHT, rg.nextStep(150).maneuver);
        assertEquals(RouteGuide.Maneuver.ARRIVE, rg.nextStep(250).maneuver);
        assertEquals(RouteGuide.Maneuver.ARRIVE, rg.nextStep(10000).maneuver);
    }

    @Test
    public void locateTellsProgressAndDistanceOffRoute() {
        RouteGuide rg = guide(lShape(true));
        RouteGuide.Position onRoute = rg.locate(22.0, 89.001);
        assertEquals(102.0, onRoute.along, 4.0);
        assertEquals(0.0, onRoute.off, 1.0);
        RouteGuide.Position away = rg.locate(22.0005, 89.001);
        assertEquals(55.0, away.off, 4.0);
        assertTrue(rg.totalMeters() > 400);
    }

    @Test
    public void aBendMadeOfSeveralNodesIsOneTurn() {
        Graph g = new Graph();
        g.addNode(1, 22.00000, 89.00000);
        g.addNode(2, 22.00000, 89.00090);
        g.addNode(3, 21.99996, 89.00096);
        g.addNode(4, 21.99991, 89.00099);
        g.addNode(5, 21.99900, 89.00099);
        g.addSegment(1, 2, "residential", "A", 0);
        g.addSegment(2, 3, "residential", "A", 0);
        g.addSegment(3, 4, "residential", "B", 0);
        g.addSegment(4, 5, "residential", "B", 0);
        Router.Result r = Router.route(g, 1, 5, new CostModel(CostModel.Mode.CAR, 0, null));
        RouteGuide rg = new RouteGuide(g, r.path);
        int turns = 0;
        for (RouteGuide.Step s : rg.steps()) {
            if (s.maneuver != RouteGuide.Maneuver.DEPART && s.maneuver != RouteGuide.Maneuver.ARRIVE) turns++;
        }
        assertEquals(1, turns);
    }

    @Test
    public void straightRoadHasNoTurns() {
        Graph g = new Graph();
        for (int i = 0; i < 6; i++) g.addNode(i + 1, 22.0, 89.0 + i * 0.001);
        for (int i = 1; i < 6; i++) g.addSegment(i, i + 1, "residential", "Long Road", 0);
        Router.Result r = Router.route(g, 1, 6, new CostModel(CostModel.Mode.CAR, 0, null));
        RouteGuide rg = new RouteGuide(g, r.path);
        assertEquals(2, rg.steps().size()); // depart + arrive
    }

    // ---- weather ------------------------------------------------------------------------------

    @Test
    public void weatherReadingsBecomeARainLevel() {
        assertEquals(0, RainRules.level(0.0, 3));      // overcast, dry
        assertEquals(1, RainRules.level(0.4, 3));      // light rain measured
        assertEquals(2, RainRules.level(4.0, 3));      // heavy rain measured
        assertEquals(1, RainRules.level(0.0, 61));     // WMO "slight rain"
        assertEquals(2, RainRules.level(0.0, 65));     // WMO "heavy rain"
        assertEquals(2, RainRules.level(0.0, 95));     // thunderstorm
        assertEquals(0, RainRules.level(-1, -1));      // unknown -> assume dry
    }
}
