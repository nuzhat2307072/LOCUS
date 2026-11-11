package com.locus.app.util;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.text.format.DateFormat;
import android.util.Log;

import com.locus.app.R;
import com.locus.app.data.db.LocusDatabase;
import com.locus.app.model.Trip;
import com.locus.app.model.TripRules;
import com.locus.app.receiver.TripReminderReceiver;
import com.locus.app.ui.TripCalendarActivity;

import java.util.Date;
import java.util.List;

/**
 * The "6 hours before" reminder of every planned trip. Two independent ways make sure it is shown:
 *  - an alarm set for each trip (works with the app closed; re-set after a restart and whenever the app opens),
 *  - the 15-minute background check, which shows any reminder that is due and was missed (phone off, alarm lost).
 * A reminder is shown only once per trip.
 */
public final class TripReminders {
    private static final String TAG = "TripReminders";
    private static final int ID_BASE = 90000;

    private TripReminders() {
    }

    private static PendingIntent alarmIntent(Context c, String tripId) {
        Intent i = new Intent(c, TripReminderReceiver.class).setAction("com.locus.app.TRIP_REMINDER")
                .putExtra(TripReminderReceiver.EXTRA_TRIP_ID, tripId);
        return PendingIntent.getBroadcast(c, requestCode(tripId), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static int requestCode(String tripId) {
        return ID_BASE + (tripId.hashCode() & 0xFFFFF);
    }

    /** Sets (or replaces) the alarm of one trip. A trip saved less than 6 hours before it starts is reminded at once. */
    public static void schedule(Context ctx, Trip t) {
        Context app = ctx.getApplicationContext();
        if (t.reminded || t.startAt <= System.currentTimeMillis()) {
            cancel(app, t.id);
            return;
        }
        long when = Math.max(TripRules.reminderAt(t.startAt), System.currentTimeMillis() + 3000);
        try {
            AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                // inexact but allowed while the phone sleeps, and needs no special permission: fine for a 6 h reminder
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, alarmIntent(app, t.id));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Alarm not set, the background check will remind instead: " + e.getMessage());
        }
    }

    public static void cancel(Context ctx, String tripId) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(alarmIntent(ctx.getApplicationContext(), tripId));
        } catch (RuntimeException ignored) {
            // nothing to cancel
        }
    }

    /** After a restart or app start: set every pending alarm again (alarms do not survive a reboot). */
    public static void rescheduleAll(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        AppExecutors.io().execute(() -> {
            try {
                for (Trip t : LocusDatabase.get(app).tripDao().awaitingReminder(System.currentTimeMillis())) schedule(app, t);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not set trip alarms: " + e.getMessage());
            }
        });
    }

    /** Shows every reminder that is due (blocking: call from a background thread). Returns how many were shown. */
    public static int checkDue(Context ctx) {
        Context app = ctx.getApplicationContext();
        long now = System.currentTimeMillis();
        int shown = 0;
        List<Trip> list = LocusDatabase.get(app).tripDao().awaitingReminder(now);
        for (Trip t : list) {
            if (!TripRules.reminderDue(t.startAt, t.reminded, now)) continue;
            LocusDatabase.get(app).tripDao().markReminded(t.id); // first, so it can never be shown twice
            show(app, t, now);
            shown++;
        }
        return shown;
    }

    private static void show(Context app, Trip t, long now) {
        String from = t.srcCurrent || t.srcName.isEmpty() ? app.getString(R.string.trip_current_location) : t.srcName;
        String when = DateFormat.getMediumDateFormat(app).format(new Date(t.startAt)) + " "
                + DateFormat.getTimeFormat(app).format(new Date(t.startAt));
        NotificationHelper.notifyIntent(app, NotificationHelper.CH_TRIPS, ID_BASE + (t.id.hashCode() & 0xFFFFF),
                app.getString(R.string.trip_reminder_title, t.destName, duration(app, TripRules.minutesUntil(t.startAt, now))),
                app.getString(R.string.trip_reminder_text, from, when),
                new Intent(app, TripCalendarActivity.class).putExtra(TripCalendarActivity.EXTRA_DAY, t.startAt));
    }

    /** "5 h 59 min", "2 h", "40 min". */
    public static String duration(Context c, long minutes) {
        long h = minutes / 60;
        long m = minutes % 60;
        if (h > 0 && m > 0) return c.getString(R.string.trip_dur_hm, h, m);
        if (h > 0) return c.getString(R.string.trip_dur_h, h);
        return c.getString(R.string.trip_dur_min, m);
    }
}
