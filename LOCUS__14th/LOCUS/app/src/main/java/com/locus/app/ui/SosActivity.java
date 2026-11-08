package com.locus.app.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.telephony.SmsManager;
import android.view.View;
import android.view.WindowManager;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.locus.app.R;
import com.locus.app.data.firebase.CommunityRepository;
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.ActivitySosBinding;
import com.locus.app.model.CommunityRequest;
import com.locus.app.model.Place;
import com.locus.app.model.RequestType;
import com.locus.app.net.EmergencyPlaces;
import com.locus.app.util.AppExecutors;
import com.locus.app.util.AppPrefs;
import com.locus.app.util.EmergencyContacts;
import com.locus.app.util.ExternalIntents;
import com.locus.app.util.GeoUtils;
import com.locus.app.util.LocationHelper;
import com.locus.app.util.PermissionHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One-tap SOS. A 5 second countdown (Cancel stops everything) then:
 * 1. an SMS with a map link goes to every saved emergency contact,
 * 2. a high-priority SOS post goes to the Community board,
 * 3. the nearest hospital and police station are shown, each with a call button.
 */
public class SosActivity extends AppCompatActivity {
    private static final int COUNTDOWN_SECONDS = 5;

    private ActivitySosBinding b;
    private CountDownTimer timer;
    private boolean fired;
    private boolean cancelled;

