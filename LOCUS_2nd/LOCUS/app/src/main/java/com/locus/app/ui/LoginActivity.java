package com.locus.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.ActivityLoginBinding;
import com.locus.app.util.AppPrefs;

/** Firebase Authentication sign-in with e-mail/password, plus an offline guest mode. */
public class LoginActivity extends AppCompatActivity {
    private ActivityLoginBinding b;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        final boolean firebaseReady = Session.isFirebaseReady(this);
        b.tvFirebaseWarning.setVisibility(firebaseReady ? View.GONE : View.VISIBLE);

        b.btnLogin.setOnClickListener(v -> login());
        b.btnGuest.setOnClickListener(v -> {
            AppPrefs.setGuest(this, true);
            openMain();
        });
        b.tvRegister.setOnClickListener(v -> startActivity(new Intent(this, RegisterActivity.class)));
        b.tvForgot.setOnClickListener(v -> resetPassword());
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Session.isLoggedIn(this)) openMain(); // e.g. just registered
    }

    private void login() {
        b.tilEmail.setError(null);
        b.tilPassword.setError(null);
        String email = text(b.etEmail.getText());
        String pass = text(b.etPassword.getText());
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            b.tilEmail.setError("Enter a valid e-mail address");
            return;
        }
        if (pass.length() < 6) {
            b.tilPassword.setError("Password must be at least 6 characters");
            return;
        }
        if (!Session.isFirebaseReady(this)) {
            Toast.makeText(this, "Firebase is not configured. Use guest mode for now.", Toast.LENGTH_LONG).show();
            return;
        }
        setBusy(true);
        AppPrefs.setGuest(this, false);
        FirebaseAuth.getInstance().signInWithEmailAndPassword(email, pass)
                .addOnSuccessListener(r -> openMain())
                .addOnFailureListener(e -> {
                    setBusy(false);
                    if (e instanceof FirebaseAuthInvalidUserException) {
                        b.tilEmail.setError("No account with this e-mail");
                    } else if (e instanceof FirebaseAuthInvalidCredentialsException) {
                        b.tilPassword.setError("Wrong e-mail or password");
                    } else if (e instanceof FirebaseNetworkException) {
                        Toast.makeText(this, "No internet connection", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Sign-in failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void resetPassword() {
        String email = text(b.etEmail.getText());
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            b.tilEmail.setError("Enter your e-mail first");
            return;
        }
        if (!Session.isFirebaseReady(this)) return;
        FirebaseAuth.getInstance().sendPasswordResetEmail(email)
                .addOnSuccessListener(v -> Toast.makeText(this, "Reset link sent to " + email, Toast.LENGTH_LONG).show())
                .addOnFailureListener(e -> Toast.makeText(this, "Could not send: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    private void openMain() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void setBusy(boolean busy) {
        b.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        b.btnLogin.setEnabled(!busy);
        b.btnGuest.setEnabled(!busy);
    }

    private static String text(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }
}
