package com.locus.app.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Route finding on {@link Graph}.
 *
 * The app never asks the user which algorithm to use. {@link #route} decides by itself; the two
 * algorithms are only exposed through {@link #run} so they can be tested against each other.
 */
public final class Router {

    /** Internal detail - not shown anywhere in the UI. */
    public enum Algorithm {
        DIJKSTRA, ASTAR
    }

    public static final class Result {
        public final Algorithm algorithm;
        public final boolean found;
        public final List<Graph.Node> path;
        public final List<Graph.Node> visited;   // order in which nodes were expanded (drives the map animation)
        public final double distanceMeters;
        public final double costSeconds;
        public final long millis;

        Result(Algorithm algorithm, boolean found, List<Graph.Node> path, List<Graph.Node> visited,
               double distanceMeters, double costSeconds, long millis) {
            this.algorithm = algorithm;
            this.found = found;
            this.path = path;
            this.visited = visited;
            this.distanceMeters = distanceMeters;
            this.costSeconds = costSeconds;
            this.millis = millis;
        }
    }

    private static final class Entry implements Comparable<Entry> {
        final long id;
        final double g;
        final double f;

        Entry(long id, double g, double f) {
            this.id = id;
            this.g = g;
            this.f = f;
        }

        @Override
        public int compareTo(Entry o) {
            return Double.compare(f, o.f);
        }
    }

    private Router() {
    }

    /**
     * The single entry point used by the app: fastest route from src to dst for the given travel mode
     * and road conditions.
     *
     * A* is always used. It returns exactly the same optimal route as Dijkstra, but explores far fewer
     * roads because its heuristic (straight-line travel time at the mode's top speed) never
     * overestimates, so the answer is both correct and quick on a phone.
     */
    public static Result route(Graph g, long src, long dst, CostModel cm) {
        return run(Algorithm.ASTAR, g, src, dst, cm);
    }

    /** Runs one specific algorithm (used by tests to prove both give the same optimum). */
    public static Result run(Algorithm algo, Graph g, long src, long dst, CostModel cm) {
        long t0 = System.nanoTime();
        List<Graph.Node> visited = new ArrayList<>();
        Map<Long, Long> parent = new HashMap<>();
        boolean found = bestFirst(g, src, dst, cm, algo == Algorithm.ASTAR, parent, visited);
        List<Graph.Node> path = found ? buildPath(g, parent, src, dst) : Collections.<Graph.Node>emptyList();
        double dist = 0;
        double cost = 0;
        for (int i = 0; i + 1 < path.size(); i++) {
            Graph.Edge e = bestEdge(g, path.get(i).id, path.get(i + 1).id, cm);
            if (e != null) {
                dist += e.length;
                cost += cm.cost(e);
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        return new Result(algo, found, path, visited, dist, cost, ms);
    }

    /** Dijkstra (useHeuristic=false) and A* (useHeuristic=true). */
    private static boolean bestFirst(Graph g, long src, long dst, CostModel cm, boolean useHeuristic,
                                     Map<Long, Long> parent, List<Graph.Node> visited) {
        Graph.Node target = g.node(dst);
        Graph.Node source = g.node(src);
        if (target == null || source == null) return false;
        Map<Long, Double> best = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        PriorityQueue<Entry> pq = new PriorityQueue<>();
        best.put(src, 0.0);
        pq.add(new Entry(src, 0, useHeuristic ? cm.heuristic(source, target) : 0));
        while (!pq.isEmpty()) {
            Entry cur = pq.poll();
            if (!closed.add(cur.id)) continue;
            Graph.Node curNode = g.node(cur.id);
            if (curNode != null) visited.add(curNode);
            if (cur.id == dst) return true;
            for (Graph.Edge e : g.edges(cur.id)) {
                double c = cm.cost(e);
                if (c == Double.POSITIVE_INFINITY || closed.contains(e.to)) continue;
                Graph.Node toNode = g.node(e.to);
                if (toNode == null) continue;
                double ng = cur.g + c;
                Double old = best.get(e.to);
                if (old == null || ng < old) {
                    best.put(e.to, ng);
                    parent.put(e.to, cur.id);
                    double h = useHeuristic ? cm.heuristic(toNode, target) : 0;
                    pq.add(new Entry(e.to, ng, ng + h));
                }
            }
        }
        return false;
    }

    private static List<Graph.Node> buildPath(Graph g, Map<Long, Long> parent, long src, long dst) {
        ArrayList<Graph.Node> rev = new ArrayList<>();
        long cur = dst;
        Graph.Node startNode = g.node(cur);
        if (startNode != null) rev.add(startNode);
        int maxSteps = g.nodeCount() + 1;
        while (cur != src && rev.size() < maxSteps) {
            Long p = parent.get(cur);
            if (p == null || p == cur) return Collections.emptyList();
            cur = p;
            Graph.Node n = g.node(cur);
            if (n != null) rev.add(n);
        }
        if (cur != src) return Collections.emptyList();
        Collections.reverse(rev);
        return rev;
    }

    private static Graph.Edge bestEdge(Graph g, long a, long b, CostModel cm) {
        Graph.Edge best = null;
        double bestC = Double.POSITIVE_INFINITY;
        for (Graph.Edge e : g.edges(a)) {
            if (e.to != b) continue;
            double c = cm.cost(e);
            if (c < bestC) {
                bestC = c;
                best = e;
            }
        }
        return best;
    }
}
