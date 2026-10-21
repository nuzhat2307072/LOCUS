package com.locus.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.locus.app.model.Hazard;
import com.locus.app.model.HazardType;
import com.locus.app.ui.NavigationActivity;
import com.locus.app.util.NotificationHelper;

import java.util.Locale;

/**
 * Receives the app's own custom broadcast {@link #ACTION} (declared in the manifest) and turns it
 * into a heads-up notification.
 */
public class HazardAlertReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.locus.app.ACTION_HAZARD_ADDED";

    /** Sends the custom broadcast (explicit, restricted to this app via setPackage). */
    public static void send(Context c, Hazard h) {
        Intent i = new Intent(ACTION)
                .setPackage(c.getPackageName())
                .putExtra("type", h.type)
                .putExtra("lat", h.lat)
                .putExtra("lon", h.lon);
        c.sendBroadcast(i);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        HazardType type = HazardType.fromName(intent.getStringExtra("type"));
        String text = String.format(Locale.US, "%s reported near %.4f, %.4f. Open Smart Navigation to reroute.",
                type.label, intent.getDoubleExtra("lat", 0), intent.getDoubleExtra("lon", 0));
        NotificationHelper.notify(context, NotificationHelper.CH_HAZARD,
                (int) (System.currentTimeMillis() % 100000), "Road hazard reported", text, NavigationActivity.class);
    }
}
