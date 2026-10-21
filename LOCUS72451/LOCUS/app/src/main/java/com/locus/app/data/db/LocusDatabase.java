package com.locus.app.data.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.locus.app.model.CommunityRequest;
import com.locus.app.model.Hazard;
import com.locus.app.model.Place;

/** Room database: offline cache of places, community requests and hazards. */
@Database(entities = {Place.class, CommunityRequest.class, Hazard.class}, version = 1, exportSchema = false)
public abstract class LocusDatabase extends RoomDatabase {

    public abstract PlaceDao placeDao();

    public abstract RequestDao requestDao();

    public abstract HazardDao hazardDao();

    private static volatile LocusDatabase instance;

    public static LocusDatabase get(Context context) {
        if (instance == null) {
            synchronized (LocusDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context.getApplicationContext(),
                                    LocusDatabase.class, "locus_room.db")
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return instance;
    }
}
