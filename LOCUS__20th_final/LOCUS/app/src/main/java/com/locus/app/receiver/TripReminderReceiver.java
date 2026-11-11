package com.locus.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.locus.app.util.AppExecutors;
import com.locus.app.util.TripReminders;

/** Fires when a trip's reminder alarm goes off: shows every reminder that is due. */
public class TripReminderReceiver extends BroadcastReceiver {
    public static final String EXTRA_TRIP_ID = "tripId";

    @Override
    public void onReceive(final Context context, Intent intent) {
        final PendingResult result = goAsync();
        AppExecutors.io().execute(() -> {
            try {
                TripReminders.checkDue(context);
            } catch (RuntimeException e) {
                Log.w("TripReminder", "Check failed: " + e.getMessage());
            } finally {
                result.finish();
            }
        });
    }
}
