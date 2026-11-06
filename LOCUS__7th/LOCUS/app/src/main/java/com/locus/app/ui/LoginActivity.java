package com.locus.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.locus.app.R;
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.ActivityLoginBinding;
import com.locus.app.security.LocalAccounts;
import com.locus.app.security.LockoutPolicy;
import com.locus.app.util.AppExecutors;
import com.locus.app.util.AppPrefs;


/**
 * The gate of the app. It is shown every time LOCUS is opened:
 *  - first use: sign in (or create an account),
 *  - afterwards: "Welcome back" - the account is remembered, so only the password (or fingerprint /
 *    screen lock) is needed.
 * Wrong passwords are counted; after 5 the screen locks for a while (see LockoutPolicy).
 */
public class LoginActivity extends AppCompatActivity {
    private static final int AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL;

    private ActivityLoginBinding b;
    private String rememberedEmail; // null = nobody signed in on this phone yet
    private CountDownTimer lockTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        b.tvFirebaseWarning.setVisibility(Session.isFirebaseReady(this) ? View.GONE : View.VISIBLE);
        rememberedEmail = Session.email(this);
        final boolean returning = rememberedEmail != null;
        final boolean canBiometric = returning && biometricAvailable();

        if (returning) {
            b.tvTitle.setText(getString(com.locus.app.R.string.welcome_back_user, Session.displayName(this)));
            b.tvSubtitle.setVisibility(View.VISIBLE);
            b.etEmail.setText(rememberedEmail);
            b.tilEmail.setEnabled(false);
            b.tvRegister.setVisibility(View.GONE);
            b.tvSwitch.setVisibility(View.VISIBLE);
            b.btnBiometric.setVisibility(canBiometric ? View.VISIBLE : View.GONE);
        }

        b.btnLogin.setOnClickListener(v -> login());
        b.btnBiometric.setOnClickListener(v -> showBiometric());
        b.tvRegister.setOnClickListener(v -> startActivity(new Intent(this, RegisterActivity.class)));
        b.tvLanguage.setOnClickListener(v -> com.locus.app.util.LanguageHelper.showChooser(this));
        b.tvForgot.setOnClickListener(v -> resetPassword());
        b.tvSwitch.setOnClickListener(v -> {
            Session.signOut(this); // forget this account on the phone, then show the normal sign-in
            recreate();
        });

