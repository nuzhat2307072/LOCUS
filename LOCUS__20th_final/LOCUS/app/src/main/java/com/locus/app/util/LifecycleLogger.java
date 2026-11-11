package com.locus.app.util;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Observes the lifecycle callbacks of every Activity in the app and keeps an in-memory log that the
 * "Lifecycle log" screen displays.
 */
public class LifecycleLogger implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "LOCUS-Lifecycle";
    private static final int MAX = 300;
    private static final List<String> LOG = new ArrayList<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public static synchronized List<String> snapshot() {
        return new ArrayList<>(LOG);
    }

    public static synchronized void clear() {
        LOG.clear();
    }

    private static synchronized void add(Activity a, String callback) {
        String line = FMT.format(new Date()) + "  " + a.getClass().getSimpleName() + "  ->  " + callback;
        Log.d(TAG, line);
        LOG.add(0, line);
        if (LOG.size() > MAX) LOG.remove(LOG.size() - 1);
    }

    @Override public void onActivityCreated(Activity a, Bundle b) { add(a, "onCreate"); }
    @Override public void onActivityStarted(Activity a) { add(a, "onStart"); }
    @Override public void onActivityResumed(Activity a) { add(a, "onResume"); }
    @Override public void onActivityPaused(Activity a) { add(a, "onPause"); }
    @Override public void onActivityStopped(Activity a) { add(a, "onStop"); }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { add(a, "onSaveInstanceState"); }
    @Override public void onActivityDestroyed(Activity a) { add(a, "onDestroy"); }
}
