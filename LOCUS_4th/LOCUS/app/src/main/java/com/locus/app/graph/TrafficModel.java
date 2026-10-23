package com.locus.app.graph;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * How busy the roads are right now. The PROGRAM decides this - the user never picks traffic.
 *
 * There is no free live-traffic feed for OpenStreetMap, so congestion is estimated from things that
 * really drive it: the time of day (morning and evening rush hours, lunch time, quiet nights), the
 * weekly holiday, the road class (main roads jam first, lanes rarely), and local hot spots - whole
 * neighbourhoods (about 250 m cells) that are jammed for a while. The hot spots are deterministic for
 * a 20-minute window, so the picture is stable between recalculations and does not flicker.
 * (Pure Java, unit-tested.)
 */
public final class TrafficModel {
    public static final int FREE = 0;
    public static final int MODERATE = 1;
    public static final int HEAVY = 2;

    private static final double CELL_DEG = 0.0025;      // ~275 m
    private static final long BUCKET_MS = 20L * 60 * 1000;

    private final double timeFactor; // 0..1, how busy this moment is in general
    private final long bucket;

    public TrafficModel(double timeFactor, long bucket) {
        this.timeFactor = Math.max(0, Math.min(1, timeFactor));
        this.bucket = bucket;
    }

    /** The worst case (evening rush hour) - handy for tests. */
    public static TrafficModel rushHour() {
        return new TrafficModel(1.0, 7);
    }

    public static TrafficModel now() {
        return forTime(System.currentTimeMillis(), TimeZone.getDefault());
    }

    public static TrafficModel forTime(long millis, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(millis);
        double hour = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0;
        return new TrafficModel(timeFactorAt(hour, isWeekend(c, tz)), (millis + tz.getRawOffset()) / BUCKET_MS);
    }

    static boolean isWeekend(Calendar c, TimeZone tz) {
        int d = c.get(Calendar.DAY_OF_WEEK);
        if ("Asia/Dhaka".equals(tz.getID())) return d == Calendar.FRIDAY || d == Calendar.SATURDAY;
        return d == Calendar.SATURDAY || d == Calendar.SUNDAY;
    }

    /** 0..1: general busyness at a local hour (0-24). */
    public static double timeFactorAt(double hour, boolean weekend) {
        double rush = Math.max(bump(hour, 9.0, 1.4), bump(hour, 18.0, 1.8));
        double lunch = 0.45 * bump(hour, 13.5, 1.6);
        double t = 0.08 + 0.92 * Math.max(rush, lunch);
        if (hour >= 6 && hour < 23) t = Math.max(t, 0.25);   // daytime is never empty
        else t *= 0.4;                                        // night
        if (weekend) t *= 0.65;
        return Math.min(1.0, t);
    }

    private static double bump(double x, double centre, double width) {
        double z = (x - centre) / width;
        return Math.exp(-z * z);
    }

    public double timeFactor() {
        return timeFactor;
    }

    /** Overall city level for the "Traffic: ..." indicator. */
    public int cityLevel() {
        if (timeFactor >= 0.7) return HEAVY;
        if (timeFactor >= 0.4) return MODERATE;
        return FREE;
    }

    /** How much of a road class is normally clogged at the busiest moment (0..1). */
    static double baseLoad(String highway) {
        if (highway == null) return 0.2;
        switch (highway) {
            case "motorway":
            case "trunk":
            case "primary":
                return 0.95;
            case "motorway_link":
            case "trunk_link":
            case "primary_link":
                return 0.75;
            case "secondary":
                return 0.8;
            case "secondary_link":
                return 0.6;
            case "tertiary":
                return 0.55;
            case "tertiary_link":
                return 0.4;
            case "unclassified":
                return 0.35;
            case "residential":
                return 0.22;
            case "living_street":
                return 0.1;
            case "service":
                return 0.08;
            default:
                return 0.0; // footways, paths, steps, cycleways
        }
    }

    /** Congestion of one road segment, 0 (free) .. 1 (standstill). */
    public double load(Graph.Edge e) {
        double base = baseLoad(e.highway);
        if (base <= 0) return 0;
        double c = base * timeFactor;
        if (base >= 0.5 && timeFactor > 0.15) {
            long cx = (long) Math.floor(e.midLat / CELL_DEG);
            long cy = (long) Math.floor(e.midLon / CELL_DEG);
            double u = hash01(cx * 73856093L ^ cy * 19349663L ^ bucket * 83492791L);
            if (u < 0.16) c += 0.45 * (0.5 + timeFactor); // a jammed neighbourhood
        }
        return Math.max(0, Math.min(1, c));
    }

    public static int levelOf(double load) {
        if (load < 0.30) return FREE;
        if (load < 0.55) return MODERATE;
        return HEAVY;
    }

    private static double hash01(long x) {
        x += 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        x = x ^ (x >>> 31);
        return (x >>> 11) * (1.0 / (1L << 53));
    }
}