        updateLockUi();
        // offer the quick unlock straight away; the password stays available underneath
        if (canBiometric && savedInstanceState == null && remainingLockMs() <= 0) showBiometric();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Session.isUnlocked() && Session.isRemembered(this)) openMain();
    }

    @Override
    protected void onDestroy() {
        if (lockTimer != null) lockTimer.cancel();
        super.onDestroy();
    }

    // ---- password sign-in ------------------------------------------------------------------------

    private void login() {
        b.tilEmail.setError(null);
        b.tilPassword.setError(null);
        if (remainingLockMs() > 0) {
            updateLockUi();
            return;
        }
        final String email = rememberedEmail != null ? rememberedEmail : text(b.etEmail.getText());
        final String pass = b.etPassword.getText() == null ? "" : b.etPassword.getText().toString();
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            b.tilEmail.setError(getString(R.string.err_valid_email));
            return;
        }
        if (pass.isEmpty()) {
            b.tilPassword.setError(getString(R.string.err_enter_password));
            return;
        }
        setBusy(true);

        if (Session.isFirebaseReady(this)) {
            FirebaseAuth.getInstance().signInWithEmailAndPassword(email, pass)
                    .addOnSuccessListener(r -> onVerified(email, pass, r.getUser()))
                    .addOnFailureListener(e -> {
                        if (e instanceof FirebaseNetworkException) {
                            offlineCheck(email, pass);
                        } else if (e instanceof FirebaseTooManyRequestsException) {
                            setBusy(false);
                            Toast.makeText(this, R.string.err_too_many_attempts,
                                    Toast.LENGTH_LONG).show();
                        } else {
                            onWrongPassword(); // wrong password and unknown e-mail look the same on purpose
                        }
                    });
        } else {
            AppExecutors.io().execute(() -> {
                final boolean ok = LocalAccounts.exists(this, email) && LocalAccounts.verify(this, email, pass);
                AppExecutors.main().post(() -> {
                    if (isDestroyed()) return;
                    if (ok) onVerified(email, pass, null);
                    else onWrongPassword();
                });
            });
        }
    }

    /** No internet: a returning user can still unlock with the password checked on this phone. */
    private void offlineCheck(final String email, final String pass) {
        if (rememberedEmail == null || !LocalAccounts.exists(this, email)) {
            setBusy(false);
            Toast.makeText(this, R.string.err_no_internet, Toast.LENGTH_LONG).show();
            return;
        }
        AppExecutors.io().execute(() -> {
            final boolean ok = LocalAccounts.verify(this, email, pass);
            AppExecutors.main().post(() -> {
                if (isDestroyed()) return;
                if (ok) onVerified(email, pass, null);
                else onWrongPassword();
            });
        });
    }

    private void onVerified(final String email, final String pass, FirebaseUser fbUser) {
        final String name = fbUser != null && fbUser.getDisplayName() != null ? fbUser.getDisplayName() : null;
        if (fbUser != null) {
            // remember a salted hash so this person can also unlock the app offline next time
            AppExecutors.io().execute(() -> LocalAccounts.save(this, name == null ? "" : name, email, pass));
        } else if (!Session.isFirebaseReady(this)) {
            LocalAccounts.setCurrent(this, email);
        }
        unlockAndOpen();
    }

    private void onWrongPassword() {
        setBusy(false);
        int failures = AppPrefs.failedLogins(this) + 1;
        AppPrefs.setFailedLogins(this, failures);
        b.etPassword.setText("");
        int secs = LockoutPolicy.lockSeconds(failures);
        if (secs > 0) {
            AppPrefs.setLockedUntil(this, System.currentTimeMillis() + secs * 1000L);
            updateLockUi();
        } else {
            int left = LockoutPolicy.attemptsLeft(failures);
            b.tilPassword.setError(getResources().getQuantityString(R.plurals.err_wrong_login, left, left));
        }
    }

    private void unlockAndOpen() {
        AppPrefs.setFailedLogins(this, 0);
        AppPrefs.setLockedUntil(this, 0);
        Session.unlock();
        openMain();
    }

    // ---- lock-out countdown --------------------------------------------------------------------

    private long remainingLockMs() {
        return AppPrefs.lockedUntil(this) - System.currentTimeMillis();
    }

    private void updateLockUi() {
        if (lockTimer != null) lockTimer.cancel();
        long remaining = remainingLockMs();
        if (remaining <= 0) {
            b.tvLockMessage.setVisibility(View.GONE);
            b.btnLogin.setEnabled(true);
            return;
        }
        b.btnLogin.setEnabled(false);
        b.tvLockMessage.setVisibility(View.VISIBLE);
        lockTimer = new CountDownTimer(remaining, 1000) {
            @Override
            public void onTick(long ms) {
                long s = (ms + 999) / 1000;
                b.tvLockMessage.setText(getString(R.string.lock_message, s / 60, s % 60));
            }

            @Override
            public void onFinish() {
                b.tvLockMessage.setVisibility(View.GONE);
                b.btnLogin.setEnabled(true);
            }
        }.start();
    }

    // ---- fingerprint / face / screen lock ------------------------------------------------------

    private boolean biometricAvailable() {
        return BiometricManager.from(this).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS;
    }

    private void showBiometric() {
        if (rememberedEmail == null || !biometricAvailable()) return;
        BiometricPrompt prompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        unlockAndOpen();
                    }
                });
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(com.locus.app.R.string.biometric_title))
                .setSubtitle(getString(com.locus.app.R.string.biometric_subtitle))
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build();
        prompt.authenticate(info);
    }

    // ---- misc ----------------------------------------------------------------------------------

    private void resetPassword() {
        String email = rememberedEmail != null ? rememberedEmail : text(b.etEmail.getText());
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            b.tilEmail.setError(getString(R.string.err_enter_email_first));
            return;
        }
        if (!Session.isFirebaseReady(this)) {
            Toast.makeText(this, R.string.msg_reset_needs_firebase, Toast.LENGTH_LONG).show();
            return;
        }
        FirebaseAuth.getInstance().sendPasswordResetEmail(email)
                .addOnSuccessListener(v -> Toast.makeText(this, getString(R.string.msg_reset_sent, email), Toast.LENGTH_LONG).show())
                .addOnFailureListener(e -> Toast.makeText(this, getString(R.string.msg_reset_failed, String.valueOf(e.getMessage())), Toast.LENGTH_LONG).show());
    }

    private void openMain() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void setBusy(boolean busy) {
        b.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        b.btnLogin.setEnabled(!busy && remainingLockMs() <= 0);
    }

    private static String text(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }
}
