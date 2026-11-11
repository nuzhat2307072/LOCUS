package com.locus.app.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.locus.app.R;
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.ActivitySettingsBinding;
import com.locus.app.util.AppPrefs;
import com.locus.app.util.LanguageHelper;
import com.locus.app.util.ThemeHelper;

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
        b.rowTheme.setOnClickListener(v -> ThemeHelper.showChooser(this));
        b.swDarkMap.setChecked(AppPrefs.autoDarkMap(this));
        b.swDarkMap.setOnCheckedChangeListener((v, on) -> AppPrefs.setAutoDarkMap(this, on));
        b.rowContacts.setOnClickListener(v -> startActivity(new Intent(this, EmergencyContactsActivity.class)));
        b.rowOfflineMaps.setOnClickListener(v -> startActivity(new Intent(this, OfflineMapsActivity.class)));
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
        b.tvThemeValue.setText(ThemeHelper.currentName(this));
        int contacts = com.locus.app.util.EmergencyContacts.load(this).size();
        b.tvContactsValue.setText(getResources().getQuantityString(R.plurals.emergency_contacts_count, contacts, contacts));
        b.tvOfflineValue.setText(R.string.settings_offline_sub);
        b.tvReputationValue.setText(R.string.reputation_loading);
        com.locus.app.data.firebase.HazardRepository.fetchReputation(this,
                com.locus.app.data.firebase.Session.uid(this), points -> {
                    if (!isFinishing()) b.tvReputationValue.setText(getString(R.string.reputation_points, points));
                });
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
