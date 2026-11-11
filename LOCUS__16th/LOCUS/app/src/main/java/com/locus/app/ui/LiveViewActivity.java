package com.locus.app.ui;

import com.locus.app.data.firebase.Rtdb;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.locus.app.R;
import com.locus.app.data.firebase.LiveShareRepository;
import com.locus.app.databinding.ActivityLiveViewBinding;
import com.locus.app.util.ThemeHelper;

import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Marker;

/** A contact's moving position on the map, with how long ago it was last updated. */
public class LiveViewActivity extends AppCompatActivity {
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_NAME = "name";

    private ActivityLiveViewBinding b;
    private Marker who;
    private Marker dest;
    private boolean centred;
    private String code;
    private String name;
    private long updatedAt;
    private boolean ended;
    private DatabaseReference ref;
    private ValueEventListener listener;
    private double lastLat, lastLon;
    private boolean havePosition;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            showAge();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityLiveViewBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        code = getIntent().getStringExtra(EXTRA_CODE);
        name = getIntent().getStringExtra(EXTRA_NAME);
        b.tvWho.setText(getString(R.string.share_live_title, name));
        b.tvUpdated.setText(R.string.share_waiting_position);
        b.btnBack.setOnClickListener(v -> finish());

        b.liveMap.setTileSource(TileSourceFactory.MAPNIK);
        b.liveMap.setMultiTouchControls(true);
        b.liveMap.setTilesScaledToDpi(true);
        b.liveMap.getController().setZoom(15.0);
        b.liveMap.getController().setCenter(new GeoPoint(com.locus.app.util.AppPrefs.lastLocation(this)[0],
                com.locus.app.util.AppPrefs.lastLocation(this)[1]));
        b.btnGoThere.setOnClickListener(v -> {
            if (havePosition) startActivity(NavigationActivity.intentTo(this, lastLat, lastLon, name));
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        ref = Rtdb.get().getReference(LiveShareRepository.NODE).child(code);
        listener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot s) {
                if (!s.exists()) {
                    showEnded(R.string.share_ended);
                    return;
                }
                Double lat = s.child("lat").getValue(Double.class);
                Double lon = s.child("lon").getValue(Double.class);
                Long up = s.child("updatedAt").getValue(Long.class);
                Long battery = s.child("battery").getValue(Long.class);
                String n = s.child("ownerName").getValue(String.class);
                if (n != null) {
                    name = n;
                    b.tvWho.setText(getString(R.string.share_live_title, n));
                }
                Double dLat = s.child("destLat").getValue(Double.class);
                Double dLon = s.child("destLon").getValue(Double.class);
                if (dLat != null && dLon != null) showDest(new GeoPoint(dLat, dLon), s.child("destName").getValue(String.class));
                if (lat == null || lon == null) return; // no first position yet
                updatedAt = up == null ? System.currentTimeMillis() : up;
                batteryText = battery == null ? "" : getString(R.string.share_battery, battery);
                move(new GeoPoint(lat, lon));
                showAge();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // the owner removed my access (or the rules refuse it)
                showEnded(R.string.share_access_removed);
            }
        };
        ref.addValueEventListener(listener);
        handler.post(tick);
    }

    private String batteryText = "";

    @Override
    protected void onStop() {
        handler.removeCallbacks(tick);
        if (ref != null && listener != null) ref.removeEventListener(listener);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        b.liveMap.onResume();
        ThemeHelper.applyToMap(b.liveMap);
    }

    @Override
    protected void onPause() {
        b.liveMap.onPause();
        super.onPause();
    }

    private void move(GeoPoint p) {
        lastLat = p.getLatitude();
        lastLon = p.getLongitude();
        havePosition = true;
        b.btnGoThere.setVisibility(View.VISIBLE);
        if (who == null) {
            who = new Marker(b.liveMap);
            who.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_pin_me));
            who.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            b.liveMap.getOverlays().add(who);
        }
        who.setTitle(name);
        who.setPosition(p);
        if (!centred) {
            centred = true;
            b.liveMap.getController().setZoom(16.5);
            b.liveMap.getController().setCenter(p);
        } else {
            b.liveMap.getController().animateTo(p);
        }
        b.liveMap.invalidate();
    }

    private void showDest(GeoPoint p, String title) {
        if (dest == null) {
            dest = new Marker(b.liveMap);
            dest.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_pin_dest));
            b.liveMap.getOverlays().add(dest);
        }
        dest.setTitle(title == null || title.isEmpty() ? getString(R.string.destination) : title);
        dest.setPosition(p);
    }

    private void showAge() {
        if (ended || !havePosition) return;
        long sec = Math.max(0, (System.currentTimeMillis() - updatedAt) / 1000);
        String age = sec < 60 ? getString(R.string.share_updated_seconds, sec)
                : getString(R.string.share_updated_minutes, sec / 60);
        b.tvUpdated.setText(batteryText.isEmpty() ? age : age + " \u00B7 " + batteryText);
        b.tvUpdated.setTextColor(ContextCompat.getColor(this, sec > 120 ? R.color.locus_danger : R.color.locus_text_secondary));
    }

    private void showEnded(int message) {
        ended = true;
        b.tvUpdated.setText(message);
        b.tvUpdated.setTextColor(ContextCompat.getColor(this, R.color.locus_danger));
    }
}
