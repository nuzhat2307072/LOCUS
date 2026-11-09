package com.locus.app.model;

import com.locus.app.R;

/**
 * Road hazards. "Blocking" hazards close the roads inside their radius (the router cannot use
 * them); the others only slow roads down by a penalty multiplier.
 */
public enum HazardType {
    ROAD_CLOSED("Road closed", R.string.hazard_road_closed, true, 1.0, 60, 12),
    FLOOD("Flooded road", R.string.hazard_flood, true, 1.0, 120, 12),
    LANDSLIDE("Landslide / fallen tree", R.string.hazard_landslide, true, 1.0, 50, 24),
    ACCIDENT("Accident", R.string.hazard_accident, false, 4.0, 80, 2),
    CONSTRUCTION("Construction", R.string.hazard_construction, false, 2.5, 100, 48),
    TRAFFIC_JAM("Heavy traffic jam", R.string.hazard_traffic_jam, false, 3.0, 150, 1);

    /** English name, kept for stored data; screens show {@link #labelRes} in the app language. */
    public final String label;
    public final int labelRes;
    public final boolean blocking;
    public final double penalty;
    public final int defaultRadius; // metres
    /** Hours the hazard stays on the map without a "Still there" vote. */
    public final int ttlHours;

    HazardType(String label, int labelRes, boolean blocking, double penalty, int defaultRadius, int ttlHours) {
        this.label = label;
        this.labelRes = labelRes;
        this.blocking = blocking;
        this.penalty = penalty;
        this.defaultRadius = defaultRadius;
        this.ttlHours = ttlHours;
    }

    public static HazardType fromName(String name) {
        try {
            return valueOf(name);
        } catch (Exception e) {
            return ACCIDENT;
        }
    }
}
