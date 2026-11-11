package com.locus.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.CountDownTimer;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.locus.app.R;
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.DialogForgotPasswordBinding;
import com.locus.app.model.ResetRules;
import com.locus.app.security.LocalAccounts;
import com.locus.app.util.AppPrefs;

import java.util.Locale;

/**
 * "Forgot password?" the way Facebook and Instagram do it: type your e-mail, get a link by e-mail from Firebase,
 * open it, choose a new password, log in with it. Works before logging in too.
 *
 * What makes it dependable:
 *  - the e-mail is cleaned (spaces, capitals) and checked before anything is sent;
 *  - the e-mail comes in the app's language (English, Bangla or Hindi);
 *  - a link can be asked for once a minute only (a countdown on the Resend button), which keeps clear of Firebase's
 *    own "too many requests" limit;
 *  - every Firebase error has its own plain message (no internet, too many requests, switched off in the Firebase
 *    console, invalid e-mail);
 *  - Firebase answers "sent" even for an e-mail it does not know (to stop strangers probing for accounts), so the
 *    wording says so, and an account made before Firebase was connected is explained;
 *  - after sending: an "Open e-mail app" button and a reminder to look in Spam.
 */
final class ForgotPassword {
    private ForgotPassword() {
    }

    static void show(final Activity activity, String prefillEmail) {
        if (!Session.isFirebaseReady(activity)) {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.forgot_needs_title)
                    .setMessage(R.string.forgot_needs_firebase)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.forgot_connect, (d, w) ->
                            activity.startActivity(new Intent(activity, ConnectFirebaseActivity.class)))
                    .show();
            return;
        }
        final DialogForgotPasswordBinding b = DialogForgotPasswordBinding.inflate(LayoutInflater.from(activity));
        if (prefillEmail != null) b.etForgotEmail.setText(prefillEmail);
        final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.forgot_title)
                .setView(b.getRoot())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.forgot_send, null) // set below so that errors keep the dialog open
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String email = ResetRules.normalize(b.etForgotEmail.getText() == null ? "" : b.etForgotEmail.getText().toString());
            b.tilForgotEmail.setError(null);
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                b.tilForgotEmail.setError(activity.getString(R.string.err_valid_email));
                return;
            }
            int wait = ResetRules.secondsLeft(AppPrefs.lastResetMail(activity, email), System.currentTimeMillis());
            if (wait > 0) {
                b.tilForgotEmail.setError(activity.getString(R.string.forgot_wait, wait));
                return;
            }
            v.setEnabled(false);
            send(activity, email, new Result() {
                @Override
                public void sent() {
                    dialog.dismiss();
                    showSent(activity, email);
                }

                @Override
                public void failed(String message) {
                    v.setEnabled(true);
                    b.tilForgotEmail.setError(message);
                }
            });
        }));
        dialog.show();
    }

    private interface Result {
        void sent();

        void failed(String message);
    }

    /** Sends the link and turns the outcome into a plain message. */
    private static void send(final Activity activity, final String email, final Result result) {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        auth.setLanguageCode(ResetRules.emailLanguage(Locale.getDefault().getLanguage()));
        auth.sendPasswordResetEmail(email)
                .addOnSuccessListener(r -> {
                    if (activity.isDestroyed()) return;
                    AppPrefs.setLastResetMail(activity, email, System.currentTimeMillis());
                    result.sent();
                })
                .addOnFailureListener(e -> {
                    if (activity.isDestroyed()) return;
                    boolean network = e instanceof FirebaseNetworkException;
                    String code = e instanceof FirebaseAuthException ? ((FirebaseAuthException) e).getErrorCode() : null;
                    if (e instanceof FirebaseTooManyRequestsException) code = "ERROR_TOO_MANY_REQUESTS";
                    result.failed(explain(activity, ResetRules.classify(code, network), e));
                });
    }

    private static String explain(Activity a, ResetRules.Problem p, Exception e) {
        switch (p) {
            case NETWORK: return a.getString(R.string.err_no_internet);
            case NO_ACCOUNT: return a.getString(R.string.forgot_no_account);
            case INVALID_EMAIL: return a.getString(R.string.forgot_invalid_server);
            case TOO_MANY: return a.getString(R.string.forgot_too_many);
            case DISABLED: return a.getString(R.string.forgot_disabled);
            default: return a.getString(R.string.forgot_send_failed, String.valueOf(e.getMessage()));
        }
    }

    /** "Check your e-mail": what was sent, what to do, Open e-mail app, and Resend with a countdown. */
    private static void showSent(final Activity activity, final String email) {
        ResetRules.Knowledge k = ResetRules.knowledge(AppPrefs.isCloudAccount(activity, email),
                LocalAccounts.exists(activity, email));
        // Firebase never says whether an e-mail has an account, so only a known cloud account is stated as sure.
        String msg = k == ResetRules.Knowledge.CLOUD ? activity.getString(R.string.forgot_link_sent, email)
                : activity.getString(R.string.forgot_link_generic, email);
        if (k == ResetRules.Knowledge.LOCAL_ONLY) msg += "\n\n" + activity.getString(R.string.forgot_local_note);
        msg += "\n\n" + activity.getString(R.string.forgot_tips);
        final AlertDialog d = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.forgot_sent_title)
                .setMessage(msg)
                .setPositiveButton(R.string.forgot_open_mail, null)
                .setNeutralButton(R.string.forgot_resend, null)
                .setNegativeButton(android.R.string.ok, null)
                .create();
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> openMailApp(activity));
            final Button resend = d.getButton(AlertDialog.BUTTON_NEUTRAL);
            final CountDownTimer[] timer = new CountDownTimer[1];
            final Runnable startCountdown = () -> {
                long left = ResetRules.cooldownLeftMs(AppPrefs.lastResetMail(activity, email), System.currentTimeMillis());
                resend.setEnabled(left <= 0);
                if (left <= 0) {
                    resend.setText(R.string.forgot_resend);
                    return;
                }
                timer[0] = new CountDownTimer(left, 1000) {
                    @Override
                    public void onTick(long ms) {
                        resend.setText(activity.getString(R.string.forgot_resend_in, (int) ((ms + 999) / 1000)));
                    }

                    @Override
                    public void onFinish() {
                        resend.setEnabled(true);
                        resend.setText(R.string.forgot_resend);
                    }
                }.start();
            };
            startCountdown.run();
            resend.setOnClickListener(v -> {
                resend.setEnabled(false);
                send(activity, email, new Result() {
                    @Override
                    public void sent() {
                        Toast.makeText(activity, R.string.forgot_resent, Toast.LENGTH_SHORT).show();
                        startCountdown.run();
                    }

                    @Override
                    public void failed(String message) {
                        resend.setEnabled(true);
                        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                    }
                });
            });
            d.setOnDismissListener(y -> {
                if (timer[0] != null) timer[0].cancel();
            });
        });
        d.show();
    }

    private static void openMailApp(Activity activity) {
        try {
            Intent i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (RuntimeException e) {
            Toast.makeText(activity, R.string.forgot_no_mail_app, Toast.LENGTH_LONG).show();
        }
    }
}
