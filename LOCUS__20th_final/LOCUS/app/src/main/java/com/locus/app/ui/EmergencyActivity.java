package com.locus.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.locus.app.R;
import com.locus.app.databinding.ActivityEmergencyBinding;
import com.locus.app.databinding.ItemEmergencyPlaceBinding;
import com.locus.app.model.Place;
import com.locus.app.net.EmergencyPlaces;
import com.locus.app.util.AppExecutors;
import com.locus.app.util.AppPrefs;
import com.locus.app.util.EmergencyContacts;
import com.locus.app.util.ExternalIntents;
import com.locus.app.util.GeoUtils;
import com.locus.app.util.LocationHelper;

/**
 * Emergency help without sending an SOS: national numbers, one-tap call to the saved emergency contacts, and the
 * nearest hospital, police station, fire station and pharmacy with a call and a directions button each.
 */
public class EmergencyActivity extends AppCompatActivity {
    private static final String WOMEN_CHILDREN_HELPLINE = "109";
    private static final String HEALTH_HELPLINE = "16263";

    private ActivityEmergencyBinding b;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityEmergencyBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        // 999 is always first; the phone country's own number (911, 112 ...) is added only when it is different
        b.btnNational.setText(R.string.sos_dial_999);
        b.btnNational.setOnClickListener(v -> dial(EmergencyContacts.BANGLADESH_EMERGENCY));
        final String local = EmergencyContacts.localNumberOrNull();
        b.btnLocal.setVisibility(local == null ? View.GONE : View.VISIBLE);
        if (local != null) {
            b.btnLocal.setText(getString(R.string.sos_dial_local, local));
            b.btnLocal.setOnClickListener(v -> dial(local));
        }
        b.btnWomen.setOnClickListener(v -> dial(WOMEN_CHILDREN_HELPLINE));
        b.btnHealth.setOnClickListener(v -> dial(HEALTH_HELPLINE));
    }

    @Override
    protected void onStart() {
        super.onStart();
        showContacts();
        findNearby();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    private void showContacts() {
        b.listContacts.removeAllViews();
        java.util.List<EmergencyContacts.Contact> contacts = EmergencyContacts.load(this);
        if (contacts.isEmpty()) {
            android.widget.TextView tv = new android.widget.TextView(this);
            tv.setText(R.string.sos_no_contacts);
            tv.setPadding(0, 8, 0, 0);
            b.listContacts.addView(tv);
            return;
        }
        for (EmergencyContacts.Contact c : contacts) {
            ItemEmergencyPlaceBinding row = ItemEmergencyPlaceBinding.inflate(LayoutInflater.from(this),
                    b.listContacts, false);
            row.tvKind.setText(R.string.emergency_contact);
            row.tvLine.setText(c.name + " - " + c.phone);
            row.btnCall.setVisibility(View.VISIBLE);
            row.btnCall.setOnClickListener(v -> dial(c.phone));
            b.listContacts.addView(row.getRoot());
        }
    }

    private void findNearby() {
        b.listNearby.removeAllViews();
        b.tvNearbyStatus.setText(R.string.sos_finding_location);
        LocationHelper.getLocation(this, loc -> {
            if (destroyed) return;
            double lat, lon;
            if (loc != null) {
                lat = loc.getLatitude();
                lon = loc.getLongitude();
            } else if (AppPrefs.hasLastLocation(this)) {
                double[] last = AppPrefs.lastLocation(this);
                lat = last[0];
                lon = last[1];
            } else {
                b.tvNearbyStatus.setText(R.string.sos_no_location);
                return;
            }
            b.tvNearbyStatus.setText(R.string.sos_searching);
            final double fLat = lat;
            final double fLon = lon;
            final String[][] kinds = {{"hospital", String.valueOf(R.string.sos_hospital)},
                    {"police", String.valueOf(R.string.sos_police)},
                    {"fire_station", String.valueOf(R.string.emergency_fire)},
                    {"pharmacy", String.valueOf(R.string.emergency_pharmacy)}};
            // every kind is looked up on its own, so the first answer appears without waiting for the others
            final int[] left = {kinds.length};
            for (String[] k : kinds) {
                final String amenity = k[0];
                final int kindRes = Integer.parseInt(k[1]);
                AppExecutors.io().execute(() -> {
                    final Place p = EmergencyPlaces.nearest(amenity, fLat, fLon);
                    AppExecutors.main().post(() -> {
                        if (destroyed) return;
                        addNearby(p, kindRes);
                        if (--left[0] == 0) b.tvNearbyStatus.setText("");
                    });
                });
            }
        });
    }

    private void addNearby(Place p, int kindRes) {
        ItemEmergencyPlaceBinding row = ItemEmergencyPlaceBinding.inflate(LayoutInflater.from(this),
                b.listNearby, false);
        row.tvKind.setText(kindRes);
        if (p == null) {
            row.tvLine.setText(getString(R.string.sos_none_found, getString(kindRes)));
        } else {
            row.tvLine.setText(getString(R.string.emergency_place_line, p.name,
                    GeoUtils.distanceText(this, p.distanceMeters)));
            row.btnGo.setVisibility(View.VISIBLE);
            row.btnGo.setOnClickListener(v ->
                    startActivity(NavigationActivity.intentTo(this, p.lat, p.lon, p.name)));
            if (p.phone != null && !p.phone.trim().isEmpty()) {
                row.btnCall.setVisibility(View.VISIBLE);
                row.btnCall.setOnClickListener(v -> dial(p.phone));
            }
        }
        b.listNearby.addView(row.getRoot());
    }

    /** Opens the dialler with the number filled in (no call permission needed; one tap to call). */
    private void dial(String number) {
        ExternalIntents.start(this, new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))));
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
