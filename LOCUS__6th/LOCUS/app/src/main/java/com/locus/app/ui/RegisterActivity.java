package com.locus.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Patterns;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.locus.app.R;
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.ActivityRegisterBinding;
import com.locus.app.security.LocalAccounts;
import com.locus.app.security.PasswordRules;
import com.locus.app.util.AppExecutors;
import com.locus.app.util.AppPrefs;
import com.locus.app.util.Labels;

/**
 * Creates an account: a Firebase Authentication account when Firebase is configured (plus a
 * verification e-mail), otherwise an account stored on this phone with a salted PBKDF2 hash.
 * The password must pass PasswordRules, and a strength bar gives live feedback.
 */
public class RegisterActivity extends AppCompatActivity {
    private ActivityRegisterBinding b;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityRegisterBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.btnRegister.setOnClickListener(v -> register());
        b.etPassword.addTextChangedListener(new TextWatcher() {
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
                    b.tvStrength.setText(com.locus.app.R.string.password_rules);
                } else {
                    int strength = PasswordRules.strength(p);
                    b.strengthBar.setProgressCompat(strength, true);
                    b.tvStrength.setText(Labels.strengthText(RegisterActivity.this, strength));
                }
            }
        });
    }

    private void register() {
        b.tilName.setError(null);
        b.tilEmail.setError(null);
        b.tilPassword.setError(null);
        b.tilConfirm.setError(null);

        final String name = text(b.etName.getText());
        final String email = text(b.etEmail.getText());
        final String pass = b.etPassword.getText() == null ? "" : b.etPassword.getText().toString();
        final String confirm = b.etConfirm.getText() == null ? "" : b.etConfirm.getText().toString();

        if (name.length() < 2) {
            b.tilName.setError(getString(R.string.err_enter_name));
            return;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            b.tilEmail.setError(getString(R.string.err_valid_email));
            return;
        }
        int problem = PasswordRules.check(pass, email);
        if (problem != PasswordRules.OK) {
            b.tilPassword.setError(Labels.passwordProblem(this, problem));
            return;
        }
        if (!pass.equals(confirm)) {
            b.tilConfirm.setError(getString(R.string.err_passwords_mismatch));
            return;
        }

        setBusy(true);
        if (Session.isFirebaseReady(this)) {
            registerWithFirebase(name, email, pass);
        } else {
            registerLocally(name, email, pass);
        }
    }

    private void registerWithFirebase(final String name, final String email, final String pass) {
        FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, pass)
                .addOnSuccessListener(result -> {
                    result.getUser().sendEmailVerification(); // best effort
                    UserProfileChangeRequest profile = new UserProfileChangeRequest.Builder()
                            .setDisplayName(name).build();
                    result.getUser().updateProfile(profile).addOnCompleteListener(t -> {
                        AppExecutors.io().execute(() -> LocalAccounts.save(this, name, email, pass));
                        Toast.makeText(this, getString(R.string.msg_account_created, email),
                                Toast.LENGTH_LONG).show();
                        finishRegistration();
                    });
                })
                .addOnFailureListener(e -> {
                    setBusy(false);
                    if (e instanceof FirebaseAuthUserCollisionException) {
                        b.tilEmail.setError(getString(R.string.err_email_exists));
                    } else if (e instanceof FirebaseAuthWeakPasswordException) {
                        b.tilPassword.setError(getString(R.string.err_weak_password));
                    } else if (e instanceof FirebaseNetworkException) {
                        Toast.makeText(this, R.string.err_no_internet, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, getString(R.string.err_registration_failed, String.valueOf(e.getMessage())), Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void registerLocally(final String name, final String email, final String pass) {
        AppExecutors.io().execute(() -> {
            final boolean exists = LocalAccounts.exists(this, email);
            if (!exists) {
                LocalAccounts.save(this, name, email, pass);
                LocalAccounts.setCurrent(this, email);
            }
            AppExecutors.main().post(() -> {
                if (isDestroyed()) return;
                if (exists) {
                    setBusy(false);
                    b.tilEmail.setError(getString(R.string.err_email_exists_local));
                } else {
                    finishRegistration();
                }
            });
        });
    }

    private void finishRegistration() {
        AppPrefs.setFailedLogins(this, 0);
        AppPrefs.setLockedUntil(this, 0);
        Session.unlock();
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void setBusy(boolean busy) {
        b.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        b.btnRegister.setEnabled(!busy);
    }

    private static String text(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }
}
