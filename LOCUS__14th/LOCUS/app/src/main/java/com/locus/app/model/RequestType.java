package com.locus.app.model;

import com.locus.app.R;

/** Kinds of Community Assistance requests. */
public enum RequestType {
    BLOOD("Blood donation", R.string.req_blood),
    VOLUNTEER("Volunteer", R.string.req_volunteer),
    DISASTER("Disaster aid", R.string.req_disaster),
    DONATION("Donation", R.string.req_donation),
    TRAVEL("Travel / delivery", R.string.req_travel),
    TREKKING("Trekking", R.string.req_trekking);

    /** English name, kept for stored data; screens show {@link #labelRes} in the app language. */
    public final String label;
    public final int labelRes;

    RequestType(String label, int labelRes) {
        this.label = label;
        this.labelRes = labelRes;
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
