package com.locus.app.data.firebase;

import android.content.Context;

import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;
import com.google.firebase.auth.FirebaseUser;
import com.locus.app.security.LocalAccounts;
import com.locus.app.security.LockoutPolicy;
import com.locus.app.util.AppExecutors;
import com.locus.app.util.AppPrefs;

/**
 * Change password: the CURRENT password must be proven first, then the new one is set, then an e-mail tells
 * the owner. Works for an online (Firebase) account and for an account stored on this phone.
 * Wrong current passwords count towards the same lock-out as the login screen, so the screen cannot be used
 * to guess the password.
 */
public final class PasswordChanger {
    public interface Callback {
        void onSuccess(boolean emailSent, boolean emailConfigured);

        void onWrongCurrent(int attemptsLeft);

        void onLocked(int secondsLeft);

        void onWeak();

        void onNoInternet();

        void onError(String message);
    }

    private PasswordChanger() {
    }

    public static void change(Context context, String current, String next, Callback cb) {
        final Context app = context.getApplicationContext();
        long left = AppPrefs.lockedUntil(app) - System.currentTimeMillis();
        if (left > 0) {
            cb.onLocked((int) Math.ceil(left / 1000.0));
            return;
        }
        final String email = Session.email(app);
        final String name = Session.displayName(app);
        if (email == null) {
            cb.onError("Not signed in");
            return;
        }
        if (Session.canSyncOnline(app)) {
            changeOnline(app, email, name, current, next, cb);
        } else if (!Session.isFirebaseReady(app)) {
            changeLocal(app, email, name, current, next, cb);
        } else {
            cb.onError("Not signed in");
        }
    }

    // ---- forgot password -------------------------------------------------------------------------------

    public interface ResetCallback {
        void onSent();

        void onNoInternet();

        void onError(String message);
    }

    /** Online account: Firebase e-mails a reset link to the signed-in address. */
    public static void sendResetLink(Context context, final ResetCallback cb) {
        final String email = Session.email(context.getApplicationContext());
        if (email == null || !Session.isFirebaseReady(context)) {
            cb.onError("Not signed in");
            return;
        }
        FirebaseAuth.getInstance().sendPasswordResetEmail(email)
                .addOnSuccessListener(v -> cb.onSent())
                .addOnFailureListener(e -> {
                    if (e instanceof FirebaseNetworkException) cb.onNoInternet();
                    else cb.onError(String.valueOf(e.getMessage()));
                });
    }

    /** Account on this phone: the one-time code (already checked by the caller) allows a new password. */
    public static void resetLocal(Context context, String next, Callback cb) {
        final Context app = context.getApplicationContext();
        final String email = Session.email(app);
        if (email == null) {
            cb.onError("Not signed in");
            return;
        }
        finish(app, email, Session.displayName(app), next, cb);
    }

    // ---- online account ------------------------------------------------------------------------------

    private static void changeOnline(final Context app, final String email, final String name,
                                     final String current, final String next, final Callback cb) {
        final FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            cb.onError("Not signed in");
            return;
        }
        AuthCredential credential = EmailAuthProvider.getCredential(email, current);
        user.reauthenticate(credential)
                .addOnSuccessListener(r -> user.updatePassword(next)
                        .addOnSuccessListener(x -> finish(app, email, name, next, cb))
                        .addOnFailureListener(e -> {
                            if (e instanceof FirebaseAuthWeakPasswordException) cb.onWeak();
                            else fail(e, cb);
                        }))
                .addOnFailureListener(e -> {
                    if (e instanceof FirebaseAuthInvalidCredentialsException) wrong(app, cb);
                    else if (e instanceof FirebaseTooManyRequestsException) cb.onLocked(60);
                    else fail(e, cb);
                });
    }

    private static void fail(Exception e, Callback cb) {
        if (e instanceof FirebaseNetworkException) cb.onNoInternet();
        else cb.onError(String.valueOf(e.getMessage()));
    }

    // ---- account stored on this phone ----------------------------------------------------------------

    private static void changeLocal(final Context app, final String email, final String name,
                                    final String current, final String next, final Callback cb) {
        AppExecutors.io().execute(() -> {
            final boolean ok = LocalAccounts.verify(app, email, current);
            if (!ok) {
                AppExecutors.main().post(() -> wrong(app, cb));
                return;
            }
            finish(app, email, name, next, cb);
        });
    }

    // ---- shared ----------------------------------------------------------------------------------------

    private static void wrong(Context app, Callback cb) {
        int failures = AppPrefs.failedLogins(app) + 1;
        AppPrefs.setFailedLogins(app, failures);
        int lock = LockoutPolicy.lockSeconds(failures);
        if (lock > 0) {
            AppPrefs.setLockedUntil(app, System.currentTimeMillis() + lock * 1000L);
            cb.onLocked(lock);
        } else {
            cb.onWrongCurrent(LockoutPolicy.attemptsLeft(failures));
        }
    }

    /** Password changed: keep the phone's copy (for offline unlock) in step, then send the e-mail. */
    private static void finish(final Context app, final String email, final String name, final String next,
                               final Callback cb) {
        AppExecutors.io().execute(() -> {
            LocalAccounts.save(app, name, email, next);
            AppPrefs.setFailedLogins(app, 0);
            AppPrefs.setLockedUntil(app, 0);
            final boolean configured = PasswordChangeNotifier.isConfigured();
            final boolean sent = configured && PasswordChangeNotifier.send(email, name);
            AppExecutors.main().post(() -> cb.onSuccess(sent, configured));
        });
    }
}
