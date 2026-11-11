package com.locus.app.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.locus.app.R;
import com.locus.app.service.AlertsService;
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
        b.swVoice.setChecked(AppPrefs.voiceGuidance(this));
        b.swVoice.setOnCheckedChangeListener((v, on) -> AppPrefs.setVoiceGuidance(this, on));
        b.swShake.setChecked(AppPrefs.sosShake(this));
        b.swShake.setOnCheckedChangeListener((v, on) -> {
            AppPrefs.setSosShake(this, on);
            if (on) com.google.android.material.snackbar.Snackbar.make(b.getRoot(), R.string.shake_enabled_note,
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show();
        });
        b.swLiveAlerts.setChecked(AppPrefs.liveAlerts(this));
        b.swLiveAlerts.setOnCheckedChangeListener((v, on) -> {
            AppPrefs.setLiveAlerts(this, on);
            if (on) {
                AlertsService.startIfWanted(this);
                com.google.android.material.snackbar.Snackbar.make(b.getRoot(), R.string.alerts_enabled_note,
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show();
            } else {
                AlertsService.stop(this);
            }
        });
        setupNotifications();
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

    /** Switches for chat / hazard / Community notifications and the quiet hours. */
    private void setupNotifications() {
        b.swNotifyChat.setChecked(AppPrefs.notifyOn(this, com.locus.app.model.NotifyRules.CHAT));
        b.swNotifyHazard.setChecked(AppPrefs.notifyOn(this, com.locus.app.model.NotifyRules.HAZARD));
        b.swNotifyCommunity.setChecked(AppPrefs.notifyOn(this, com.locus.app.model.NotifyRules.COMMUNITY));
        b.swNotifyChat.setOnCheckedChangeListener((v, on) ->
                AppPrefs.setNotifyOn(this, com.locus.app.model.NotifyRules.CHAT, on));
        b.swNotifyHazard.setOnCheckedChangeListener((v, on) ->
                AppPrefs.setNotifyOn(this, com.locus.app.model.NotifyRules.HAZARD, on));
        b.swNotifyCommunity.setOnCheckedChangeListener((v, on) ->
                AppPrefs.setNotifyOn(this, com.locus.app.model.NotifyRules.COMMUNITY, on));
        b.swQuiet.setChecked(AppPrefs.quietEnabled(this));
        b.swQuiet.setOnCheckedChangeListener((v, on) -> {
            AppPrefs.setQuietEnabled(this, on);
            showQuietHours();
        });
        b.btnQuietFrom.setOnClickListener(v -> pickHour(true));
        b.btnQuietTo.setOnClickListener(v -> pickHour(false));
        showQuietHours();
    }

    private void showQuietHours() {
        b.btnQuietFrom.setText(getString(R.string.notify_quiet_from, String.format(java.util.Locale.getDefault(), "%02d:00", AppPrefs.quietFrom(this))));
        b.btnQuietTo.setText(getString(R.string.notify_quiet_to, String.format(java.util.Locale.getDefault(), "%02d:00", AppPrefs.quietTo(this))));
        boolean on = AppPrefs.quietEnabled(this);
        b.btnQuietFrom.setEnabled(on);
        b.btnQuietTo.setEnabled(on);
    }

    private void pickHour(final boolean from) {
        int current = from ? AppPrefs.quietFrom(this) : AppPrefs.quietTo(this);
        new android.app.TimePickerDialog(this, (view, hour, minute) -> {
            if (from) AppPrefs.setQuietHours(this, hour, AppPrefs.quietTo(this));
            else AppPrefs.setQuietHours(this, AppPrefs.quietFrom(this), hour);
            showQuietHours();
        }, current, 0, true).show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
