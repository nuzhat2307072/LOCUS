package com.locus.app.util;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.locus.app.R;

/** Notification channels and helpers (Notification APIs). */
public final class NotificationHelper {
    public static final String CH_HAZARD = "locus_hazard";
    public static final String CH_TRACKING = "locus_tracking";
    public static final String CH_COMMUNITY = "locus_community";
    public static final String CH_ALERTS = "locus_alerts";
    public static final String CH_TRIPS = "locus_trips";

    public static final int ID_TRACKING = 1001;
    public static final int ID_ALERTS = 1002;

    private NotificationHelper() {
    }

    public static void createChannels(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH_HAZARD,
                c.getString(R.string.ch_hazard), NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CH_TRACKING,
                c.getString(R.string.ch_tracking), NotificationManager.IMPORTANCE_LOW));
        NotificationChannel alerts = new NotificationChannel(CH_ALERTS,
                c.getString(R.string.ch_alerts), NotificationManager.IMPORTANCE_MIN);
        alerts.setShowBadge(false);
        nm.createNotificationChannel(alerts);
        nm.createNotificationChannel(new NotificationChannel(CH_TRIPS,
                c.getString(R.string.ch_trips), NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CH_COMMUNITY,
                c.getString(R.string.ch_community), NotificationManager.IMPORTANCE_DEFAULT));
    }

    public static Notification trackingNotification(Context c, Class<?> target) {
        return new NotificationCompat.Builder(c, CH_TRACKING)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(c.getString(R.string.tracking_title))
                .setContentText(c.getString(R.string.tracking_text))
                .setOngoing(true)
                .setContentIntent(open(c, target))
                .build();
    }

    /** The quiet ongoing notification of the live-alerts service (the system requires one for a service that keeps running). */
    public static Notification alertsNotification(Context c, Class<?> target) {
        return new NotificationCompat.Builder(c, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(c.getString(R.string.alerts_title))
                .setContentText(c.getString(R.string.alerts_text))
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(open(c, target))
                .build();
    }

    @SuppressLint("MissingPermission") // POST_NOTIFICATIONS is requested at runtime; if denied this is a no-op
    public static void notify(Context c, String channel, int id, String title, String text, Class<?> target) {
        Notification n = new NotificationCompat.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open(c, target))
                .build();
        NotificationManagerCompat.from(c).notify(id, n);
    }

    /** Like {@link #notify} but opens a prepared intent (e.g. one chat). */
    @SuppressLint("MissingPermission")
    public static void notifyIntent(Context c, String channel, int id, String title, String text, Intent target) {
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, id, target, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .build();
        NotificationManagerCompat.from(c).notify(id, n);
    }

    /** A notification that updates in place without sound or pop-up (the "unread messages" reminder that stays). */
    @SuppressLint("MissingPermission")
    public static void notifyQuiet(Context c, String channel, int id, String title, String text, Intent target) {
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, id, target, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .build();
        NotificationManagerCompat.from(c).notify(id, n);
    }

    private static PendingIntent open(Context c, Class<?> target) {
        Intent i = new Intent(c, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
