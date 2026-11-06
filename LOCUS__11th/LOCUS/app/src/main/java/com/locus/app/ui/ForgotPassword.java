package com.locus.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.locus.app.R;
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.DialogForgotPasswordBinding;

/**
 * "Forgot password?" the way Facebook and Instagram do it: type your e-mail, get a link by e-mail from
 * Firebase, open it, choose a new password, log in with it. Works before logging in too.
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
            final String email = b.etForgotEmail.getText() == null ? "" : b.etForgotEmail.getText().toString().trim();
            b.tilForgotEmail.setError(null);
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                b.tilForgotEmail.setError(activity.getString(R.string.err_valid_email));
                return;
            }
            v.setEnabled(false);
            FirebaseAuth.getInstance().sendPasswordResetEmail(email)
                    .addOnSuccessListener(r -> {
                        dialog.dismiss();
                        if (activity.isDestroyed()) return;
                        new MaterialAlertDialogBuilder(activity)
                                .setTitle(R.string.forgot_sent_title)
                                .setMessage(activity.getString(R.string.forgot_link_sent, email))
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                    })
                    .addOnFailureListener(e -> {
                        v.setEnabled(true);
                        if (e instanceof FirebaseAuthInvalidUserException) {
                            b.tilForgotEmail.setError(activity.getString(R.string.forgot_no_account));
                        } else if (e instanceof FirebaseNetworkException) {
                            b.tilForgotEmail.setError(activity.getString(R.string.err_no_internet));
                        } else {
                            b.tilForgotEmail.setError(activity.getString(R.string.forgot_send_failed,
                                    String.valueOf(e.getMessage())));
                        }
                    });
        }));
        dialog.show();
    }
}
