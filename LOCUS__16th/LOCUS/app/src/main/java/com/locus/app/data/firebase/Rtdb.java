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

    /** @throws IllegalStateException when Firebase is not connected */
    public static FirebaseDatabase get() {
        String url = url();
        if (url == null) throw new IllegalStateException("Firebase is not connected");
        return FirebaseDatabase.getInstance(FirebaseApp.getInstance(), url);
    }
}
