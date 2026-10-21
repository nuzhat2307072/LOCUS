package com.locus.app.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.Set;

/** JVM unit tests for the routing core. Run with: ./gradlew test */
public class GraphRouterTest {

    private Graph g;

    /**
     * 3x3 grid of residential roads, ~111 m between neighbours:
     *   1 - 2 - 3
     *   |   |   |
     *   4 - 5 - 6
     *   |   |   |
     *   7 - 8 - 9
     */
    @Before
    public void buildGrid() {
        g = new Graph();
        long id = 1;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                g.addNode(id++, 22.0 - r * 0.001, 89.0 + c * 0.001);
            }
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                long n = r * 3 + c + 1;
                if (c < 2) g.addSegment(n, n + 1, "residential", 0);
                if (r < 2) g.addSegment(n, n + 3, "residential", 0);
            }
        }
    }

    private static CostModel car() {
        return new CostModel(CostModel.Mode.CAR, false, false);
    }

    @Test
    public void appRouteUsesAstarAndMatchesDijkstra() {
        Router.Result app = Router.route(g, 1, 9, car());
        Router.Result dij = Router.run(Router.Algorithm.DIJKSTRA, g, 1, 9, car());
        assertTrue(app.found && dij.found);
        assertEquals(Router.Algorithm.ASTAR, app.algorithm);
        assertEquals(dij.costSeconds, app.costSeconds, 1e-6); // same optimum
        assertEquals(5, app.path.size());                    // 4 segments
        assertTrue(app.visited.size() <= dij.visited.size()); // A* never explores more
    }

    @Test
    public void blockedRoadForcesDetour() {
        Router.Result before = Router.route(g, 1, 3, car());
        assertEquals(3, before.path.size()); // 1-2-3
        Graph.Node n2 = g.node(2);
        g.applyHazard(n2.lat, n2.lon, 20, true, 1.0); // close the road around node 2
        Router.Result after = Router.route(g, 1, 3, car());
        assertTrue(after.found);
        assertTrue(after.distanceMeters > before.distanceMeters);
        assertFalse(after.path.contains(n2));
        g.resetConditions();
        assertEquals(before.costSeconds, Router.route(g, 1, 3, car()).costSeconds, 1e-6);
    }

    @Test
    public void noRouteWhenEverythingIsBlocked() {
        for (Graph.Node n : g.nodes()) g.applyHazard(n.lat, n.lon, 5, true, 1.0);
        assertFalse(Router.route(g, 1, 9, car()).found);
    }

    @Test
    public void onewayIsRespectedByCarsButNotWalkers() {
        Graph h = new Graph();
        h.addNode(1, 22.0, 89.0);
        h.addNode(2, 22.0, 89.001);
        h.addSegment(1, 2, "residential", 1); // one way 1 -> 2
        assertTrue(Router.route(h, 1, 2, car()).found);
        assertFalse(Router.route(h, 2, 1, car()).found);
        CostModel walk = new CostModel(CostModel.Mode.WALK, false, false);
        assertTrue(Router.route(h, 2, 1, walk).found);
    }

    @Test
    public void carsCannotUseFootways() {
        Graph h = new Graph();
        h.addNode(1, 22.0, 89.0);
        h.addNode(2, 22.0, 89.001);
        h.addSegment(1, 2, "footway", 0);
        assertFalse(Router.route(h, 1, 2, car()).found);
        assertTrue(Router.route(h, 1, 2, new CostModel(CostModel.Mode.WALK, false, false)).found);
    }

    @Test
    public void rainAndTrafficMakeTripsSlower() {
        Router.Result dry = Router.route(g, 1, 9, car());
        Router.Result wet = Router.route(g, 1, 9, new CostModel(CostModel.Mode.CAR, true, true));
        assertTrue(wet.costSeconds > dry.costSeconds);
    }

    @Test
    public void nearestNodeSnapsToClosest() {
        // lat 21.9981 is closest to the bottom row (21.998), lon 89.0009 to the middle column
        Graph.Node n = g.nearest(21.9981, 89.0009, null);
        assertEquals(8L, n.id);
    }

    @Test
    public void snappingIgnoresDisconnectedIslands() {
        // an isolated footpath fragment sits right next to the query point, but the main network is the grid
        g.addNode(100, 22.0005, 89.0005);
        g.addNode(101, 22.0006, 89.0005);
        g.addSegment(100, 101, "residential", 0);

        Graph.Node naive = g.nearest(22.00055, 89.0005, null);
        assertTrue(naive.id == 100L || naive.id == 101L); // would strand the router on the island

        Set<Long> mainland = g.largestComponent(car());
        assertFalse(mainland.contains(100L));
        Graph.Node snapped = g.nearest(22.00055, 89.0005, mainland);
        assertTrue(Router.route(g, snapped.id, 9, car()).found);
    }

    @Test
    public void closedRoadsDoNotCountAsConnected() {
        Graph.Node n5 = g.node(5);
        g.applyHazard(n5.lat, n5.lon, 20, true, 1.0);
        Set<Long> mainland = g.largestComponent(car());
        assertFalse(mainland.contains(5L)); // node 5 is cut off, so a start there snaps elsewhere
    }
}
