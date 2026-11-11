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
@Database(entities = {Place.class, CommunityRequest.class, Hazard.class, Comment.class, OfflineArea.class, Trip.class}, version = 10, exportSchema = false)
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
                                }
                            })
                            .addMigrations(new androidx.room.migration.Migration(6, 7) {
                                @Override
                                public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase db) {
                                    // an earlier test build of version 6 had a "trips" table (trip history, now removed);
                                    // the table must go, otherwise Room refuses to open a database whose layout differs
                                    db.execSQL("DROP TABLE IF EXISTS trips");
                                }
                            })
                            .addMigrations(new androidx.room.migration.Migration(7, 8) {
                                @Override
                                public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase db) {
                                    // hazards verified by people nearby: two counters
                                    db.execSQL("ALTER TABLE hazards ADD COLUMN nearStill INTEGER NOT NULL DEFAULT 0");
                                    db.execSQL("ALTER TABLE hazards ADD COLUMN nearCleared INTEGER NOT NULL DEFAULT 0");
                                }
                            })
                            .addMigrations(new androidx.room.migration.Migration(8, 9) {
                                @Override
                                public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase db) {
                                    // photo on a hazard report
                                    db.execSQL("ALTER TABLE hazards ADD COLUMN hasPhoto INTEGER NOT NULL DEFAULT 0");
                                }
                            })
                            .addMigrations(new androidx.room.migration.Migration(9, 10) {
                                @Override
                                public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase db) {
                                    // the trip calendar
                                    db.execSQL("CREATE TABLE IF NOT EXISTS planned_trips (id TEXT NOT NULL, title TEXT NOT NULL, "
                                            + "srcCurrent INTEGER NOT NULL, srcName TEXT NOT NULL, srcLat REAL NOT NULL, "
                                            + "srcLon REAL NOT NULL, destName TEXT NOT NULL, destLat REAL NOT NULL, "
                                            + "destLon REAL NOT NULL, startAt INTEGER NOT NULL, note TEXT NOT NULL, "
                                            + "reminded INTEGER NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))");
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
