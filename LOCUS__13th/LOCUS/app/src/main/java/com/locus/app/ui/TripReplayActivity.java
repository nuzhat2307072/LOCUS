package com.locus.app.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.SeekBar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.locus.app.R;
import com.locus.app.databinding.ActivityTripReplayBinding;
import com.locus.app.model.Trip;
import com.locus.app.util.GeoUtils;
import com.locus.app.util.ThemeHelper;

import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Replays a logged trip on the map: the route drawn, a marker moving along it, play / restart / seek. */
public class TripReplayActivity extends AppCompatActivity {
    public static final String EXTRA_TRIP = "trip";
    private static final long STEP_MS = 60;

    private ActivityTripReplayBinding b;
    private Trip trip;
    private final List<GeoPoint> pts = new ArrayList<>();
    private double[] cumulative;
    private Marker mover;
    private Polyline done;
    private int index;
    private boolean playing;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stepper = new Runnable() {
        @Override
        public void run() {
            if (!playing) return;
            if (index >= pts.size() - 1) {
                setPlaying(false);
                return;
            }
            // about 8 seconds for the whole route, whatever its length
            int stride = Math.max(1, pts.size() / 130);
            show(Math.min(pts.size() - 1, index + stride));
            handler.postDelayed(this, STEP_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityTripReplayBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        trip = (Trip) getIntent().getSerializableExtra(EXTRA_TRIP);
        if (trip == null) {
            finish();
            return;
        }
        for (double[] p : Trip.decodePath(trip.path)) pts.add(new GeoPoint(p[0], p[1]));
        if (pts.size() < 2) {
            finish();
            return;
        }
        cumulative = new double[pts.size()];
        for (int i = 1; i < pts.size(); i++) {
            cumulative[i] = cumulative[i - 1] + GeoUtils.distance(pts.get(i - 1).getLatitude(), pts.get(i - 1).getLongitude(),
                    pts.get(i).getLatitude(), pts.get(i).getLongitude());
        }

        b.replayMap.setTileSource(TileSourceFactory.MAPNIK);
        b.replayMap.setMultiTouchControls(true);
        b.replayMap.setTilesScaledToDpi(true);

        Polyline full = new Polyline();
        full.setPoints(pts);
        full.getOutlinePaint().setColor(0x663F7F7C);
        full.getOutlinePaint().setStrokeWidth(10f);
        b.replayMap.getOverlays().add(full);
        done = new Polyline();
        done.getOutlinePaint().setColor(ContextCompat.getColor(this, R.color.locus_primary));
        done.getOutlinePaint().setStrokeWidth(12f);
        b.replayMap.getOverlays().add(done);

        Marker s = new Marker(b.replayMap);
        s.setPosition(pts.get(0));
        s.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_pin_start));
        s.setTitle(trip.startName);
        b.replayMap.getOverlays().add(s);
        Marker e = new Marker(b.replayMap);
        e.setPosition(pts.get(pts.size() - 1));
        e.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_pin_dest));
        e.setTitle(trip.destName);
        b.replayMap.getOverlays().add(e);
        mover = new Marker(b.replayMap);
        mover.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_pin_me));
        mover.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        b.replayMap.getOverlays().add(mover);

        b.replayMap.post(() -> b.replayMap.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.3f), false));

        b.seekReplay.setMax(pts.size() - 1);
        b.seekReplay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) show(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                setPlaying(false);
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        b.btnPlay.setOnClickListener(v -> {
            if (!playing && index >= pts.size() - 1) show(0);
            setPlaying(!playing);
        });
        b.btnRestart.setOnClickListener(v -> {
            show(0);
            setPlaying(true);
        });
        show(0);
    }

    private void setPlaying(boolean on) {
        playing = on;
        b.btnPlay.setText(on ? R.string.trips_pause : R.string.trips_play);
        handler.removeCallbacks(stepper);
        if (on) handler.post(stepper);
    }

    private void show(int i) {
        index = Math.max(0, Math.min(pts.size() - 1, i));
        mover.setPosition(pts.get(index));
        done.setPoints(new ArrayList<>(pts.subList(0, index + 1)));
        b.seekReplay.setProgress(index);
        b.tvReplayInfo.setText(getString(R.string.trips_replay_info, trip.destName,
                GeoUtils.distanceText(this, cumulative[index]), GeoUtils.distanceText(this, cumulative[cumulative.length - 1]),
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(trip.startedAt))));
        b.replayMap.invalidate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        b.replayMap.onResume();
        ThemeHelper.applyToMap(b.replayMap);
    }

    @Override
    protected void onPause() {
        setPlaying(false);
        b.replayMap.onPause();
        super.onPause();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
