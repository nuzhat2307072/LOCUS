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

    /** Cost of one edge; lets alternatives temporarily make already-used roads less attractive. */
    private interface EdgeCost {
        double cost(Graph.Edge e);
    }

    /** A stretch of the route with one traffic level (for colouring the line on the map). */
    public static final class Run {
        public final int level;
        public final List<double[]> points = new ArrayList<>(); // {lat, lon}

        public Run(int level) {
            this.level = level;
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
        boolean found = bestFirst(g, src, dst, cm, cm::cost, algo == Algorithm.ASTAR, parent, visited);
        List<Graph.Node> path = found ? buildPath(g, parent, src, dst) : Collections.<Graph.Node>emptyList();
        return evaluate(algo, found, path, visited, g, cm, t0);
    }

    /** Distance and time of a finished path, always measured with the TRUE costs. */
    private static Result evaluate(Algorithm algo, boolean found, List<Graph.Node> path, List<Graph.Node> visited,
                                   Graph g, CostModel cm, long t0) {
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

    /**
     * All sensible ways to get there, best first (at most {@code max}). The first is the fastest route
     * with the current traffic and weather; the others are found by making the roads already used less
     * attractive, so they really differ, and are dropped when they are far slower or nearly the same.
     */
    public static List<Result> alternatives(Graph g, long src, long dst, final CostModel cm, int max) {
        List<Result> out = new ArrayList<>();
        Result best = route(g, src, dst, cm);
        if (!best.found) return out;
        out.add(best);
        final Set<Long> used = new HashSet<>();
        addKeys(used, best.path);
        for (int attempt = 0; attempt < 6 && out.size() < max; attempt++) {
            final double pen = 1.8 + 0.4 * attempt;
            long t0 = System.nanoTime();
            List<Graph.Node> visited = new ArrayList<>();
            Map<Long, Long> parent = new HashMap<>();
            EdgeCost penalised = e -> {
                double base = cm.cost(e);
                return used.contains(key(e.from, e.to)) ? base * pen : base;
            };
            if (!bestFirst(g, src, dst, cm, penalised, true, parent, visited)) break;
            List<Graph.Node> path = buildPath(g, parent, src, dst);
            if (path.isEmpty()) break;
            Result cand = evaluate(Algorithm.ASTAR, true, path, visited, g, cm, t0);
            boolean tooSlow = cand.costSeconds > best.costSeconds * 1.7 + 90;
            boolean same = false;
            for (Result r : out) {
                if (overlap(cand.path, r.path) > 0.85) {
                    same = true;
                    break;
                }
            }
            addKeys(used, cand.path);
            if (!tooSlow && !same) out.add(cand);
        }
        Collections.sort(out, (a, b) -> Double.compare(a.costSeconds, b.costSeconds));
        return out;
    }

    /** Travel time of an existing path under the given conditions (e.g. the same path without traffic). */
    public static double costOf(Graph g, List<Graph.Node> path, CostModel cm) {
        double cost = 0;
        for (int i = 0; i + 1 < path.size(); i++) {
            Graph.Edge e = bestEdge(g, path.get(i).id, path.get(i + 1).id, cm);
            if (e != null) cost += cm.cost(e);
        }
        return cost;
    }

    /** Splits a path into runs of equal traffic level, for drawing it green / amber / red. */
    public static List<Run> runs(Graph g, List<Graph.Node> path, CostModel cm) {
        List<Run> out = new ArrayList<>();
        Run cur = null;
        for (int i = 0; i + 1 < path.size(); i++) {
            Graph.Node a = path.get(i);
            Graph.Node b = path.get(i + 1);
            Graph.Edge e = bestEdge(g, a.id, b.id, cm);
            int level = e == null ? TrafficModel.FREE : cm.trafficLevel(e);
            if (cur == null || cur.level != level) {
                cur = new Run(level);
                cur.points.add(new double[]{a.lat, a.lon});
                out.add(cur);
            }
            cur.points.add(new double[]{b.lat, b.lon});
        }
        return out;
    }

    /** Share (0..1) of a's length that b also uses. */
    private static double overlap(List<Graph.Node> a, List<Graph.Node> b) {
        Set<Long> keys = new HashSet<>();
        addKeys(keys, b);
        double total = 0;
        double shared = 0;
        for (int i = 0; i + 1 < a.size(); i++) {
            Graph.Node p = a.get(i);
            Graph.Node q = a.get(i + 1);
            double len = Graph.haversine(p.lat, p.lon, q.lat, q.lon);
            total += len;
            if (keys.contains(key(p.id, q.id))) shared += len;
        }
        return total == 0 ? 1 : shared / total;
    }

    private static void addKeys(Set<Long> keys, List<Graph.Node> path) {
        for (int i = 0; i + 1 < path.size(); i++) keys.add(key(path.get(i).id, path.get(i + 1).id));
    }

    /** Same key for both directions of a road segment. */
    private static long key(long a, long b) {
        long lo = Math.min(a, b);
        long hi = Math.max(a, b);
        return lo * 0x9E3779B97F4A7C15L + hi;
    }

    /** Dijkstra (useHeuristic=false) and A* (useHeuristic=true). */
    private static boolean bestFirst(Graph g, long src, long dst, CostModel cm, EdgeCost edgeCost,
                                     boolean useHeuristic, Map<Long, Long> parent, List<Graph.Node> visited) {
        Graph.Node target = g.node(dst);
        Map<Long, Double> best = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        PriorityQueue<Entry> pq = new PriorityQueue<>();
        best.put(src, 0.0);
        pq.add(new Entry(src, 0, useHeuristic ? cm.heuristic(g.node(src), target) : 0));
        while (!pq.isEmpty()) {
            Entry cur = pq.poll();
            if (!closed.add(cur.id)) continue;
            visited.add(g.node(cur.id));
            if (cur.id == dst) return true;
            for (Graph.Edge e : g.edges(cur.id)) {
                double c = edgeCost.cost(e);
                if (c == Double.POSITIVE_INFINITY || closed.contains(e.to)) continue;
                double ng = cur.g + c;
                Double old = best.get(e.to);
                if (old == null || ng < old) {
                    best.put(e.to, ng);
                    parent.put(e.to, cur.id);
                    double h = useHeuristic ? cm.heuristic(g.node(e.to), target) : 0;
                    pq.add(new Entry(e.to, ng, ng + h));
                }
            }
        }
        return false;
    }

    private static List<Graph.Node> buildPath(Graph g, Map<Long, Long> parent, long src, long dst) {
        ArrayList<Graph.Node> rev = new ArrayList<>();
        long cur = dst;
        rev.add(g.node(cur));
        while (cur != src) {
            Long p = parent.get(cur);
            if (p == null) return Collections.emptyList();
            cur = p;
            rev.add(g.node(cur));
        }
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
