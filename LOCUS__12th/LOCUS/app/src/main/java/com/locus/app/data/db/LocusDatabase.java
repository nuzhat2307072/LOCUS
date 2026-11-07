package com.locus.app.data.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.locus.app.model.Comment;
import com.locus.app.model.CommunityRequest;
import com.locus.app.model.Hazard;
import com.locus.app.model.Place;

/** Room database: offline cache of places, community requests and hazards. */
@Database(entities = {Place.class, CommunityRequest.class, Hazard.class, Comment.class}, version = 5, exportSchema = false)
public abstract class LocusDatabase extends RoomDatabase {

    public abstract PlaceDao placeDao();

    public abstract RequestDao requestDao();

    public abstract HazardDao hazardDao();

    public abstract CommentDao commentDao();

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
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return instance;
    }
}
