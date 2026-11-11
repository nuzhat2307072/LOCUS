package com.locus.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.locus.app.service.AlertsService;
import com.locus.app.work.ChatCheckWorker;

/**
 * After the phone restarts or the app is updated, the live-alerts service is started again, so alerts do not stop
 * until the person opens the app. (If Android refuses to start a service this early, the 15-minute checks
 * scheduled by the app still deliver the alerts.)
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(a)) return;
        try {
            ChatCheckWorker.schedule(context);
            com.locus.app.util.TripReminders.rescheduleAll(context); // alarms do not survive a restart
            AlertsService.startIfWanted(context);
        } catch (RuntimeException e) {
            Log.w("BootReceiver", "Could not restart live alerts: " + e.getMessage());
        }
    }
}
