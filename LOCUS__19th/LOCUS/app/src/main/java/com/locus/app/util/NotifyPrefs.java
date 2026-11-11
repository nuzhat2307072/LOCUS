package com.locus.app.util;

import android.content.Context;

import com.locus.app.model.NotifyRules;

import java.util.Calendar;

/** Asks the person's notification settings (Settings > Notifications) before anything is shown. */
public final class NotifyPrefs {
    private NotifyPrefs() {
    }

    /** @param kind {@link NotifyRules#CHAT}, {@link NotifyRules#HAZARD} or {@link NotifyRules#COMMUNITY} */
    public static boolean allow(Context c, int kind) {
        return allow(c, kind, false, false);
    }

    public static boolean allow(Context c, int kind, boolean urgent, boolean silent) {
        return NotifyRules.allowed(AppPrefs.notifyOn(c, kind), AppPrefs.quietEnabled(c), AppPrefs.quietFrom(c),
                AppPrefs.quietTo(c), Calendar.getInstance().get(Calendar.HOUR_OF_DAY), urgent, silent);
    }
}
