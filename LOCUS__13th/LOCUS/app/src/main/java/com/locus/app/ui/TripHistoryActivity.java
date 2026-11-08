package com.locus.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.locus.app.R;
import com.locus.app.data.db.LocusDatabase;
import com.locus.app.databinding.ActivityTripHistoryBinding;
import com.locus.app.databinding.HeaderTripStatsBinding;
import com.locus.app.databinding.ItemTripBinding;
import com.locus.app.model.Trip;
import com.locus.app.model.TripStats;
import com.locus.app.ui.widget.BarChartView;
import com.locus.app.util.AppExecutors;
import com.locus.app.util.GeoUtils;
import com.locus.app.util.TripCsv;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Every navigation is logged automatically. This screen shows weekly / monthly charts of distance and time by mode,
 * the list of trips (replay, re-route, delete), and exports the whole history as CSV.
 */
public class TripHistoryActivity extends AppCompatActivity {
    private ActivityTripHistoryBinding b;
    private HeaderTripStatsBinding header;
    private final List<Trip> trips = new ArrayList<>();
    private Adapter adapter;
    private boolean weekly = true;
    private boolean distance = true;

    private final ActivityResultLauncher<String> createCsv =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"), this::writeCsv);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityTripHistoryBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        header = HeaderTripStatsBinding.inflate(getLayoutInflater(), b.rvTrips, false);
        adapter = new Adapter();
        b.rvTrips.setLayoutManager(new LinearLayoutManager(this));
        b.rvTrips.setAdapter(adapter);

        header.toggleSpan.check(R.id.btnWeekly);
        header.toggleMeasure.check(R.id.btnDistance);
        header.toggleSpan.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            weekly = id == R.id.btnWeekly;
            drawChart();
        });
        header.toggleMeasure.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            distance = id == R.id.btnDistance;
            drawChart();
        });

        LocusDatabase.get(this).tripDao().observeAll().observe(this, list -> {
            trips.clear();
            if (list != null) trips.addAll(list);
            adapter.notifyDataSetChanged();
            drawChart();
        });
    }

    private void drawChart() {
        int n = trips.size();
        header.tvEmpty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        long[] at = new long[n];
        double[] m = new double[n];
        long[] s = new long[n];
        String[] mode = new String[n];
        for (int i = 0; i < n; i++) {
            Trip t = trips.get(i);
            at[i] = t.startedAt;
            m[i] = t.meters;
            s[i] = t.seconds;
            mode[i] = t.mode;
        }
        TripStats.Result r = TripStats.buckets(at, m, s, mode, weekly, System.currentTimeMillis(), TimeZone.getDefault());
        int count = r.periodStart.length;
        String[] labels = new String[count];
        SimpleDateFormat f = new SimpleDateFormat(weekly ? "d MMM" : "MMM", Locale.getDefault());
        for (int i = 0; i < count; i++) labels[i] = f.format(new Date(r.periodStart[i]));
        double[][] data = distance ? r.km : r.minutes;
        header.chart.setData(data, labels, distance ? " km" : " min");

        double[] sum = new double[3];
        double all = 0;
        for (int k = 0; k < 3; k++) {
            for (int i = 0; i < count; i++) sum[k] += data[k][i];
            all += sum[k];
        }
        header.tvTotals.setText(getString(distance ? R.string.trips_total_distance : R.string.trips_total_time,
                distance ? String.format(Locale.getDefault(), "%.1f km", all) : String.format(Locale.getDefault(), "%.0f min", all)));
        header.tvLegend.setText(getString(R.string.trips_legend,
                getString(R.string.mode_walk), getString(R.string.mode_cycle), getString(R.string.mode_car)));
    }

    // ---- export / clear ---------------------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_trips, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_export_csv) {
            if (trips.isEmpty()) {
                Toast.makeText(this, R.string.trips_empty, Toast.LENGTH_SHORT).show();
            } else {
                createCsv.launch("locus-trips.csv");
            }
            return true;
        }
        if (item.getItemId() == R.id.action_clear_trips) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.trips_clear_q)
                    .setPositiveButton(R.string.action_delete, (d, w) ->
                            AppExecutors.io().execute(() -> LocusDatabase.get(getApplicationContext()).tripDao().deleteAll()))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void writeCsv(Uri uri) {
        if (uri == null) return;
        final String text = TripCsv.build(new ArrayList<>(trips));
        AppExecutors.io().execute(() -> {
            boolean ok = false;
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out != null) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                    ok = true;
                }
            } catch (Exception ignored) {
                // reported below
            }
            final boolean fine = ok;
            runOnUiThread(() -> Toast.makeText(this, fine ? R.string.trips_exported : R.string.trips_export_failed,
                    Toast.LENGTH_SHORT).show());
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    // ---- list ---------------------------------------------------------------------------------------

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int HEADER = 0, ROW = 1;

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? HEADER : ROW;
        }

        @Override
        public int getItemCount() {
            return trips.size() + 1;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == HEADER) {
                return new RecyclerView.ViewHolder(header.getRoot()) {
                };
            }
            return new Row(ItemTripBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (!(holder instanceof Row)) return;
            final Trip t = trips.get(position - 1);
            ItemTripBinding r = ((Row) holder).b;
            r.tvTripTitle.setText(getString(R.string.trips_title_line, t.destName));
            r.tvTripFrom.setText(getString(R.string.trips_from_line, t.startName,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(t.startedAt))));
            r.tvTripMeta.setText(getString(R.string.trips_meta_line, label(t.mode), GeoUtils.distanceText(TripHistoryActivity.this, t.meters),
                    duration(t.seconds), getString(t.completed ? R.string.trips_arrived : R.string.trips_ended_early)));
            boolean hasPath = !t.path.isEmpty();
            r.btnReplay.setVisibility(hasPath ? View.VISIBLE : View.GONE);
            r.btnReplay.setOnClickListener(v -> startActivity(new Intent(TripHistoryActivity.this, TripReplayActivity.class)
                    .putExtra(TripReplayActivity.EXTRA_TRIP, t)));
            r.btnReroute.setOnClickListener(v -> startActivity(
                    NavigationActivity.intentTo(TripHistoryActivity.this, t.destLat, t.destLon, t.destName)));
            r.btnTripDelete.setOnClickListener(v -> new MaterialAlertDialogBuilder(TripHistoryActivity.this)
                    .setMessage(R.string.trips_delete_q)
                    .setPositiveButton(R.string.action_delete, (d, w) ->
                            AppExecutors.io().execute(() -> LocusDatabase.get(getApplicationContext()).tripDao().delete(t.id)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
        }
    }

    private static final class Row extends RecyclerView.ViewHolder {
        final ItemTripBinding b;

        Row(ItemTripBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }

    private String label(String mode) {
        switch (mode) {
            case "WALK":
                return getString(R.string.mode_walk);
            case "CYCLE":
                return getString(R.string.mode_cycle);
            default:
                return getString(R.string.mode_car);
        }
    }

    private static String duration(long sec) {
        long min = Math.max(1, Math.round(sec / 60.0));
        return min >= 60 ? (min / 60) + " h " + (min % 60) + " min" : min + " min";
    }
}
