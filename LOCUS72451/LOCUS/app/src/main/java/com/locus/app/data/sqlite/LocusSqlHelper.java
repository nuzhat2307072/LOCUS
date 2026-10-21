package com.locus.app.data.sqlite;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain SQLite (no Room) database with two tables:
 *  - favorites: saved places, exposed to the app through FavoritesProvider (a Content Provider)
 *  - search_history: destination searches (CRUD demo)
 */
public class LocusSqlHelper extends SQLiteOpenHelper {
    public static final String DB_NAME = "locus_sqlite.db";
    public static final int DB_VERSION = 1;

    public static final String T_FAV = "favorites";
    public static final String T_HISTORY = "search_history";

    public LocusSqlHelper(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T_FAV + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "name TEXT NOT NULL, "
                + "category TEXT, "
                + "lat REAL NOT NULL, "
                + "lon REAL NOT NULL, "
                + "note TEXT, "
                + "created INTEGER, "
                + "UNIQUE(name, lat, lon))");
        db.execSQL("CREATE TABLE " + T_HISTORY + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "query TEXT NOT NULL UNIQUE, "
                + "ts INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + T_FAV);
        db.execSQL("DROP TABLE IF EXISTS " + T_HISTORY);
        onCreate(db);
    }

    // ---- search history CRUD -------------------------------------------------

    public void addHistory(String query) {
        ContentValues v = new ContentValues();
        v.put("query", query);
        v.put("ts", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(T_HISTORY, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public List<String> recentHistory(int limit) {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(T_HISTORY, new String[]{"query"}, null, null,
                null, null, "ts DESC", String.valueOf(limit))) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    public void clearHistory() {
        getWritableDatabase().delete(T_HISTORY, null, null);
    }
}