    private final ActivityResultLauncher<String[]> permissions =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> { });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivitySosBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        b.btnCancel.setOnClickListener(v -> cancel());
        b.btnDone.setOnClickListener(v -> finish());
        b.btnContacts.setOnClickListener(v -> startActivity(new Intent(this, EmergencyContactsActivity.class)));
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (fired) finish();
                else cancel(); // Back during the countdown = cancel, never "send now"
            }
        });

        // Ask for what the SOS needs while the countdown runs, so it is ready when it reaches zero.
        List<String> need = new ArrayList<>();
        if (!PermissionHelper.hasLocation(this)) need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (!EmergencyContacts.load(this).isEmpty() && !hasSmsPermission()) need.add(Manifest.permission.SEND_SMS);
        if (!need.isEmpty()) permissions.launch(need.toArray(new String[0]));

        timer = new CountDownTimer(COUNTDOWN_SECONDS * 1000L, 250) {
            @Override
            public void onTick(long left) {
                b.tvCountdown.setText(String.valueOf((left + 999) / 1000));
            }

            @Override
            public void onFinish() {
                b.tvCountdown.setText("0");
                fire();
            }
        };
        timer.start();
    }

    private boolean hasSmsPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private void cancel() {
        cancelled = true;
        if (timer != null) timer.cancel();
        finish();
    }

    @Override
    protected void onDestroy() {
        if (timer != null) timer.cancel();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ sending

    private void fire() {
        if (cancelled || fired || isFinishing()) return;
        fired = true;
        b.tvHint.setText(R.string.sos_sending);
        b.btnCancel.setVisibility(View.GONE);
        b.panelResult.setVisibility(View.VISIBLE);
        b.tvStatus.setText(R.string.sos_finding_location);
        b.btnNational.setText(getString(R.string.sos_dial_national, EmergencyContacts.nationalNumber()));
        b.btnNational.setOnClickListener(v -> dial(EmergencyContacts.nationalNumber()));
        // the position first: a short wait, then the last known one (see LocationHelper)
        LocationHelper.getLocation(this, loc -> {
            if (isDestroyed()) return;
            double lat, lon;
            if (loc != null) {
                lat = loc.getLatitude();
                lon = loc.getLongitude();
            } else {
                double[] last = AppPrefs.lastLocation(this);
                lat = last[0];
                lon = last[1];
            }
            send(lat, lon, loc != null || AppPrefs.hasLastLocation(this));
        });
    }

    private void send(final double lat, final double lon, final boolean haveLocation) {
        final String name = Session.displayName(this);
        final String link = haveLocation
                ? String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", lat, lon)
                : getString(R.string.sos_no_location);
        final String text = getString(R.string.sos_sms_text, name, link);
        StringBuilder status = new StringBuilder();

        // 1. SMS
        List<EmergencyContacts.Contact> contacts = EmergencyContacts.load(this);
        if (contacts.isEmpty()) {
            status.append(getString(R.string.sos_no_contacts)).append('\n');
        } else if (hasSmsPermission()) {
            int sent = 0;
            for (EmergencyContacts.Contact c : contacts) {
                try {
                    SmsManager sm = Build.VERSION.SDK_INT >= 31 ? getSystemService(SmsManager.class)
                            : SmsManager.getDefault();
                    sm.sendMultipartTextMessage(c.phone, null, sm.divideMessage(text), null, null);
                    sent++;
                } catch (Exception ignored) {
                    // one bad number must not stop the others
                }
            }
            status.append(getString(R.string.sos_sms_sent, sent, contacts.size())).append('\n');
        } else {
            // no SMS permission: open the messaging app with everything filled in; one tap sends it
            StringBuilder to = new StringBuilder();
            for (EmergencyContacts.Contact c : contacts) to.append(to.length() == 0 ? "" : ";").append(c.phone);
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + to)).putExtra("sms_body", text);
            ExternalIntents.start(this, i);
            status.append(getString(R.string.sos_sms_manual)).append('\n');
        }

        // 2. Community board, high priority
        CommunityRequest r = new CommunityRequest();
        r.id = UUID.randomUUID().toString();
        r.type = RequestType.DISASTER.name();
        r.title = CommunityRequest.SOS_MARK + " - " + getString(R.string.sos_post_title, name);
        if (r.title.length() > 110) r.title = r.title.substring(0, 110);
        r.description = text;
        r.contact = "";
        r.lat = lat;
        r.lon = lon;
        r.hasLocation = haveLocation;
        r.ownerUid = Session.uid(this);
        r.ownerName = name;
        r.createdAt = System.currentTimeMillis();
        final String smsStatus = status.toString();
        b.tvStatus.setText(smsStatus + getString(R.string.sos_posting));
        CommunityRepository.add(this, r, outcome -> {
            if (isDestroyed()) return;
            b.tvStatus.setText(smsStatus + getString(outcome == CommunityRepository.SHARED ? R.string.sos_posted
                    : outcome == CommunityRepository.REFUSED ? R.string.sos_post_refused
                    : outcome == CommunityRepository.LOCAL_ONLY ? R.string.sos_post_local : R.string.sos_post_queued));
        });
        b.tvHint.setText(R.string.sos_sent_title);

        // 3. nearest hospital and police station
        b.tvHospital.setText(R.string.sos_searching);
        b.tvPolice.setText(R.string.sos_searching);
        if (!haveLocation) {
            b.tvHospital.setText(R.string.sos_no_location);
            b.tvPolice.setText("");
            return;
        }
        AppExecutors.io().execute(() -> {
            final Place hospital = EmergencyPlaces.nearest("hospital", lat, lon);
            final Place police = EmergencyPlaces.nearest("police", lat, lon);
            AppExecutors.main().post(() -> {
                if (isDestroyed()) return;
                show(hospital, b.tvHospital, b.btnCallHospital, b.btnGoHospital, R.string.sos_hospital);
                show(police, b.tvPolice, b.btnCallPolice, b.btnGoPolice, R.string.sos_police);
            });
        });
    }

    private void show(final Place p, android.widget.TextView tv, com.google.android.material.button.MaterialButton call,
                      com.google.android.material.button.MaterialButton go, int kindRes) {
        if (p == null) {
            tv.setText(getString(R.string.sos_none_found, getString(kindRes)));
            return;
        }
        tv.setText(getString(R.string.sos_place_line, getString(kindRes), p.name,
                GeoUtils.distanceText(this, p.distanceMeters)));
        go.setVisibility(View.VISIBLE);
        go.setOnClickListener(v -> startActivity(NavigationActivity.intentTo(this, p.lat, p.lon, p.name)));
        if (p.phone != null && !p.phone.trim().isEmpty()) {
            call.setVisibility(View.VISIBLE);
            call.setOnClickListener(v -> dial(p.phone));
        }
    }

    /** Opens the dialler with the number filled in (no call permission needed; one tap to call). */
    private void dial(String number) {
        ExternalIntents.start(this, new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))));
    }
}
