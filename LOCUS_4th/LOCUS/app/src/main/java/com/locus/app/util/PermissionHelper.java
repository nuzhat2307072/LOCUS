package com.locus.app.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

/** Runtime permission helpers (Android permissions + runtime requests). */
public final class PermissionHelper {
    private PermissionHelper() {
    }

    public static boolean hasLocation(Context c) {
        return granted(c, Manifest.permission.ACCESS_FINE_LOCATION)
                || granted(c, Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    public static String[] locationPermissions() {
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
    }

    /** Notifications need a runtime permission only on Android 13+; empty array on older versions. */
    public static String[] notificationPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.POST_NOTIFICATIONS};
        }
        return new String[0];
    }

    public static boolean hasNotifications(Context c) {
        return Build.VERSION.SDK_INT < 33 || granted(c, Manifest.permission.POST_NOTIFICATIONS);
    }

    private static boolean granted(Context c, String p) {
        return ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED;
    }
}
