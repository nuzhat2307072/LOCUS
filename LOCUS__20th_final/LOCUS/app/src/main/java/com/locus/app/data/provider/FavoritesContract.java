package com.locus.app.data.provider;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;

import com.locus.app.model.Place;
import com.locus.app.util.AppExecutors;

/** Public contract of the Saved Places content provider. */
public final class FavoritesContract {
    public static final String AUTHORITY = "com.locus.app.provider";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/favorites");

    public static final String COL_ID = "_id";
    public static final String COL_NAME = "name";
    public static final String COL_CATEGORY = "category";
    public static final String COL_LAT = "lat";
    public static final String COL_LON = "lon";
    public static final String COL_NOTE = "note";
    public static final String COL_CREATED = "created";
    /** "" = no list, "@home", "@work", or the name of the person's own list. */
    public static final String COL_LIST = "list_name";
    public static final String LIST_HOME = "@home";
    public static final String LIST_WORK = "@work";

    private FavoritesContract() {
    }

    /** Saves a place through the content provider (runs on a background thread). */
    public static void save(Context context, Place p, Runnable onDone) {
        final ContentResolver resolver = context.getApplicationContext().getContentResolver();
        AppExecutors.io().execute(() -> {
            ContentValues v = new ContentValues();
            v.put(COL_NAME, p.name);
            v.put(COL_CATEGORY, p.category);
            v.put(COL_LAT, p.lat);
            v.put(COL_LON, p.lon);
            v.put(COL_NOTE, "");
            v.put(COL_CREATED, System.currentTimeMillis());
            v.put(COL_LIST, "");
            resolver.insert(CONTENT_URI, v);
            if (onDone != null) AppExecutors.main().post(onDone);
        });
    }
}
