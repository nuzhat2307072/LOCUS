package com.locus.app.security;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.locus.app.data.firebase.Session;
import com.locus.app.ui.LoginActivity;
import com.locus.app.ui.RegisterActivity;
import com.locus.app.ui.SplashActivity;

/**
 * One place that guards every screen. The app locks when
 *  - the process starts (the unlocked flag only lives in memory),
 *  - the person leaves the app (all screens closed, e.g. back button or swipe from recents),
 *  - the app stays in the background longer than AUTO_LOCK_MS,
 *  - or "Lock app" / "Log out" is chosen.
 * Whenever a protected screen starts while locked, the task is replaced by the login screen.
 */
public class AuthGate implements Application.ActivityLifecycleCallbacks {
    /** Leaving the app for longer than this locks it (2 minutes). */
    public static final long AUTO_LOCK_MS = 2 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int created;
    private int started;
    private long lastStoppedAt;

    private static boolean isAuthScreen(Activity a) {
        return a instanceof SplashActivity || a instanceof LoginActivity || a instanceof RegisterActivity;
    }

    @Override
    public void onActivityCreated(Activity a, Bundle state) {
        created++;
    }

    @Override
    public void onActivityStarted(Activity a) {
        if (started == 0 && lastStoppedAt > 0 && Session.isUnlocked()
                && SystemClock.elapsedRealtime() - lastStoppedAt > AUTO_LOCK_MS) {
            Session.lock();
        }
        started++;
        if (!Session.isUnlocked() && !isAuthScreen(a)) {
            Intent i = new Intent(a, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            a.startActivity(i);
        }
    }

    @Override
    public void onActivityStopped(Activity a) {
        started = Math.max(0, started - 1);
        if (started == 0 && !a.isChangingConfigurations()) lastStoppedAt = SystemClock.elapsedRealtime();
    }

    @Override
    public void onActivityDestroyed(Activity a) {
        created = Math.max(0, created - 1);
        // every screen is gone and it is not a rotation: the person has left the app. The short delay
        // avoids locking in the moment between one screen closing and the next one opening.
        if (created == 0 && !a.isChangingConfigurations()) {
            handler.postDelayed(() -> {
                if (created == 0) Session.lock();
            }, 1000);
        }
    }

    @Override
    public void onActivityResumed(Activity a) {
    }

    @Override
    public void onActivityPaused(Activity a) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity a, Bundle outState) {
    }
}
