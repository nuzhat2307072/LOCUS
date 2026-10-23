package com.locus.app.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * Turn-by-turn guidance for one route (pure Java, unit-tested): builds the list of manoeuvres from
 * the road path and tells, for a GPS position, how far along the route the traveller is, how far
 * off it, and what comes next.
 */
public final class RouteGuide {

    public enum Maneuver {
        DEPART, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN, ARRIVE
    }

    public static final class Step {
        public final Maneuver maneuver;
        public final String street;        // the road to take after the manoeuvre ("" if unnamed)
        public final double atMeters;      // distance from the start of the route to the manoeuvre
        public final double lat;
        public final double lon;

        Step(Maneuver maneuver, String street, double atMeters, double lat, double lon) {
            this.maneuver = maneuver;
            this.street = street == null ? "" : street;
            this.atMeters = atMeters;
            this.lat = lat;
            this.lon = lon;
        }
    }

    public static final class Position {
        public final double along;   // metres travelled along the route
        public final double off;     // metres away from the route line

        Position(double along, double off) {
            this.along = along;
            this.off = off;
        }
    }

    private static final double WINDOW_M = 25;       // bearings are measured over at least this distance
    private static final double MIN_TURN_DEG = 30;

    private final List<Graph.Node> path;
    private final double[] cum;                      // distance from the start to each node
    private final List<Step> steps = new ArrayList<>();

    public RouteGuide(Graph g, List<Graph.Node> path) {
        this.path = path;
        int n = path.size();
        cum = new double[n];
        for (int i = 1; i < n; i++) {
            cum[i] = cum[i - 1] + Graph.haversine(path.get(i - 1).lat, path.get(i - 1).lon,
                    path.get(i).lat, path.get(i).lon);
        }
        if (n >= 2) buildSteps(g);
    }

    public double totalMeters() {
        return cum.length == 0 ? 0 : cum[cum.length - 1];
    }

    public List<Step> steps() {
        return steps;
    }

    /** First manoeuvre that is still ahead (never DEPART), or the arrival step. */
    public Step nextStep(double along) {
        for (Step s : steps) {
            if (s.maneuver != Maneuver.DEPART && s.atMeters > along + 5) return s;
        }
        return steps.isEmpty() ? null : steps.get(steps.size() - 1);
    }

    /** Projects a GPS position onto the route. */
    public Position locate(double lat, double lon) {
        double bestOff = Double.MAX_VALUE;
        double bestAlong = 0;
        double cos = Math.cos(Math.toRadians(lat));
        for (int i = 0; i + 1 < path.size(); i++) {
            Graph.Node a = path.get(i);
            Graph.Node b = path.get(i + 1);
            double px = (lon - a.lon) * cos;
            double py = lat - a.lat;
            double bx = (b.lon - a.lon) * cos;
            double by = b.lat - a.lat;
            double len2 = bx * bx + by * by;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, (px * bx + py * by) / len2));
            double dx = px - t * bx;
            double dy = py - t * by;
            double off = Math.sqrt(dx * dx + dy * dy) * 111320.0;
            if (off < bestOff) {
                bestOff = off;
                bestAlong = cum[i] + t * (cum[i + 1] - cum[i]);
            }
        }
        return new Position(bestAlong, bestOff == Double.MAX_VALUE ? 0 : bestOff);
    }

    // ------------------------------------------------------------------------------------------

    private void buildSteps(Graph g) {
        int n = path.size();
        steps.add(new Step(Maneuver.DEPART, edgeName(g, 0), 0, path.get(0).lat, path.get(0).lon));
        int i = 1;
        while (i < n - 1) {
            double d = turnAt(i);
            if (Math.abs(d) < MIN_TURN_DEG) {
                i++;
                continue;
            }
            // a bend is made of several nodes: take the whole cluster as one manoeuvre
            int first = i;
            int last = i;
            while (last + 1 < n - 1 && cum[last + 1] - cum[last] < WINDOW_M * 1.5
                    && Math.abs(turnAt(last + 1)) >= MIN_TURN_DEG) {
                last++;
            }
            double bIn = bearing(backNode(first), first);
            double bOut = bearing(last, forwardNode(last));
            double total = normalise(bOut - bIn);
            int mid = (first + last) / 2;
            if (Math.abs(total) >= MIN_TURN_DEG) {
                steps.add(new Step(classify(total), edgeName(g, last), cum[mid], path.get(mid).lat, path.get(mid).lon));
            }
            i = last + 1;
        }
        Graph.Node end = path.get(n - 1);
        steps.add(new Step(Maneuver.ARRIVE, "", cum[n - 1], end.lat, end.lon));
    }

    private double turnAt(int i) {
        return normalise(bearing(i, forwardNode(i)) - bearing(backNode(i), i));
    }

    /** Index of a node about WINDOW_M metres before i (or the start). */
    private int backNode(int i) {
        int j = i - 1;
        while (j > 0 && cum[i] - cum[j] < WINDOW_M) j--;
        return Math.max(0, j);
    }

    private int forwardNode(int i) {
        int k = i + 1;
        while (k < path.size() - 1 && cum[k] - cum[i] < WINDOW_M) k++;
        return Math.min(path.size() - 1, k);
    }

    private double bearing(int from, int to) {
        return bearing(path.get(from).lat, path.get(from).lon, path.get(to).lat, path.get(to).lon);
    }

    /** Compass bearing in degrees (0 = north, 90 = east). */
    public static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    /** Turn angle in (-180, 180]; positive = right. */
    static double normalise(double deg) {
        double d = deg % 360.0;
        if (d > 180) d -= 360;
        if (d <= -180) d += 360;
        return d;
    }

    static Maneuver classify(double d) {
        double a = Math.abs(d);
        if (a > 165) return Maneuver.UTURN;
        if (d > 0) return a < 60 ? Maneuver.SLIGHT_RIGHT : a < 135 ? Maneuver.RIGHT : Maneuver.SHARP_RIGHT;
        return a < 60 ? Maneuver.SLIGHT_LEFT : a < 135 ? Maneuver.LEFT : Maneuver.SHARP_LEFT;
    }

    private String edgeName(Graph g, int fromIndex) {
        if (fromIndex + 1 >= path.size()) return "";
        long a = path.get(fromIndex).id;
        long b = path.get(fromIndex + 1).id;
        for (Graph.Edge e : g.edges(a)) {
            if (e.to == b && !e.name.isEmpty()) return e.name;
        }
        return "";
    }
}
