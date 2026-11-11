package com.locus.app.graph;

import java.util.List;

/**
 * Compares the possible routes: how many reported hazards each one passes, which one is the recommended choice
 * ("shortest" or "safest") and a one-line reason. Pure Java, tested on the JVM.
 *
 * Safety score = travel time + a time penalty for every hazard the route passes (2 min each, 3 min if people
 * nearby have verified it). So a slightly longer route that avoids a verified hazard wins in Safest mode.
 */
public final class RouteInsight {
    public static final double HAZARD_SECONDS = 120;
    public static final double VERIFIED_SECONDS = 180;
    /** A route "passes" a hazard when it comes closer than the hazard's radius plus this many metres. */
    public static final double NEAR_EXTRA_M = 30;

    private RouteInsight() {
    }

    /** A reported hazard, reduced to what the comparison needs. */
    public static final class Spot {
        public final double lat;
        public final double lon;
        public final double radiusM;
        public final boolean verified;

        public Spot(double lat, double lon, double radiusM, boolean verified) {
            this.lat = lat;
            this.lon = lon;
            this.radiusM = radiusM;
            this.verified = verified;
        }
    }

    /** Hazards a route passes. */
    public static final class Exposure {
        public final int hazards;
        public final int verified;

        public Exposure(int hazards, int verified) {
            this.hazards = hazards;
            this.verified = verified;
        }
    }

    /** Shortest distance in metres from a point to a path of {lat, lon} points (segments, not just corners). */
    public static double distanceToPath(List<double[]> path, double lat, double lon) {
        if (path == null || path.isEmpty()) return Double.POSITIVE_INFINITY;
        double kx = 111320.0 * Math.cos(Math.toRadians(lat));
        double ky = 110540.0;
        double best = Double.POSITIVE_INFINITY;
        double[] prev = null;
        for (double[] p : path) {
            double x = (p[1] - lon) * kx;
            double y = (p[0] - lat) * ky;
            double d;
            if (prev == null) {
                d = Math.hypot(x, y);
            } else {
                double px = prev[0];
                double py = prev[1];
                double dx = x - px;
                double dy = y - py;
                double len2 = dx * dx + dy * dy;
                double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(px * dx + py * dy) / len2));
                d = Math.hypot(px + t * dx, py + t * dy);
            }
            best = Math.min(best, d);
            prev = new double[]{x, y};
        }
        return best;
    }

    public static Exposure exposure(List<double[]> path, List<Spot> spots) {
        int n = 0;
        int v = 0;
        for (Spot s : spots) {
            if (distanceToPath(path, s.lat, s.lon) <= s.radiusM + NEAR_EXTRA_M) {
                n++;
                if (s.verified) v++;
            }
        }
        return new Exposure(n, v);
    }

    /** Extra seconds the hazards on a route are worth. */
    public static double riskSeconds(Exposure e) {
        return (e.hazards - e.verified) * HAZARD_SECONDS + e.verified * VERIFIED_SECONDS;
    }

    public static double safetyScore(double seconds, Exposure e) {
        return seconds + riskSeconds(e);
    }

    /** Index of the recommended route: the lowest safety score in safest mode, else the shortest distance. */
    public static int best(double[] meters, double[] score, boolean safest) {
        double[] key = safest ? score : meters;
        int best = 0;
        for (int i = 1; i < key.length; i++) if (key[i] < key[best]) best = i;
        return best;
    }

    public static final int CLEAR = 1;    // the recommended route passes no hazard
    public static final int EXPOSED = 2;  // it passes hazards and no cleaner route exists
    public static final int SAFER = 3;    // it is longer than the shortest one but passes fewer hazards

    /** The reason for the recommendation, as numbers the screen turns into a sentence. */
    public static final class Why {
        public final int type;
        public final int hazards;       // hazards on the recommended route
        public final int avoided;       // hazards the shortest route passes that this one does not
        public final double extraMeters;
        public final double extraSeconds;

        Why(int type, int hazards, int avoided, double extraMeters, double extraSeconds) {
            this.type = type;
            this.hazards = hazards;
            this.avoided = avoided;
            this.extraMeters = extraMeters;
            this.extraSeconds = extraSeconds;
        }
    }

    public static Why why(int best, double[] meters, double[] seconds, int[] hazards) {
        int shortest = 0;
        for (int i = 1; i < meters.length; i++) if (meters[i] < meters[shortest]) shortest = i;
        if (best != shortest && hazards[best] < hazards[shortest]) {
            return new Why(SAFER, hazards[best], hazards[shortest] - hazards[best],
                    meters[best] - meters[shortest], seconds[best] - seconds[shortest]);
        }
        return new Why(hazards[best] == 0 ? CLEAR : EXPOSED, hazards[best], 0, 0, 0);
    }
}
