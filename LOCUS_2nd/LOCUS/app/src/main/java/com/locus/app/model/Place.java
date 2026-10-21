package com.locus.app.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

/**
 * A nearby place from OpenStreetMap. Also the Room entity used for the offline cache of searches.
 * The id is "<search text>#<osm type><osm id>", so the same shop can be cached under several searches.
 */
@Entity(tableName = "places")
public class Place implements Serializable {
    @PrimaryKey
    @NonNull
    public String id = "";
    public String searchKey;         // normalised text the user searched for
    public String name;
    public String category;          // readable type, e.g. "Pharmacy", "Pizza restaurant"
    public double lat;
    public double lon;
    public String phone;
    public String address;
    public String openingHours;
    public double distanceMeters;    // from the user at fetch time
    public long fetchedAt;
}
