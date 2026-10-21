package com.locus.app.model;

/**
 * Road hazards. "Blocking" hazards close the roads inside their radius (the router cannot use
 * them); the others only slow roads down by a penalty multiplier.
 */
public enum HazardType {
    ROAD_CLOSED("Road closed", true, 1.0, 60),
    FLOOD("Flooded road", true, 1.0, 120),
    LANDSLIDE("Landslide / fallen tree", true, 1.0, 50),
    ACCIDENT("Accident", false, 4.0, 80),
    CONSTRUCTION("Construction", false, 2.5, 100),
    TRAFFIC_JAM("Heavy traffic jam", false, 3.0, 150);

    public final String label;
    public final boolean blocking;
    public final double penalty;
    public final int defaultRadius; // metres

    HazardType(String label, boolean blocking, double penalty, int defaultRadius) {
        this.label = label;
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
