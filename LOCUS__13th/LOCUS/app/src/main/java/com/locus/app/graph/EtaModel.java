package com.locus.app.graph;

import java.util.List;

/**
 * Travel-time arithmetic that makes the arrival time realistic (pure Java, unit-tested).
 *
 *  - {@link #calibrationScale}: the online router (OSRM) knows real speed limits, turns and junctions; the
 *    on-phone road graph only knows road classes. The ratio between the two on the SAME trip corrects the graph.
 *  - {@link #junctionDelay}: time lost at junctions (offline fallback, when no online reference exists).
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
}
