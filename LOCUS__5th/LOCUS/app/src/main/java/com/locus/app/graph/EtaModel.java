package com.locus.app.graph;

import java.util.List;

/**
 * Travel-time arithmetic that makes the arrival time realistic (pure Java, unit-tested).
 *
 *  - {@link #calibrationScale}: the online router (OSRM) knows real speed limits, turns and junctions; the
 *    on-phone road graph only knows road classes. The ratio between the two on the SAME trip corrects the graph.
 *  - {@link #junctionDelay}: time lost at junctions (offline fallback, when no online reference exists).
 *  - {@link #onlineFactor}: traffic + rain multiplier for routes that come ready-made from the online router.
 */
public final class EtaModel {
    private EtaModel() {
    }

    /**
     * @param referenceFreeFlowSeconds trip time from the online router, no traffic (0 if unknown)
     * @param graphDrySeconds          trip time of the graph's best route with no traffic and no rain
     * @return multiplier for every graph route time; 1 when there is no usable reference
     */
    public static double calibrationScale(double referenceFreeFlowSeconds, double graphDrySeconds) {
        if (!(referenceFreeFlowSeconds > 0) || !(graphDrySeconds > 0)) return 1.0;
        double k = referenceFreeFlowSeconds / graphDrySeconds;
        return Math.max(0.6, Math.min(1.9, k));
    }

    /** Seconds lost at one junction (signal, give-way, crossing a road). */
    public static double junctionDelay(CostModel.Mode mode) {
        switch (mode) {
            case WALK:
                return 2.0;
            case CYCLE:
                return 2.5;
            default:
                return 4.0;
        }
    }

    /** Junctions on a path: interior nodes where at least three roads meet. */
    public static int countJunctions(Graph g, List<Graph.Node> path) {
        int n = 0;
        for (int i = 1; i + 1 < path.size(); i++) {
            if (g.edges(path.get(i).id).size() >= 3) n++;
        }
        return n;
    }

    /**
     * Multiplier for a ready-made online route: how much slower than free flow is this trip now?
     *
     * @param timeFactor      0..1 general busyness of the moment ({@link TrafficModel#timeFactor()})
     * @param rainLevel       0 dry, 1 rain, 2 heavy rain
     * @param liveSlowdown    measured slowdown (free-flow time / current time, 1 = free), or NaN when unknown
     */
    public static double onlineFactor(CostModel.Mode mode, double timeFactor, int rainLevel, double liveSlowdown) {
        double traffic;
        if (mode == CostModel.Mode.WALK) {
            traffic = 1.0;
        } else {
            double slowdown = !Double.isNaN(liveSlowdown) && liveSlowdown >= 1.0
                    ? Math.min(liveSlowdown, 4.0)
                    : 1.0 + 1.0 * Math.max(0, Math.min(1, timeFactor)); // rush hour about 2x, quiet night about 1x
            traffic = mode == CostModel.Mode.CYCLE ? 1.0 + 0.5 * (slowdown - 1.0) : slowdown;
        }
        double rain;
        switch (mode) {
            case WALK:
                rain = 1.0 + 0.15 * rainLevel;
                break;
            case CYCLE:
                rain = 1.0 + 0.3 * rainLevel;
                break;
            default:
                rain = 1.0 + 0.08 * rainLevel;
                break;
        }
        return traffic * rain;
    }
}
