package com.locus.app.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.locus.app.data.firebase.PasswordChangeNotifier;
import com.locus.app.util.AppExecutors;

import androidx.appcompat.app.AppCompatActivity;

import com.locus.app.R;
import com.locus.app.data.firebase.PasswordChanger;
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.ActivityChangePasswordBinding;
import com.locus.app.security.PasswordRules;
import com.locus.app.security.ResetCode;
import com.locus.app.util.Labels;

/**
 * Change password in two steps on one screen: first the CURRENT password (proves it is really the owner),
 * then the NEW password twice. When it works an e-mail tells the owner that the password changed.
 */
public class ChangePasswordActivity extends AppCompatActivity {
    private ActivityChangePasswordBinding b;
    private final ResetCode resetCode = new ResetCode();
    private boolean resetMode;   // "Forgot password" for an account stored on this phone: code instead of current password

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityChangePasswordBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        String email = Session.email(this);
        b.tvIntro.setText(getString(R.string.change_password_intro, email == null ? "" : email));
        b.btnChange.setOnClickListener(v -> submit());
        b.tvForgot.setOnClickListener(v -> forgot());
        b.tvBackToChange.setOnClickListener(v -> setResetMode(false));
        b.etNew.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String p = s.toString();
                if (p.isEmpty()) {
                    b.strengthBar.setProgress(0);
                    b.tvStrength.setText(R.string.password_rules);
                } else {
                    int strength = PasswordRules.strength(p);
                    b.strengthBar.setProgressCompat(strength, true);
                    b.tvStrength.setText(Labels.strengthText(ChangePasswordActivity.this, strength));
                }
            }
        });
    }

    private void submit() {
        b.tilCurrent.setError(null);
        b.tilNew.setError(null);
        b.tilConfirm.setError(null);
        b.tvResult.setVisibility(View.GONE);

        final String current = b.etCurrent.getText() == null ? "" : b.etCurrent.getText().toString();
        final String next = b.etNew.getText() == null ? "" : b.etNew.getText().toString();
        final String confirm = b.etConfirm.getText() == null ? "" : b.etConfirm.getText().toString();

        final String code = b.etCode.getText() == null ? "" : b.etCode.getText().toString().trim();
        if (resetMode) {
            if (code.length() != 6) {
                b.tilCode.setError(getString(R.string.err_code_six_digits));
                return;
            }
        } else if (current.isEmpty()) {
            b.tilCurrent.setError(getString(R.string.err_enter_current_password));
            return;
        }
        int problem = PasswordRules.check(next, Session.email(this));
        if (problem != PasswordRules.OK) {
            b.tilNew.setError(Labels.passwordProblem(this, problem));
            return;
        }
        if (!resetMode && next.equals(current)) {
            b.tilNew.setError(getString(R.string.err_new_same_as_current));
            return;
        }
        if (!next.equals(confirm)) {
            b.tilConfirm.setError(getString(R.string.err_passwords_mismatch));
            return;
        }

        final PasswordChanger.Callback done = new PasswordChanger.Callback() {
            @Override
            public void onSuccess(boolean emailSent, boolean emailConfigured) {
                if (isDestroyed()) return;
                setBusy(false);
                b.etCurrent.setText("");
                b.etNew.setText("");
                b.etConfirm.setText("");
                setResetMode(false);
                showResult(getString(R.string.password_changed_ok)); // just this, nothing else
            }

            @Override
            public void onWrongCurrent(int attemptsLeft) {
                if (isDestroyed()) return;
                setBusy(false);
                b.tilCurrent.setError(getString(R.string.err_current_password_wrong, attemptsLeft));
            }

            @Override
            public void onLocked(int secondsLeft) {
                if (isDestroyed()) return;
                setBusy(false);
                showResult(getString(R.string.err_password_change_locked, Math.max(1, secondsLeft)));
            }

            @Override
            public void onWeak() {
                if (isDestroyed()) return;
                setBusy(false);
                b.tilNew.setError(getString(R.string.err_weak_password));
            }

            @Override
            public void onNoInternet() {
                if (isDestroyed()) return;
                setBusy(false);
                showResult(getString(R.string.err_no_internet));
            }

            @Override
            public void onError(String message) {
                if (isDestroyed()) return;
                setBusy(false);
                showResult(getString(R.string.err_password_change_failed, message));
            }
        };

        if (resetMode) {
            int r = resetCode.check(code, System.currentTimeMillis());
            if (r == ResetCode.EXPIRED) {
                b.tilCode.setError(getString(R.string.err_code_expired));
                return;
            }
            if (r == ResetCode.WRONG) {
                b.tilCode.setError(getString(R.string.err_code_wrong, resetCode.triesLeft()));
                return;
            }
            setBusy(true);
            PasswordChanger.resetLocal(this, next, done);
        } else {
            setBusy(true);
            PasswordChanger.change(this, current, next, done);
        }
    }

    // ---- forgot password ---------------------------------------------------------------------------

    private void forgot() {
        final String email = Session.email(this);
        if (email == null) return;
        if (Session.canSyncOnline(this)) {
            // online account: Firebase e-mails a link that sets a new password
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.forgot_confirm_title)
                    .setMessage(getString(R.string.forgot_confirm_message, email))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.forgot_send, (d, w) -> sendLink(email))
                    .show();
        } else if (!PasswordChangeNotifier.isConfigured()) {
            showResult(getString(R.string.forgot_no_email));
        } else {
            // account on this phone: a one-time code is e-mailed, then entered here with the new password
            setBusy(true);
            final String code = resetCode.issue(System.currentTimeMillis());
            final String name = Session.displayName(this);
            AppExecutors.io().execute(() -> {
                final boolean sent = PasswordChangeNotifier.sendCode(email, name, code);
                AppExecutors.main().post(() -> {
                    if (isDestroyed()) return;
                    setBusy(false);
                    if (sent) {
                        setResetMode(true);
                        showResult(getString(R.string.forgot_code_sent, email));
                    } else {
                        resetCode.clear();
                        showResult(getString(R.string.forgot_code_failed));
                    }
                });
            });
        }
    }

    private void sendLink(String email) {
        setBusy(true);
        PasswordChanger.sendResetLink(this, new PasswordChanger.ResetCallback() {
            @Override
            public void onSent() {
                if (isDestroyed()) return;
                setBusy(false);
                showResult(getString(R.string.forgot_link_sent, email));
            }

            @Override
            public void onNoInternet() {
                if (isDestroyed()) return;
                setBusy(false);
                showResult(getString(R.string.err_no_internet));
            }

            @Override
            public void onError(String message) {
                if (isDestroyed()) return;
                setBusy(false);
                showResult(getString(R.string.forgot_send_failed, message));
            }
        });
    }

    private void setResetMode(boolean on) {
        resetMode = on;
        b.tilCurrent.setVisibility(on ? View.GONE : View.VISIBLE);
        b.tvForgot.setVisibility(on ? View.GONE : View.VISIBLE);
        b.tilCode.setVisibility(on ? View.VISIBLE : View.GONE);
        b.tvBackToChange.setVisibility(on ? View.VISIBLE : View.GONE);
        b.btnChange.setText(on ? R.string.reset_password : R.string.change_password);
        b.tilCode.setError(null);
        b.tilCurrent.setError(null);
        if (!on) {
            resetCode.clear();
            b.etCode.setText("");
        }
    }

    private void showResult(String text) {
        b.tvResult.setText(text);
        b.tvResult.setVisibility(View.VISIBLE);
    }

    private void setBusy(boolean busy) {
        b.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        b.btnChange.setEnabled(!busy);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
