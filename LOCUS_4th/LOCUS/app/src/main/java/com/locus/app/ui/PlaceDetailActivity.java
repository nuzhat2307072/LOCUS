package com.locus.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.locus.app.R;
import com.locus.app.data.provider.FavoritesContract;
import com.locus.app.databinding.ActivityPlaceDetailBinding;
import com.locus.app.model.Place;
import com.locus.app.util.ExternalIntents;
import com.locus.app.util.GeoUtils;
import com.locus.app.util.Labels;

import java.util.Locale;

/** Place details. Shows how data arrives through an Intent extra and how implicit intents launch other apps. */
public class PlaceDetailActivity extends AppCompatActivity {
    public static final String EXTRA_PLACE = "place";

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityPlaceDetailBinding b = ActivityPlaceDetailBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        final Place p = (Place) getIntent().getSerializableExtra(EXTRA_PLACE);
        if (p == null) {
            finish();
            return;
        }

        b.tvName.setText(p.name);
        b.tvCategory.setText(Labels.category(this, p.category));
        b.tvDistance.setText(getString(R.string.detail_distance, GeoUtils.distanceText(this, p.distanceMeters)));
        b.tvTimes.setText(Labels.travelTimes(this, p.distanceMeters));
        b.tvPhone.setText(getString(R.string.detail_phone, orDash(p.phone)));
        b.tvAddress.setText(getString(R.string.detail_address, orDash(p.address)));
        b.tvHours.setText(getString(R.string.detail_hours, orDash(p.openingHours)));

        b.btnNavigate.setOnClickListener(v ->
                startActivity(NavigationActivity.intentTo(this, p.lat, p.lon, p.name)));
        b.btnSave.setOnClickListener(v -> FavoritesContract.save(this, p,
                () -> Toast.makeText(this, R.string.saved_toast, Toast.LENGTH_SHORT).show()));

        // Implicit intents: the system picks the app (dialer, maps, share sheet)
        b.btnCall.setOnClickListener(v -> {
            if (p.phone == null || p.phone.isEmpty()) {
                Toast.makeText(this, R.string.no_phone, Toast.LENGTH_SHORT).show();
            } else {
                ExternalIntents.start(this, new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + p.phone)));
            }
        });
        b.btnMaps.setOnClickListener(v -> ExternalIntents.openMaps(this, p.lat, p.lon, p.name));
        b.btnShare.setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, String.format(Locale.US,
                    "%s - https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=18/%.6f/%.6f",
                    p.name, p.lat, p.lon, p.lat, p.lon));
            ExternalIntents.start(this, Intent.createChooser(send, getString(R.string.share_place)));
        });
    }

    private static String orDash(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
