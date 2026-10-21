package com.locus.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.ActivityRegisterBinding;
import com.locus.app.util.AppPrefs;

/** Creates a Firebase Authentication account (e-mail + password) and stores the display name. */
public class RegisterActivity extends AppCompatActivity {
    private ActivityRegisterBinding b;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityRegisterBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.btnRegister.setOnClickListener(v -> register());
    }

    private void register() {
        b.tilName.setError(null);
        b.tilEmail.setError(null);
        b.tilPassword.setError(null);
        b.tilConfirm.setError(null);

        final String name = text(b.etName.getText());
        final String email = text(b.etEmail.getText());
        String pass = text(b.etPassword.getText());
        String confirm = text(b.etConfirm.getText());

        if (name.length() < 2) {
            b.tilName.setError("Enter your name");
            return;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            b.tilEmail.setError("Enter a valid e-mail address");
            return;
        }
        if (pass.length() < 6) {
            b.tilPassword.setError("At least 6 characters");
            return;
        }
        if (!pass.equals(confirm)) {
            b.tilConfirm.setError("Passwords do not match");
            return;
        }
        setBusy(true);
        AppPrefs.setGuest(this, false);
        AppPrefs.saveAccount(this, email, pass, name);
        AppPrefs.setUserSession(this, email, name, "loc_" + Math.abs(email.hashCode()));

        if (Session.isFirebaseReady(this)) {
            FirebaseAuth auth = FirebaseAuth.getInstance();
            auth.createUserWithEmailAndPassword(email, pass)
                    .addOnSuccessListener(result -> {
                        UserProfileChangeRequest profile = new UserProfileChangeRequest.Builder()
                                .setDisplayName(name).build();
                        result.getUser().updateProfile(profile).addOnCompleteListener(t -> openMain());
                    })
                    .addOnFailureListener(e -> {
                        openMain();
                    });
        } else {
            Toast.makeText(this, "Account created successfully", Toast.LENGTH_SHORT).show();
            openMain();
        }
    }

    private void openMain() {
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
