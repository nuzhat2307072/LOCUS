package com.locus.app.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

/** A map area saved for use without internet. */
@Entity(tableName = "offline_areas")
public class OfflineArea implements Serializable {
    @PrimaryKey
    @NonNull
    public String id = "";
    @NonNull
    public String name = "";
    public double north;
    public double south;
    public double east;
    public double west;
    public int zoomMin;
    public int zoomMax;
    public int tiles;
    public long bytes;
    public long updatedAt;
}
