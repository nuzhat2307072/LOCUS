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
    public static final int DB_VERSION = 3;

    public static final String T_FAV = "favorites";
    public static final String T_HISTORY = "search_history";
    public static final String T_EXPLORE = "explore_history";
    public static final String T_STARTS = "start_places";

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
        createHistoryTable(db, T_HISTORY);
        createHistoryTable(db, T_EXPLORE);
        createStartsTable(db);
    }

    private static void createStartsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + T_STARTS + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "name TEXT NOT NULL, "
                + "lat REAL NOT NULL, "
                + "lon REAL NOT NULL, "
                + "k TEXT NOT NULL UNIQUE, "
                + "ts INTEGER)");
    }

    private static void createHistoryTable(SQLiteDatabase db, String table) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + table + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "query TEXT NOT NULL UNIQUE, "
                + "ts INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // version 1 -> 2 only adds the Explore history table; saved places are kept
        if (oldVersion < 2) createHistoryTable(db, T_EXPLORE);
        if (oldVersion < 3) createStartsTable(db); // version 2 -> 3: saved start places
    }

    // ---- search history CRUD (destination searches + Explore searches) ---------

    public void addHistory(String query) {
        add(T_HISTORY, query);
    }

    public List<String> recentHistory(int limit) {
        return recent(T_HISTORY, limit);
    }

    public void clearHistory() {
        getWritableDatabase().delete(T_HISTORY, null, null);
    }

    public void addExploreHistory(String query) {
        add(T_EXPLORE, query);
    }

    public List<String> recentExploreHistory(int limit) {
        return recent(T_EXPLORE, limit);
    }

    public void clearExploreHistory() {
        getWritableDatabase().delete(T_EXPLORE, null, null);
    }

    private void add(String table, String query) {
        ContentValues v = new ContentValues();
        v.put("query", query);
        v.put("ts", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(table, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private List<String> recent(String table, int limit) {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(table, new String[]{"query"}, null, null,
                null, null, "ts DESC", String.valueOf(limit))) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    // ---- saved start places ("Set location" in Smart Navigation) -------------------------------

    /** A start place the person set; saved automatically, removable. */
    public static final class StartPlace {
        public final long id;
        public final String name;
        public final double lat;
        public final double lon;

        StartPlace(long id, String name, double lat, double lon) {
            this.id = id;
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** Saves (or refreshes) a start place; the same spot (about 10 m) is stored only once. */
    public void saveStartPlace(String name, double lat, double lon) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("lat", lat);
        v.put("lon", lon);
        v.put("k", String.format(java.util.Locale.US, "%.4f,%.4f", lat, lon));
        v.put("ts", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(T_STARTS, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public List<StartPlace> startPlaces(int limit) {
        List<StartPlace> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(T_STARTS, new String[]{"_id", "name", "lat", "lon"}, null, null,
                null, null, "ts DESC", String.valueOf(limit))) {
            while (c.moveToNext()) out.add(new StartPlace(c.getLong(0), c.getString(1), c.getDouble(2), c.getDouble(3)));
        }
        return out;
    }

    public void deleteStartPlace(long id) {
        getWritableDatabase().delete(T_STARTS, "_id=?", new String[]{String.valueOf(id)});
    }

    public void clearStartPlaces() {
        getWritableDatabase().delete(T_STARTS, null, null);
    }
}
