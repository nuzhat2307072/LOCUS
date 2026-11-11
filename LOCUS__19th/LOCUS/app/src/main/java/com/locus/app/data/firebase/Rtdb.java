package com.locus.app.data.firebase;

import androidx.annotation.Nullable;

import com.google.firebase.FirebaseApp;
import com.google.firebase.database.FirebaseDatabase;

/**
 * The one place that opens the Realtime Database (live sharing, hazard votes, chat).
 * When google-services.json has no "firebase_url" (the database was created AFTER the file was downloaded) the
 * usual address of a new database, https://PROJECT-default-rtdb.firebaseio.com, is used, so the features still work.
 */
public final class Rtdb {
    private Rtdb() {
    }

    /** The database address that is used, or null when Firebase is not running. */
    @Nullable
    public static String url() {
        try {
            FirebaseApp app = FirebaseApp.getInstance();
            String url = app.getOptions().getDatabaseUrl();
            if (url != null && !url.isEmpty()) return url;
            String project = app.getOptions().getProjectId();
            return project == null ? null : "https://" + project + "-default-rtdb.firebaseio.com";
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private static boolean configured;

    /**
     * @throws IllegalStateException when Firebase is not connected
     * The first call turns on the disk cache: chats, messages and the chats list that were loaded once are still
     * there without internet (and open instantly), and messages written offline are sent when the phone is online again.
     */
    public static synchronized FirebaseDatabase get() {
        String url = url();
        if (url == null) throw new IllegalStateException("Firebase is not connected");
        FirebaseDatabase db = FirebaseDatabase.getInstance(FirebaseApp.getInstance(), url);
        if (!configured) {
            configured = true;
            try {
                db.setPersistenceEnabled(true);
                db.setPersistenceCacheSizeBytes(80L * 1024 * 1024);
            } catch (Exception ignored) {
                // already in use (cannot be changed any more): the app still works, only without the disk cache
            }
        }
        return db;
    }
}
