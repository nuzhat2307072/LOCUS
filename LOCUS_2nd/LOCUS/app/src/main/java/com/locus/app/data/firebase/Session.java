package com.locus.app.data.firebase;

import android.content.Context;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.locus.app.util.AppPrefs;

/**
 * Who is using the app? Combines Firebase Authentication with an offline "guest" mode so the app
 * still runs on a phone before google-services.json has been added.
 */
public final class Session {
    private Session() {
    }

    /** True when google-services.json was present at build time and Firebase initialised. */
    public static boolean isFirebaseReady(Context c) {
        try {
            return !FirebaseApp.getApps(c.getApplicationContext()).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static FirebaseUser user(Context c) {
        return isFirebaseReady(c) ? FirebaseAuth.getInstance().getCurrentUser() : null;
    }

    public static boolean isLoggedIn(Context c) {
        return AppPrefs.isGuest(c) || user(c) != null;
    }

    /** True if data can be pushed to Firebase (signed-in, non-guest user). */
    public static boolean canSyncOnline(Context c) {
        return !AppPrefs.isGuest(c) && user(c) != null;
    }

    public static String uid(Context c) {
        FirebaseUser u = AppPrefs.isGuest(c) ? null : user(c);
        return u == null ? "guest" : u.getUid();
    }

    public static String displayName(Context c) {
        FirebaseUser u = AppPrefs.isGuest(c) ? null : user(c);
        if (u == null) return "Guest";
        if (u.getDisplayName() != null && !u.getDisplayName().isEmpty()) return u.getDisplayName();
        return u.getEmail() == null ? "User" : u.getEmail();
    }

    public static void signOut(Context c) {
        AppPrefs.setGuest(c, false);
        if (isFirebaseReady(c)) FirebaseAuth.getInstance().signOut();
    }
}
