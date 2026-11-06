package com.locus.app.graph;

/**
 * Turns a road segment into a travel time in seconds for a transport mode and the current
 * conditions (rain, traffic jam, hazard penalties, closed roads).
 */
public final class CostModel {

    public enum Mode {
        WALK("Walk", 1.4),
        CYCLE("Cycle", 4.5),
        CAR("Car", 22.0);

        public final String label;
        /** Fastest speed (m/s) this mode can ever use - needed for an admissible A* heuristic. */
        public final double maxSpeed;

        Mode(String label, double maxSpeed) {
            this.label = label;
            this.maxSpeed = maxSpeed;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public final Mode mode;
    /** 0 = dry, 1 = rain, 2 = heavy rain. Decided by the program from the weather service. */
    public final int rainLevel;
    /** Estimated road congestion, or null for "ignore traffic". Decided by the program. */
    public final TrafficModel traffic;

    public CostModel(Mode mode, int rainLevel, TrafficModel traffic) {
        this.mode = mode;
        this.rainLevel = Math.max(0, Math.min(2, rainLevel));
        this.traffic = traffic;
    }

    /** Convenience for tests: rain on/off and the worst-case rush hour traffic on/off. */
    public CostModel(Mode mode, boolean rain, boolean rushHourTraffic) {
        this(mode, rain ? 1 : 0, rushHourTraffic ? TrafficModel.rushHour() : null);
    }

    public boolean rain() {
        return rainLevel > 0;
    }

    /** Base speed in m/s, or 0 if this mode may not use the segment. */
    public double speed(Graph.Edge e) {
        String hw = e.highway == null ? "" : e.highway;
        switch (mode) {
            case WALK:
                if (hw.equals("motorway") || hw.equals("motorway_link")) return 0;
                if (hw.equals("steps")) return 0.7;
                return 1.4;
            case CYCLE:
                if (hw.equals("motorway") || hw.equals("motorway_link") || hw.equals("steps")) return 0;
                if (hw.equals("footway") || hw.equals("pedestrian") || hw.equals("path")) return 3.0;
                if (hw.equals("track")) return 3.5;
                return 4.5;
            case CAR:
            default:
                if (e.carForbidden) return 0;
                switch (hw) {
                    case "motorway": return 22;
                    case "motorway_link": return 12;
                    case "trunk": return 18;
                    case "trunk_link": return 11;
                    case "primary": return 14;
                    case "primary_link": return 9;
                    case "secondary": return 12;
                    case "secondary_link": return 8;
                    case "tertiary": return 10;
                    case "tertiary_link": return 7;
                    case "unclassified": return 9;
                    case "residential": return 7;
                    case "living_street": return 3.5;
                    case "service": return 4;
                    case "track": return 3;
                    default: return 0; // footway, path, steps, cycleway, pedestrian ...
                }
        }
    }

    /** Normal traffic flow 0..1 on this segment (time of day + rain). Hazard penalties are separate. */
    private double flow(Graph.Edge e) {
        double c = traffic == null ? 0 : traffic.load(e);
        if (rainLevel > 0 && TrafficModel.baseLoad(e.highway) >= 0.3) c += 0.08 * rainLevel; // wet roads slow everyone
        return Math.min(1.0, c);
    }

    /** Congestion 0..1 used for colouring: normal flow, or a standstill where a hazard was reported. */
    public double congestion(Graph.Edge e) {
        if (e.blocked) return 1.0;
        double c = flow(e);
        if (e.penalty > 1.2) c = Math.max(c, 0.8); // accident / construction reported nearby
        return c;
    }

    /** TrafficModel.FREE / MODERATE / HEAVY for colouring the route. Walking is not affected by traffic. */
    public int trafficLevel(Graph.Edge e) {
        if (mode == Mode.WALK) return e.penalty > 1.2 ? TrafficModel.HEAVY : TrafficModel.FREE;
        return TrafficModel.levelOf(congestion(e));
    }

    /** Travel time in seconds, or +infinity if the edge cannot be used. */
    public double cost(Graph.Edge e) {
        if (e.blocked) return Double.POSITIVE_INFINITY;
        double s = speed(e);
        if (s <= 0) return Double.POSITIVE_INFINITY;
        double t = e.length / s;
        if (rainLevel > 0) {
            switch (mode) {
                case WALK: t *= 1.0 + 0.15 * rainLevel; break;
                case CYCLE: t *= 1.0 + 0.3 * rainLevel; break;
                default: t *= 1.0 + 0.08 * rainLevel; break;
            }
        }
        double c = flow(e);
        if (mode == Mode.CAR) t *= 1.0 + 2.4 * c;
        else if (mode == Mode.CYCLE) t *= 1.0 + 0.5 * c;
        return t * e.penalty;
    }

    /** A* heuristic: straight-line time at the fastest possible speed (never overestimates). */
    public double heuristic(Graph.Node a, Graph.Node b) {
        return Graph.haversine(a.lat, a.lon, b.lat, b.lon) / mode.maxSpeed;
    }
}
