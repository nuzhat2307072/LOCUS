package com.locus.app.model;

import com.locus.app.R;

/**
 * Road hazards. "Blocking" hazards close the roads inside their radius (the router cannot use
 * them); the others only slow roads down by a penalty multiplier.
 */
public enum HazardType {
    ROAD_CLOSED("Road closed", R.string.hazard_road_closed, true, 1.0, 60),
    FLOOD("Flooded road", R.string.hazard_flood, true, 1.0, 120),
    LANDSLIDE("Landslide / fallen tree", R.string.hazard_landslide, true, 1.0, 50),
    ACCIDENT("Accident", R.string.hazard_accident, false, 4.0, 80),
    CONSTRUCTION("Construction", R.string.hazard_construction, false, 2.5, 100),
    TRAFFIC_JAM("Heavy traffic jam", R.string.hazard_traffic_jam, false, 3.0, 150);

    /** English name, kept for stored data; screens show {@link #labelRes} in the app language. */
    public final String label;
    public final int labelRes;
    public final boolean blocking;
    public final double penalty;
    public final int defaultRadius; // metres

    HazardType(String label, int labelRes, boolean blocking, double penalty, int defaultRadius) {
        this.label = label;
        this.labelRes = labelRes;
        this.blocking = blocking;
        this.penalty = penalty;
        this.defaultRadius = defaultRadius;
    }

    public static HazardType fromName(String name) {
        try {
            return valueOf(name);
        } catch (Exception e) {
            return ACCIDENT;
        }
    }
}
