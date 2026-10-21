package com.locus.app.model;

/** Kinds of Community Assistance requests. */
public enum RequestType {
    BLOOD("Blood donation"),
    VOLUNTEER("Volunteer"),
    DISASTER("Disaster aid"),
    DONATION("Donation"),
    TRAVEL("Travel / delivery"),
    TREKKING("Trekking");

    public final String label;

    RequestType(String label) {
        this.label = label;
    }

    public static RequestType fromName(String name) {
        try {
            return valueOf(name);
        } catch (Exception e) {
            return VOLUNTEER;
        }
    }

    @Override
    public String toString() {
        return label;
    }
}
