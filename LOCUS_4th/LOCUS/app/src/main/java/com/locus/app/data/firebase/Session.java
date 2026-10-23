package com.locus.app.data.firebase;

import android.content.Context;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.locus.app.security.LocalAccounts;

/**
 * Who is using the app, and is the app unlocked right now?
 *
 *  - "remembered account": the person who signed in on this phone (Firebase user, or a local account
 *    when google-services.json is missing). Cleared only by Log out.
 *  - "unlocked": proven in THIS run of the app by password or fingerprint/screen lock. Held in memory
 *    only, so closing the app (or leaving it for a while - see AuthGate) locks it again.
 */
public final class Session {
    private static volatile boolean unlocked;

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

    // ---- lock state -----------------------------------------------------------------------------

    public static boolean isUnlocked() {
        return unlocked;
    }

    public static void unlock() {
        unlocked = true;
    }

    public static void lock() {
        unlocked = false;
    }

    // ---- account ---------------------------------------------------------------------------------

    /** Someone has signed in on this phone before (and has not logged out). */
    public static boolean isRemembered(Context c) {
        return email(c) != null;
    }

    public static String email(Context c) {
        FirebaseUser u = user(c);
        if (u != null) return u.getEmail();
        return isFirebaseReady(c) ? null : LocalAccounts.current(c);
    }

    /** True if data can be pushed to Firebase (signed-in Firebase user). */
    public static boolean canSyncOnline(Context c) {
        return user(c) != null;
    }

    public static String uid(Context c) {
        FirebaseUser u = user(c);
        if (u != null) return u.getUid();
        String e = email(c);
        return e == null ? "anonymous" : LocalAccounts.uid(e);
    }

    public static String displayName(Context c) {
        FirebaseUser u = user(c);
        if (u != null) {
            if (u.getDisplayName() != null && !u.getDisplayName().isEmpty()) return u.getDisplayName();
            return u.getEmail() == null ? "User" : u.getEmail();
        }
        String e = email(c);
        if (e == null) return "User";
        String n = LocalAccounts.name(c, e);
        return n.isEmpty() ? e : n;
    }

    /** Log out: forget the account on this phone and lock the app. */
    public static void signOut(Context c) {
        lock();
        LocalAccounts.setCurrent(c, null);
        if (isFirebaseReady(c)) FirebaseAuth.getInstance().signOut();
    }
}
