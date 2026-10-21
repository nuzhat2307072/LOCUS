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
    public final boolean rain;
    public final boolean traffic;

    public CostModel(Mode mode, boolean rain, boolean traffic) {
        this.mode = mode;
        this.rain = rain;
        this.traffic = traffic;
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

    /** Travel time in seconds, or +infinity if the edge cannot be used. */
    public double cost(Graph.Edge e) {
        if (e.blocked) return Double.POSITIVE_INFINITY;
        double s = speed(e);
        if (s <= 0) return Double.POSITIVE_INFINITY;
        double t = e.length / s;
        if (rain) {
            switch (mode) {
                case WALK: t *= 1.15; break;
                case CYCLE: t *= 1.3; break;
                default: t *= 1.1; break;
            }
        }
        if (traffic && mode == Mode.CAR) {
            String hw = e.highway == null ? "" : e.highway;
            if (hw.startsWith("trunk") || hw.startsWith("primary") || hw.startsWith("secondary")) t *= 1.8;
            else if (hw.startsWith("tertiary")) t *= 1.5;
            else t *= 1.2;
        }
        return t * e.penalty;
    }

    /** A* heuristic: straight-line time at the fastest possible speed (never overestimates). */
    public double heuristic(Graph.Node a, Graph.Node b) {
        return Graph.haversine(a.lat, a.lon, b.lat, b.lon) / mode.maxSpeed;
    }
}
