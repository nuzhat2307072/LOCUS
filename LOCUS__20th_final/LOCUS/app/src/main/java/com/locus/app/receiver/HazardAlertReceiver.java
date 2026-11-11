package com.locus.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.locus.app.R;
import com.locus.app.model.Hazard;
import com.locus.app.model.HazardType;
import com.locus.app.ui.NavigationActivity;
import com.locus.app.util.GeoUtils;
import com.locus.app.model.NotifyRules;
import com.locus.app.util.NotificationHelper;
import com.locus.app.util.NotifyPrefs;

/**
 * Receives the app's own custom broadcast {@link #ACTION} (declared in the manifest) and turns it
 * into a heads-up notification: "hazard reported near you" or "hazard cleared".
 */
public class HazardAlertReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.locus.app.ACTION_HAZARD_ADDED";

    /** Sends the custom broadcast (explicit, restricted to this app via setPackage). */
    public static void send(Context c, Hazard h, boolean cleared, double metersFromMe) {
        Intent i = new Intent(ACTION)
                .setPackage(c.getPackageName())
                .putExtra("id", h.id)
                .putExtra("type", h.type)
                .putExtra("note", h.description == null ? "" : h.description)
                .putExtra("cleared", cleared)
                .putExtra("meters", metersFromMe);
        c.sendBroadcast(i);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        if (!NotifyPrefs.allow(context, NotifyRules.HAZARD)) return; // switched off or quiet hours
        HazardType type = HazardType.fromName(intent.getStringExtra("type"));
        boolean cleared = intent.getBooleanExtra("cleared", false);
        String near = GeoUtils.distanceText(context, intent.getDoubleExtra("meters", 0));
        String note = intent.getStringExtra("note");
        String text = context.getString(cleared ? R.string.hazard_cleared_text : R.string.hazard_alert_text,
                context.getString(type.labelRes), near);
        if (!cleared && note != null && !note.trim().isEmpty()) text += "\n" + note.trim();
        String id = intent.getStringExtra("id");
        int notificationId = (id == null ? 0 : id.hashCode()) * 2 + (cleared ? 1 : 0); // one per hazard and kind
        NotificationHelper.notify(context, NotificationHelper.CH_HAZARD, notificationId,
                context.getString(cleared ? R.string.hazard_cleared_title : R.string.hazard_alert_title),
                text, NavigationActivity.class);
    }
}
