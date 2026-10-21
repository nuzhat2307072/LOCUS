package com.locus.app.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

/** A nearby place from OpenStreetMap. Also the Room entity used for the offline cache. */
@Entity(tableName = "places")
public class Place implements Serializable {
    @PrimaryKey
    @NonNull
    public String id = "";
    public String name;
    public String category;          // PlaceCategory name
    public double lat;
    public double lon;
    public String phone;
    public String address;
    public String openingHours;
    public double distanceMeters;    // from the user at fetch time
    public long fetchedAt;
}
