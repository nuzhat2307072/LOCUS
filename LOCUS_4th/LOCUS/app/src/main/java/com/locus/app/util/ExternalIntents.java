package com.locus.app.util;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.util.Locale;

/** External application integration through implicit Intents. */
public final class ExternalIntents {
    private ExternalIntents() {
    }

    public static void start(Context c, Intent intent) {
        try {
            c.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(c, com.locus.app.R.string.no_app_found, Toast.LENGTH_SHORT).show();
        }
    }

    /** Opens any installed maps app at the location (geo: URI). */
    public static void openMaps(Context c, double lat, double lon, String label) {
        String uri = String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", lat, lon, lat, lon, Uri.encode(label));
        start(c, new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
    }
}
