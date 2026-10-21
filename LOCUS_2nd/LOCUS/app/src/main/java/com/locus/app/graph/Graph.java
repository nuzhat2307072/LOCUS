package com.locus.app.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Weighted directed graph of the road network (pure Java - no Android classes, so it can be
 * unit-tested on the JVM). Nodes are OpenStreetMap node ids; edges are road segments.
 */
public class Graph {

    public static final class Node {
        public final long id;
        public final double lat;
        public final double lon;

        public Node(long id, double lat, double lon) {
            this.id = id;
            this.lat = lat;
            this.lon = lon;
        }
    }

    public static final class Edge {
        public final long from;
        public final long to;
        public final double length;          // metres
        public final String highway;         // OSM highway tag value
        public final boolean carForbidden;   // true for the wrong way of a one-way road
        public final double midLat;
        public final double midLon;
        public boolean blocked;              // road closed / flooded (set by hazards)
        public double penalty = 1.0;         // >1 slows the edge down (accident, construction...)

        Edge(long from, long to, double length, String highway, boolean carForbidden,
             double midLat, double midLon) {
            this.from = from;
            this.to = to;
            this.length = length;
            this.highway = highway;
            this.carForbidden = carForbidden;
            this.midLat = midLat;
            this.midLon = midLon;
        }
    }

    private final HashMap<Long, Node> nodes = new HashMap<>();
    private final HashMap<Long, ArrayList<Edge>> adjacency = new HashMap<>();

    public void addNode(long id, double lat, double lon) {
        if (!nodes.containsKey(id)) {
            nodes.put(id, new Node(id, lat, lon));
        }
    }

    public Node node(long id) {
        return nodes.get(id);
    }

    public Collection<Node> nodes() {
        return nodes.values();
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        int c = 0;
        for (ArrayList<Edge> l : adjacency.values()) c += l.size();
        return c;
    }

    public List<Edge> edges(long nodeId) {
        ArrayList<Edge> l = adjacency.get(nodeId);
        return l == null ? Collections.<Edge>emptyList() : l;
    }

    /**
     * Adds a road segment between two existing nodes.
     *
     * @param oneway 0 = two-way, 1 = only from -> to, -1 = only to -> from (applies to cars)
     */
    public void addSegment(long from, long to, String highway, int oneway) {
        Node a = nodes.get(from);
        Node b = nodes.get(to);
        if (a == null || b == null || from == to) return;
        double len = haversine(a.lat, a.lon, b.lat, b.lon);
        double mLat = (a.lat + b.lat) / 2.0;
        double mLon = (a.lon + b.lon) / 2.0;
        add(new Edge(from, to, len, highway, oneway == -1, mLat, mLon));
        add(new Edge(to, from, len, highway, oneway == 1, mLat, mLon));
    }

    /** Adds all consecutive segments of an OSM way. */
    public void addWay(List<Long> ids, List<Double> lats, List<Double> lons, String highway, int oneway) {
        for (int i = 0; i < ids.size(); i++) {
            addNode(ids.get(i), lats.get(i), lons.get(i));
        }
        for (int i = 0; i + 1 < ids.size(); i++) {
            addSegment(ids.get(i), ids.get(i + 1), highway, oneway);
        }
    }

    private void add(Edge e) {
        ArrayList<Edge> l = adjacency.get(e.from);
        if (l == null) {
            l = new ArrayList<>(3);
            adjacency.put(e.from, l);
        }
        l.add(e);
    }

    /** Nearest node, optionally limited to {@code allowed} (null = every node). */
    public Node nearest(double lat, double lon, Set<Long> allowed) {
        Node best = null;
        double bestD = Double.MAX_VALUE;
        double cosLat = Math.cos(Math.toRadians(lat));
        for (Node n : nodes.values()) {
            double dy = n.lat - lat;
            double dx = (n.lon - lon) * cosLat;
            double d = dx * dx + dy * dy;
            if (d >= bestD) continue;
            if (allowed != null && !allowed.contains(n.id)) continue;
            best = n;
            bestD = d;
        }
        return best;
    }

    /**
     * Nodes of the biggest connected road network that this travel mode can actually use right now
     * (closed roads and forbidden road types do not connect anything).
     *
     * Snapping the start / destination to this set avoids the classic "no route" failure where the
     * nearest node is a private lane, a footpath (for cars) or a stretch cut off by a closed road.
     */
    public Set<Long> largestComponent(CostModel cm) {
        int n = nodes.size();
        HashMap<Long, Integer> index = new HashMap<>(n * 2);
        long[] ids = new long[n];
        int k = 0;
        for (Long id : nodes.keySet()) {
            index.put(id, k);
            ids[k++] = id;
        }
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (ArrayList<Edge> list : adjacency.values()) {
            for (Edge e : list) {
                if (cm.cost(e) == Double.POSITIVE_INFINITY) continue;
                Integer a = index.get(e.from);
                Integer b = index.get(e.to);
                if (a == null || b == null) continue;
                int ra = find(parent, a);
                int rb = find(parent, b);
                if (ra != rb) parent[ra] = rb;
            }
        }
        int[] size = new int[n];
        for (int i = 0; i < n; i++) size[find(parent, i)]++;
        int bestRoot = 0;
        for (int i = 0; i < n; i++) {
            if (size[i] > size[bestRoot]) bestRoot = i;
        }
        HashSet<Long> out = new HashSet<>();
        for (int i = 0; i < n; i++) {
            if (find(parent, i) == bestRoot) out.add(ids[i]);
        }
        return out;
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    /** Clears every hazard effect (call before re-applying the current hazard list). */
    public void resetConditions() {
        for (ArrayList<Edge> l : adjacency.values()) {
            for (Edge e : l) {
                e.blocked = false;
                e.penalty = 1.0;
            }
        }
    }

    /**
     * Applies a hazard: every edge whose midpoint or end points lie within radius metres is
     * either blocked (road closed) or slowed down (penalty multiplier).
     *
     * @return number of edges affected
     */
    public int applyHazard(double lat, double lon, double radius, boolean block, double penalty) {
        int affected = 0;
        for (ArrayList<Edge> l : adjacency.values()) {
            for (Edge e : l) {
                Node a = nodes.get(e.from);
                Node b = nodes.get(e.to);
                double d = Math.min(haversine(lat, lon, e.midLat, e.midLon),
                        Math.min(haversine(lat, lon, a.lat, a.lon), haversine(lat, lon, b.lat, b.lon)));
                if (d <= radius) {
                    if (block) e.blocked = true;
                    else e.penalty = Math.max(e.penalty, penalty);
                    affected++;
                }
            }
        }
        return affected;
    }

    /** Great-circle distance in metres. */
    public static double haversine(double lat1, double lon1, double lat2, double lon2) {
        final double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
