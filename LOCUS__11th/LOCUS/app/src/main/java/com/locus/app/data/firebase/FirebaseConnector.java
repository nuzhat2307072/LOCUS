package com.locus.app.data.firebase;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Connects the app to the person's own Firebase project WITHOUT rebuilding the app: the downloaded
 * google-services.json is chosen on the phone, kept, and used to start Firebase every time the app starts.
 * (When the file was already put in the app folder at build time, that built-in connection is used instead.)
 */
public final class FirebaseConnector {
    private static final String PREFS = "locus_firebase_cfg";
    private static final String KEY_JSON = "google_services_json";

    private FirebaseConnector() {
    }

    /** Thrown with a ready-to-show message. */
    public static final class BadFile extends Exception {
        public BadFile(String message) {
            super(message);
        }
    }

    /** Called first thing when the app starts. */
    public static void init(Context context) {
        Context app = context.getApplicationContext();
        try {
            String json = prefs(app).getString(KEY_JSON, null);
            if (json != null) {
                FirebaseOptions options = parse(app, json);
                if (!FirebaseApp.getApps(app).isEmpty()) {
                    FirebaseApp.getInstance().delete();
                }
                FirebaseApp.initializeApp(app, options);
            }
        } catch (Exception e) {
            // a damaged file must never stop the app from starting; Connect Firebase shows the problem
        }
    }

    /** The Firebase project this app is connected to, or null. */
    public static String projectId(Context context) {
        try {
            Context app = context.getApplicationContext();
            if (FirebaseApp.getApps(app).isEmpty()) return null;
            return FirebaseApp.getInstance().getOptions().getProjectId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Checks the chosen file and keeps it and initializes Firebase immediately.
     */
    public static boolean connect(Context context, String json) throws BadFile {
        Context app = context.getApplicationContext();
        FirebaseOptions options = parse(app, json);
        prefs(app).edit().putString(KEY_JSON, json).apply();
        try {
            if (!FirebaseApp.getApps(app).isEmpty()) {
                FirebaseApp.getInstance().delete();
            }
        } catch (Exception ignored) {
        }
        FirebaseApp.initializeApp(app, options);
        return true;
    }

    private static SharedPreferences prefs(Context app) {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static FirebaseOptions parse(Context app, String text) throws BadFile {
        try {
            JSONObject root = new JSONObject(text);
            JSONObject info = root.getJSONObject("project_info");
            String projectId = info.getString("project_id");
            String number = info.optString("project_number", "");
            String bucket = info.optString("storage_bucket", "");
            String dbUrl = info.optString("firebase_url", "");

            String want = app.getPackageName().trim();
            JSONArray clients = root.getJSONArray("client");
            JSONObject mine = null;
            StringBuilder found = new StringBuilder();
            for (int i = 0; i < clients.length(); i++) {
                JSONObject c = clients.getJSONObject(i);
                String pkg = c.getJSONObject("client_info").getJSONObject("android_client_info")
                        .optString("package_name", "").trim();
                if (pkg.equalsIgnoreCase(want)) mine = c;
                if (found.length() > 0) found.append(", ");
                found.append(pkg);
            }
            if (mine == null) {
                throw new BadFile(app.getString(com.locus.app.R.string.connect_fb_err_package, found, want));
            }
            String appId = mine.getJSONObject("client_info").getString("mobilesdk_app_id");
            String apiKey = mine.getJSONArray("api_key").getJSONObject(0).getString("current_key");

            if (dbUrl.isEmpty()) dbUrl = "https://" + projectId + "-default-rtdb.firebaseio.com";

            FirebaseOptions.Builder b = new FirebaseOptions.Builder()
                    .setProjectId(projectId)
                    .setApplicationId(appId)
                    .setApiKey(apiKey)
                    .setDatabaseUrl(dbUrl);
            if (!number.isEmpty()) b.setGcmSenderId(number);
            if (!bucket.isEmpty()) b.setStorageBucket(bucket);
            return b.build();
        } catch (BadFile e) {
            throw e;
        } catch (Exception e) {
            throw new BadFile(app.getString(com.locus.app.R.string.connect_fb_err_json));
        }
    }
}
