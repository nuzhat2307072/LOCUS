package com.locus.app.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.locus.app.R;
import com.locus.app.data.firebase.PasswordChanger;
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.ActivityChangePasswordBinding;
import com.locus.app.security.PasswordRules;
import com.locus.app.util.Labels;

/**
 * Change password in two steps on one screen: first the CURRENT password (proves it is really the owner),
 * then the NEW password twice. When it works an e-mail tells the owner that the password changed.
 */
public class ChangePasswordActivity extends AppCompatActivity {
    private ActivityChangePasswordBinding b;

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

        if (current.isEmpty()) {
            b.tilCurrent.setError(getString(R.string.err_enter_current_password));
            return;
        }
        int problem = PasswordRules.check(next, Session.email(this));
        if (problem != PasswordRules.OK) {
            b.tilNew.setError(Labels.passwordProblem(this, problem));
            return;
        }
        if (next.equals(current)) {
            b.tilNew.setError(getString(R.string.err_new_same_as_current));
            return;
        }
        if (!next.equals(confirm)) {
            b.tilConfirm.setError(getString(R.string.err_passwords_mismatch));
            return;
        }

        setBusy(true);
        PasswordChanger.change(this, current, next, new PasswordChanger.Callback() {
            @Override
            public void onSuccess(boolean emailSent, boolean emailConfigured) {
                if (isDestroyed()) return;
                setBusy(false);
                b.etCurrent.setText("");
                b.etNew.setText("");
                b.etConfirm.setText("");
                int msg = emailSent ? R.string.password_changed_email_sent
                        : emailConfigured ? R.string.password_changed_email_failed
                        : R.string.password_changed_no_email;
                showResult(getString(msg, Session.email(ChangePasswordActivity.this)));
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
        });
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
