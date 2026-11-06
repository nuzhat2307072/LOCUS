package com.locus.app.data.provider;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.locus.app.data.sqlite.LocusSqlHelper;

/**
 * Content Provider over the SQLite favorites table.
 * Lifecycle: onCreate() runs once when the process starts (before Application.onCreate);
 * query/insert/update/delete are called on binder threads.
 */
public class FavoritesProvider extends ContentProvider {
    private static final int FAVORITES = 1;
    private static final int FAVORITE_ID = 2;
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        MATCHER.addURI(FavoritesContract.AUTHORITY, "favorites", FAVORITES);
        MATCHER.addURI(FavoritesContract.AUTHORITY, "favorites/#", FAVORITE_ID);
    }

    private LocusSqlHelper helper;

    @Override
    public boolean onCreate() {
        helper = new LocusSqlHelper(getContext());
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        SQLiteDatabase db = helper.getReadableDatabase();
        String sel = selection;
        String[] args = selectionArgs;
        if (MATCHER.match(uri) == FAVORITE_ID) {
            sel = idClause(selection);
            args = idArgs(uri, selectionArgs);
        } else if (MATCHER.match(uri) != FAVORITES) {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
        Cursor c = db.query(LocusSqlHelper.T_FAV, projection, sel, args, null, null,
                sortOrder == null ? "created DESC" : sortOrder);
        c.setNotificationUri(getContext().getContentResolver(), uri);
        return c;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        if (MATCHER.match(uri) != FAVORITES || values == null) {
            throw new IllegalArgumentException("Unsupported insert " + uri);
        }
        long id = helper.getWritableDatabase().insertWithOnConflict(LocusSqlHelper.T_FAV, null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
        if (id < 0) return null;
        getContext().getContentResolver().notifyChange(FavoritesContract.CONTENT_URI, null);
        return ContentUris.withAppendedId(FavoritesContract.CONTENT_URI, id);
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        if (values == null) return 0;
        String sel = selection;
        String[] args = selectionArgs;
        if (MATCHER.match(uri) == FAVORITE_ID) {
            sel = idClause(selection);
            args = idArgs(uri, selectionArgs);
        } else if (MATCHER.match(uri) != FAVORITES) {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
        int n = helper.getWritableDatabase().update(LocusSqlHelper.T_FAV, values, sel, args);
        if (n > 0) getContext().getContentResolver().notifyChange(FavoritesContract.CONTENT_URI, null);
        return n;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        String sel = selection;
        String[] args = selectionArgs;
        if (MATCHER.match(uri) == FAVORITE_ID) {
            sel = idClause(selection);
            args = idArgs(uri, selectionArgs);
        } else if (MATCHER.match(uri) != FAVORITES) {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
        int n = helper.getWritableDatabase().delete(LocusSqlHelper.T_FAV, sel, args);
        if (n > 0) getContext().getContentResolver().notifyChange(FavoritesContract.CONTENT_URI, null);
        return n;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        switch (MATCHER.match(uri)) {
            case FAVORITES:
                return "vnd.android.cursor.dir/vnd.com.locus.app.favorite";
            case FAVORITE_ID:
                return "vnd.android.cursor.item/vnd.com.locus.app.favorite";
            default:
                return null;
        }
    }

    private static String idClause(String selection) {
        return "_id = ?" + (selection == null || selection.isEmpty() ? "" : " AND (" + selection + ")");
    }

    private static String[] idArgs(Uri uri, String[] selectionArgs) {
        String id = uri.getLastPathSegment();
        if (selectionArgs == null) return new String[]{id};
        String[] out = new String[selectionArgs.length + 1];
        out[0] = id;
        System.arraycopy(selectionArgs, 0, out, 1, selectionArgs.length);
        return out;
    }
}
