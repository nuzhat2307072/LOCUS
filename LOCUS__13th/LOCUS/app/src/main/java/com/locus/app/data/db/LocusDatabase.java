package com.locus.app.data.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.locus.app.model.Comment;
import com.locus.app.model.CommunityRequest;
import com.locus.app.model.Hazard;
import com.locus.app.model.OfflineArea;
import com.locus.app.model.Place;
import com.locus.app.model.Trip;

/** Room database: offline cache of places, community requests and hazards. */
@Database(entities = {Place.class, CommunityRequest.class, Hazard.class, Comment.class, OfflineArea.class, Trip.class}, version = 6, exportSchema = false)
public abstract class LocusDatabase extends RoomDatabase {

    public abstract PlaceDao placeDao();

    public abstract RequestDao requestDao();

    public abstract HazardDao hazardDao();

    public abstract CommentDao commentDao();

    public abstract OfflineAreaDao offlineAreaDao();

    public abstract TripDao tripDao();

    private static volatile LocusDatabase instance;

    public static LocusDatabase get(Context context) {
        if (instance == null) {
            synchronized (LocusDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context.getApplicationContext(),
                                    LocusDatabase.class, "locus_room.db")
                            .addMigrations(new androidx.room.migration.Migration(4, 5) {
                                @Override
                                public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase db) {
                                    // replies: two new columns on comments; nothing else is touched
                                    db.execSQL("ALTER TABLE comments ADD COLUMN parentId TEXT DEFAULT ''");
                                    db.execSQL("ALTER TABLE comments ADD COLUMN replyToName TEXT DEFAULT ''");
                                }
                            })
                            .addMigrations(new androidx.room.migration.Migration(5, 6) {
                                @Override
                                public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase db) {
                                    // hazard votes: four new columns on hazards
                                    db.execSQL("ALTER TABLE hazards ADD COLUMN lastConfirmedAt INTEGER NOT NULL DEFAULT 0");
                                    db.execSQL("ALTER TABLE hazards ADD COLUMN stillVotes INTEGER NOT NULL DEFAULT 0");
                                    db.execSQL("ALTER TABLE hazards ADD COLUMN clearedVotes INTEGER NOT NULL DEFAULT 0");
                                    db.execSQL("ALTER TABLE hazards ADD COLUMN myVote TEXT");
                                    // offline map areas
                                    db.execSQL("CREATE TABLE IF NOT EXISTS offline_areas (id TEXT NOT NULL, name TEXT NOT NULL, "
                                            + "north REAL NOT NULL, south REAL NOT NULL, east REAL NOT NULL, west REAL NOT NULL, "
                                            + "zoomMin INTEGER NOT NULL, zoomMax INTEGER NOT NULL, tiles INTEGER NOT NULL, "
                                            + "bytes INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))");
                                    // trip history
                                    db.execSQL("CREATE TABLE IF NOT EXISTS trips (id TEXT NOT NULL, startedAt INTEGER NOT NULL, "
                                            + "endedAt INTEGER NOT NULL, startName TEXT NOT NULL, startLat REAL NOT NULL, "
                                            + "startLon REAL NOT NULL, destName TEXT NOT NULL, destLat REAL NOT NULL, "
                                            + "destLon REAL NOT NULL, meters REAL NOT NULL, seconds INTEGER NOT NULL, "
                                            + "mode TEXT NOT NULL, completed INTEGER NOT NULL, path TEXT NOT NULL, PRIMARY KEY(id))");
                                }
                            })
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return instance;
    }
}
