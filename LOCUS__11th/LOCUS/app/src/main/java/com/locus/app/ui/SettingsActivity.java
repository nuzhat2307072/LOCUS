package com.locus.app.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.locus.app.R;
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.ActivitySettingsBinding;
import com.locus.app.util.LanguageHelper;

/** Settings (the three-dot button of the dashboard): language, lock the app, change the password. */
public class SettingsActivity extends AppCompatActivity {
    private ActivitySettingsBinding b;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        String email = Session.email(this);
        b.tvAccount.setText(email == null ? "" : getString(R.string.signed_in_as, email));

        b.rowLanguage.setOnClickListener(v -> LanguageHelper.showChooser(this));
        b.rowLock.setOnClickListener(v -> {
            Session.lock(); // the login screen asks for the password or fingerprint again
            Intent i = new Intent(this, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
        });
        b.rowCloud.setOnClickListener(v -> startActivity(new Intent(this, ConnectFirebaseActivity.class)));
        b.rowPassword.setOnClickListener(v -> startActivity(new Intent(this, ChangePasswordActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        b.tvLanguageValue.setText(LanguageHelper.currentName(this));
        String project = com.locus.app.data.firebase.FirebaseConnector.projectId(this);
        b.tvCloudValue.setText(project == null ? getString(R.string.settings_cloud_sub_off)
                : getString(R.string.settings_cloud_sub_on, project));
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
