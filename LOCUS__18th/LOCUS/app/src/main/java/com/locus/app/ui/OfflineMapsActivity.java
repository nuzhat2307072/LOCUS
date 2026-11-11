package com.locus.app.ui;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.locus.app.R;
import com.locus.app.data.db.LocusDatabase;
import com.locus.app.databinding.ActivityOfflineMapsBinding;
import com.locus.app.databinding.ItemContactBinding;
import com.locus.app.model.OfflineArea;
import com.locus.app.model.TileMath;
import com.locus.app.util.AppExecutors;
import com.locus.app.util.AppPrefs;
import com.locus.app.util.OfflineMaps;
import com.locus.app.util.ThemeHelper;

import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Polygon;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Offline map download: pick an area (a box on the map or a radius around me), see the size before downloading,
 * watch progress, cancel, and manage the saved areas (show, rename, update, delete).
 */
public class OfflineMapsActivity extends AppCompatActivity {
    private static final int ZOOM_MIN = 10;

    private ActivityOfflineMapsBinding b;
    private boolean radiusMode;
    private Polygon radiusOverlay;
    private List<OfflineArea> areas = new ArrayList<>();
    private OfflineMaps.Job job;
    private long lastTiles;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityOfflineMapsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        b.offlineMap.setTileSource(TileSourceFactory.MAPNIK);
        b.offlineMap.setMultiTouchControls(true);
        b.offlineMap.setTilesScaledToDpi(true);
        double[] me = AppPrefs.lastLocation(this);
        b.offlineMap.getController().setZoom(13.0);
        b.offlineMap.getController().setCenter(new GeoPoint(me[0], me[1]));
        // the map sits inside a ScrollView: it must get the drags, not the page
        b.offlineMap.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN || ev.getAction() == MotionEvent.ACTION_MOVE) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        });
        b.offlineMap.addMapListener(new MapListener() {
            @Override
            public boolean onScroll(ScrollEvent event) {
                updateEstimate();
                return false;
            }

            @Override
            public boolean onZoom(ZoomEvent event) {
                updateEstimate();
                return false;
            }
        });

        b.swWifi.setChecked(OfflineMaps.wifiOnly(this));
        b.swWifi.setOnCheckedChangeListener((v, on) -> OfflineMaps.setWifiOnly(this, on));
        b.modeGroup.setOnCheckedStateChangeListener((g, ids) -> {
            radiusMode = ids.contains(R.id.chipRadius);
            b.selFrame.setVisibility(radiusMode ? View.GONE : View.VISIBLE);
            b.tvBoxHint.setVisibility(radiusMode ? View.GONE : View.VISIBLE);
            b.panelRadius.setVisibility(radiusMode ? View.VISIBLE : View.GONE);
            if (radiusMode) {
                double[] at = AppPrefs.lastLocation(this);
                b.offlineMap.getController().setCenter(new GeoPoint(at[0], at[1]));
                b.offlineMap.getController().setZoom(12.5);
            }
            updateEstimate();
        });
        b.zoomGroup.setOnCheckedStateChangeListener((g, ids) -> updateEstimate());
        b.seekRadius.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                updateEstimate();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        b.btnDownload.setOnClickListener(v -> startDownload(null));
        b.btnCancelDownload.setOnClickListener(v -> {
            if (job != null) job.cancel();
        });

        LocusDatabase.get(this).offlineAreaDao().observeAll().observe(this, list -> {
            areas = list;
            showAreas();
        });
        b.offlineMap.post(this::updateEstimate);
    }

    @Override
    protected void onResume() {
        super.onResume();
        b.offlineMap.onResume();
        ThemeHelper.applyToMap(b.offlineMap);
        showStorage();
    }

    @Override
    protected void onPause() {
        b.offlineMap.onPause();
        super.onPause();
    }

    // ------------------------------------------------------------------ the chosen area

    private int zoomMax() {
        int id = b.zoomGroup.getCheckedChipId();
        return id == R.id.chipZ14 ? 14 : id == R.id.chipZ16 ? 16 : 15;
    }

    /** @return {north, south, east, west} of what the person has chosen. */
    private double[] chosenBox() {
        if (radiusMode) {
            double[] at = AppPrefs.lastLocation(this);
            double[] box = TileMath.boxAround(at[0], at[1], radiusKm());
            return new double[]{box[1], box[0], box[3], box[2]};
        }
        int w = b.offlineMap.getWidth(), h = b.offlineMap.getHeight();
        int m = (int) (28 * getResources().getDisplayMetrics().density);
        org.osmdroid.api.IGeoPoint nw = b.offlineMap.getProjection().fromPixels(m, m);
        org.osmdroid.api.IGeoPoint se = b.offlineMap.getProjection().fromPixels(Math.max(m + 1, w - m), Math.max(m + 1, h - m));
        return new double[]{nw.getLatitude(), se.getLatitude(), se.getLongitude(), nw.getLongitude()};
    }

    private int radiusKm() {
        return b.seekRadius.getProgress() + 1;
    }

    private void updateEstimate() {
        if (b == null || b.offlineMap.getWidth() == 0) return;
        if (radiusMode) {
            b.tvRadius.setText(getString(R.string.offline_radius, radiusKm()));
            double[] at = AppPrefs.lastLocation(this);
            if (radiusOverlay != null) b.offlineMap.getOverlays().remove(radiusOverlay);
            radiusOverlay = new Polygon();
            radiusOverlay.setPoints(Polygon.pointsAsCircle(new GeoPoint(at[0], at[1]), radiusKm() * 1000.0));
            radiusOverlay.getFillPaint().setColor(0x2200695C);
            radiusOverlay.getOutlinePaint().setColor(0xFF00695C);
            b.offlineMap.getOverlays().add(radiusOverlay);
            b.offlineMap.invalidate();
        } else if (radiusOverlay != null) {
            b.offlineMap.getOverlays().remove(radiusOverlay);
            radiusOverlay = null;
        }
        double[] bx = chosenBox();
        lastTiles = TileMath.count(bx[0], bx[1], bx[2], bx[3], ZOOM_MIN, zoomMax());
        boolean tooBig = lastTiles > TileMath.MAX_TILES;
        b.tvEstimate.setText(tooBig
                ? getString(R.string.offline_too_big, lastTiles, TileMath.MAX_TILES)
                : getString(R.string.offline_estimate, lastTiles,
                Formatter.formatShortFileSize(this, TileMath.estimateBytes(lastTiles))));
        b.tvEstimate.setTextColor(getColor(tooBig ? R.color.locus_danger : R.color.locus_text));
        b.btnDownload.setEnabled(!tooBig && job == null);
    }

    private void showStorage() {
        final long used = OfflineMaps.storageUsed();
        final long limit = Math.max(1, OfflineMaps.storageLimit());
        b.tvStorage.setText(getString(R.string.offline_storage, Formatter.formatShortFileSize(this, used),
                Formatter.formatShortFileSize(this, limit)));
        b.pbStorage.setProgress((int) Math.min(1000, used * 1000 / limit));
    }

    // ------------------------------------------------------------------ download

    /** @param existing an area to download again (update), or null for a new one. */
    private void startDownload(final OfflineArea existing) {
        if (job != null) return;
        if (OfflineMaps.wifiOnly(this) && !OfflineMaps.onWifi(this)) {
            Toast.makeText(this, R.string.offline_need_wifi, Toast.LENGTH_LONG).show();
            return;
        }
        final OfflineArea a;
        if (existing != null) {
            a = existing;
        } else {
            double[] bx = chosenBox();
            a = new OfflineArea();
            a.id = UUID.randomUUID().toString();
            String name = b.etName.getText() == null ? "" : b.etName.getText().toString().trim();
            a.name = name.isEmpty() ? getString(R.string.offline_default_name, areas.size() + 1) : name;
            a.north = bx[0];
            a.south = bx[1];
            a.east = bx[2];
            a.west = bx[3];
            a.zoomMin = ZOOM_MIN;
            a.zoomMax = zoomMax();
        }
        final long total = TileMath.count(a.north, a.south, a.east, a.west, a.zoomMin, a.zoomMax);
        if (total > TileMath.MAX_TILES) {
            Toast.makeText(this, R.string.offline_too_big_toast, Toast.LENGTH_LONG).show();
            return;
        }
        a.tiles = (int) total;
        job = new OfflineMaps.Job();
        final OfflineMaps.Job mine = job;
        b.btnDownload.setEnabled(false);
        b.btnCancelDownload.setVisibility(View.VISIBLE);
        b.pbDownload.setVisibility(View.VISIBLE);
        b.tvProgress.setVisibility(View.VISIBLE);
        b.pbDownload.setProgress(0);
        b.tvProgress.setText(R.string.offline_starting);
        AppExecutors.io().execute(() -> {
            long bytes = OfflineMaps.download(a, mine, (done, all) -> runOnUiThread(() -> {
                if (isDestroyed()) return;
                b.pbDownload.setProgress(all == 0 ? 0 : done * 100 / all);
                b.tvProgress.setText(getString(R.string.offline_progress, done, all));
            }));
            if (bytes >= 0) {
                a.bytes = bytes;
                a.updatedAt = System.currentTimeMillis();
                LocusDatabase.get(this).offlineAreaDao().insert(a);
            } else {
                OfflineMaps.remove(a, LocusDatabase.get(this).offlineAreaDao().getAll()); // drop the part downloaded
            }
            final boolean ok = bytes >= 0;
            runOnUiThread(() -> {
                job = null;
                if (isDestroyed()) return;
                b.btnCancelDownload.setVisibility(View.GONE);
                b.pbDownload.setVisibility(View.GONE);
                b.tvProgress.setVisibility(View.GONE);
                Toast.makeText(this, ok ? getString(R.string.offline_done, a.name) : getString(R.string.offline_cancelled),
                        Toast.LENGTH_LONG).show();
                showStorage();
                updateEstimate();
            });
        });
    }

    // ------------------------------------------------------------------ saved areas

    private void showAreas() {
        b.listAreas.removeAllViews();
        b.tvNoAreas.setVisibility(areas.isEmpty() ? View.VISIBLE : View.GONE);
        DateFormat df = DateFormat.getDateInstance(DateFormat.MEDIUM);
        for (final OfflineArea a : areas) {
            ItemContactBinding row = ItemContactBinding.inflate(getLayoutInflater(), b.listAreas, false);
            row.tvName.setText(a.name);
            row.tvAbout.setText(getString(R.string.offline_area_line, Formatter.formatShortFileSize(this, a.bytes),
                    a.tiles, df.format(new Date(a.updatedAt))));
            row.btnPrimary.setVisibility(View.GONE);
            row.btnSecondary.setVisibility(View.GONE);
            row.getRoot().setOnClickListener(v -> manage(a));
            b.listAreas.addView(row.getRoot());
        }
    }

    private void manage(final OfflineArea a) {
        String[] items = {getString(R.string.offline_show), getString(R.string.offline_rename),
                getString(R.string.offline_update), getString(R.string.action_delete)};
        new MaterialAlertDialogBuilder(this).setTitle(a.name).setItems(items, (d, which) -> {
            if (which == 0) {
                b.modeGroup.check(R.id.chipBox);
                b.offlineMap.zoomToBoundingBox(new BoundingBox(a.north, a.east, a.south, a.west), true);
                b.scroll.smoothScrollTo(0, 0);
            } else if (which == 1) {
                rename(a);
            } else if (which == 2) {
                startDownload(a);
            } else {
                confirmDelete(a);
            }
        }).show();
    }

    private void rename(final OfflineArea a) {
        final EditText input = new EditText(this);
        input.setText(a.name);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.offline_rename).setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    final String n = input.getText().toString().trim();
                    if (!n.isEmpty()) AppExecutors.io().execute(
                            () -> LocusDatabase.get(this).offlineAreaDao().rename(a.id, n));
                }).show();
    }

    private void confirmDelete(final OfflineArea a) {
        new MaterialAlertDialogBuilder(this).setMessage(getString(R.string.offline_delete_q, a.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.action_delete, (d, w) -> AppExecutors.io().execute(() -> {
                    OfflineMaps.remove(a, LocusDatabase.get(this).offlineAreaDao().getAll());
                    LocusDatabase.get(this).offlineAreaDao().delete(a.id);
                    runOnUiThread(this::showStorage);
                })).show();
    }

    @Override
    protected void onDestroy() {
        if (job != null) job.cancel();
        super.onDestroy();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
