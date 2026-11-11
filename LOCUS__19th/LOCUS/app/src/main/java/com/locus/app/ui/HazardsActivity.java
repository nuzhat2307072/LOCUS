package com.locus.app.ui;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.locus.app.R;
import com.locus.app.data.db.LocusDatabase;
import com.locus.app.data.firebase.HazardRepository;
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.ActivityHazardsBinding;
import com.locus.app.databinding.ItemHazardBinding;
import com.locus.app.model.Hazard;
import com.locus.app.model.HazardTrust;
import com.locus.app.model.HazardType;
import com.locus.app.util.AppPrefs;
import com.locus.app.util.GeoUtils;
import com.locus.app.util.LocationHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Hazards around you: every live report within the chosen distance, nearest first, how old it is and whether
 * people near the place have confirmed it. People who are near a hazard can say "Still there" or "Cleared" right
 * from the list; reports made by one person alone stay "not verified" until two others nearby agree.
 */
public class HazardsActivity extends AppCompatActivity {
    private ActivityHazardsBinding b;
    private final List<Hazard> all = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private HazardAdapter adapter;
    private HazardRepository.Subscription sub;
    private int radiusM = 10_000; // 0 = no limit
    private Hazard photoFor;       // the report that gets the next picked photo
    private final androidx.activity.result.ActivityResultLauncher<String> pickPhoto = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.GetContent(), this::onPhotoPicked);

    private static final class Row {
        Hazard h;
        double dist; // metres from this person, -1 = unknown
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityHazardsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        adapter = new HazardAdapter();
        b.listHazards.setLayoutManager(new LinearLayoutManager(this));
        b.listHazards.setAdapter(adapter);

        b.chipsRadius.setOnCheckedStateChangeListener((group, ids) -> {
            int id = ids.isEmpty() ? R.id.chipR10 : ids.get(0);
            radiusM = id == R.id.chipR2 ? 2000 : id == R.id.chipR5 ? 5000 : id == R.id.chipRAll ? 0 : 10_000;
            rebuild();
        });
        b.btnReport.setOnClickListener(v -> chooseTypeAndReport());

        LocusDatabase.get(this).hazardDao().observeAll().observe(this, list -> {
            all.clear();
            if (list != null) {
                long now = System.currentTimeMillis();
                for (Hazard h : list) if (!h.isExpired(now)) all.add(h);
            }
            rebuild();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        sub = HazardRepository.listen(this); // keeps the list live while this screen is open
        LocationHelper.getLocation(this, loc -> { // a fresh position makes the distances and voting rights accurate
            if (loc != null && !isDestroyed()) rebuild();
        });
    }

    @Override
    protected void onStop() {
        if (sub != null) sub.stop();
        sub = null;
        super.onStop();
    }

    private void rebuild() {
        rows.clear();
        for (Hazard h : all) {
            Row r = new Row();
            r.h = h;
            r.dist = HazardRepository.distanceToMe(this, h);
            if (radiusM > 0 && r.dist >= 0 && r.dist > radiusM) continue;
            rows.add(r);
        }
        Collections.sort(rows, (x, y) -> {
            if (x.dist < 0 && y.dist < 0) return Long.compare(y.h.createdAt, x.h.createdAt);
            if (x.dist < 0) return 1;
            if (y.dist < 0) return -1;
            return Double.compare(x.dist, y.dist);
        });
        adapter.notifyDataSetChanged();
        b.tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        int verified = 0;
        for (Row r : rows) if (r.h.isVerified()) verified++;
        b.tvSummary.setText(getString(R.string.hazards_summary, rows.size(), verified));
    }

    /** Report a hazard at the current position: pick the kind, it is shared with everybody right away. */
    private void chooseTypeAndReport() {
        if (!AppPrefs.hasLastLocation(this)) {
            LocationHelper.getLocation(this, loc -> {
                if (isDestroyed()) return;
                if (loc == null) {
                    android.widget.Toast.makeText(this, R.string.hazard_far_unknown, android.widget.Toast.LENGTH_LONG).show();
                } else {
                    chooseTypeAndReport();
                }
            });
            return;
        }
        final HazardType[] types = HazardType.values();
        String[] names = new String[types.length];
        for (int i = 0; i < types.length; i++) names[i] = getString(types[i].labelRes);
        new AlertDialog.Builder(this)
                .setTitle(R.string.hazard_report_here)
                .setItems(names, (d, which) -> report(types[which]))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void report(HazardType t) {
        double[] at = AppPrefs.lastLocation(this);
        Hazard h = new Hazard();
        h.id = UUID.randomUUID().toString();
        h.type = t.name();
        h.description = t.label;
        h.lat = at[0];
        h.lon = at[1];
        h.radiusM = t.defaultRadius;
        h.createdAt = System.currentTimeMillis();
        h.reporterUid = Session.uid(this);
        HazardRepository.add(this, h, null);
        android.widget.Toast.makeText(this, getString(R.string.hazard_reported, getString(t.labelRes)),
                android.widget.Toast.LENGTH_LONG).show();
        // a photo makes a report far easier to trust
        new AlertDialog.Builder(this)
                .setMessage(R.string.hazard_add_photo_ask)
                .setPositiveButton(R.string.hazard_add_photo, (d, w) -> {
                    photoFor = h;
                    pickPhoto.launch("image/*");
                })
                .setNegativeButton(R.string.hazard_no_photo, null)
                .show();
    }

    private void onPhotoPicked(android.net.Uri uri) {
        final Hazard target = photoFor;
        photoFor = null;
        if (uri == null || target == null) return;
        final android.content.ContentResolver resolver = getContentResolver();
        com.locus.app.util.AppExecutors.io().execute(() -> {
            final String data = com.locus.app.util.ImageCodec.encode(resolver, uri);
            com.locus.app.util.AppExecutors.main().post(() -> {
                if (isDestroyed()) return;
                if (data == null) {
                    android.widget.Toast.makeText(this, R.string.hazard_photo_failed, android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                HazardRepository.attachPhoto(this, target, data, () -> {
                    if (!isDestroyed()) {
                        android.widget.Toast.makeText(this, R.string.hazard_photo_added, android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            });
        });
    }

    private final class HazardAdapter extends RecyclerView.Adapter<HazardAdapter.VH> {
        final class VH extends RecyclerView.ViewHolder {
            final ItemHazardBinding v;

            VH(ItemHazardBinding v) {
                super(v.getRoot());
                this.v = v;
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(ItemHazardBinding.inflate(getLayoutInflater(), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            final Row r = rows.get(position);
            final Hazard h = r.h;
            final HazardType t = HazardType.fromName(h.type);
            final ItemHazardBinding v = holder.v;
            final boolean mine = h.reporterUid != null && h.reporterUid.equals(Session.uid(HazardsActivity.this));
            v.tvTitle.setText(t.labelRes);
            String where = r.dist < 0 ? getString(R.string.hazard_distance_unknown)
                    : getString(R.string.hazard_away, GeoUtils.distanceText(HazardsActivity.this, r.dist));
            long last = Math.max(h.createdAt, h.lastConfirmedAt);
            v.tvMeta.setText(where + " · " + getString(R.string.hazard_reported_ago, ago(h.createdAt))
                    + (h.lastConfirmedAt > h.createdAt ? " · " + getString(R.string.hazard_confirmed_ago, ago(last)) : "")
                    + (mine ? " · " + getString(R.string.by_you) : ""));
            v.tvVerify.setText(HazardDialogs.verificationLine(HazardsActivity.this, h));
            v.tvVerify.setTextColor(HazardDialogs.verificationColor(HazardsActivity.this, h));
            boolean near = HazardTrust.canVote(r.dist);
            if (mine) {
                v.btnStill.setVisibility(View.GONE);
                v.btnCleared.setVisibility(View.GONE);
                v.tvNote.setText(R.string.hazard_yours_note);
            } else {
                v.btnStill.setVisibility(View.VISIBLE);
                v.btnCleared.setVisibility(View.VISIBLE);
                v.btnStill.setEnabled(near && !"still".equals(h.myVote));
                v.btnCleared.setEnabled(near && !"cleared".equals(h.myVote));
                v.tvNote.setText(near ? getString(R.string.hazard_you_can_vote)
                        : getString(R.string.hazard_come_closer, HazardTrust.VOTE_RADIUS_M));
            }
            v.btnStill.setOnClickListener(x -> HazardDialogs.vote(HazardsActivity.this, h, true, null));
            v.btnCleared.setOnClickListener(x -> HazardDialogs.vote(HazardsActivity.this, h, false, null));
            v.btnGo.setOnClickListener(x -> startActivity(
                    NavigationActivity.intentTo(HazardsActivity.this, h.lat, h.lon, getString(t.labelRes))));
            v.getRoot().setOnClickListener(x -> HazardDialogs.show(HazardsActivity.this, h, null));
            v.getRoot().setStrokeColor(ContextCompat.getColor(HazardsActivity.this,
                    h.isVerified() ? R.color.locus_ok : android.R.color.transparent));
            v.getRoot().setStrokeWidth(h.isVerified() ? 3 : 0);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    /** "5 min", "2 h", "1 d" - short, and the same wording in every language. */
    static String ago(long since) {
        long min = Math.max(0, (System.currentTimeMillis() - since) / 60_000L);
        if (min < 60) return min + " min";
        long h = min / 60;
        if (h < 48) return h + " h";
        return (h / 24) + " d";
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
