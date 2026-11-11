package com.locus.app.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

/** A trip the person planned in the calendar: where from (current location or a set place), where to, and when. */
@Entity(tableName = "planned_trips")
public class Trip implements Serializable {
    @PrimaryKey
    @NonNull
    public String id = "";
    @NonNull
    public String title = "";
    /** true: the trip starts from wherever the phone is when the trip begins; false: from the set place below. */
    public boolean srcCurrent = true;
    @NonNull
    public String srcName = "";
    public double srcLat;
    public double srcLon;
    @NonNull
    public String destName = "";
    public double destLat;
    public double destLon;
    /** Start of the trip, UTC milliseconds. */
    public long startAt;
    @NonNull
    public String note = "";
    /** The 6-hour reminder was already shown. */
    public boolean reminded;
    public long createdAt;
}
