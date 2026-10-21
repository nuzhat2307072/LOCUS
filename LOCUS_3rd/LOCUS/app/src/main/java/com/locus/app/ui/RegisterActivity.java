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
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.ActivityRegisterBinding;
import com.locus.app.security.LocalAccounts;
import com.locus.app.security.PasswordRules;
import com.locus.app.util.AppExecutors;
import com.locus.app.util.AppPrefs;

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
                    b.tvStrength.setText("Strength: " + PasswordRules.label(strength));
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
            b.tilName.setError("Enter your name");
            return;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            b.tilEmail.setError("Enter a valid e-mail address");
            return;
        }
        String problem = PasswordRules.problem(pass, email);
        if (problem != null) {
            b.tilPassword.setError(problem);
            return;
        }
        if (!pass.equals(confirm)) {
            b.tilConfirm.setError("Passwords do not match");
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
                        Toast.makeText(this, "Account created. We sent a verification e-mail to " + email,
                                Toast.LENGTH_LONG).show();
                        finishRegistration();
                    });
                })
                .addOnFailureListener(e -> {
                    setBusy(false);
                    if (e instanceof FirebaseAuthUserCollisionException) {
                        b.tilEmail.setError("An account with this e-mail already exists");
                    } else if (e instanceof FirebaseAuthWeakPasswordException) {
                        b.tilPassword.setError("Password is too weak");
                    } else if (e instanceof FirebaseNetworkException) {
                        Toast.makeText(this, "No internet connection", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Registration failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
                    b.tilEmail.setError("An account with this e-mail already exists on this phone");
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
