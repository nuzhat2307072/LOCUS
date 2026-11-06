package com.locus.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.chip.Chip;
import com.locus.app.R;
import com.locus.app.data.db.LocusDatabase;
import com.locus.app.data.firebase.CommunityDiagnostics;
import com.locus.app.data.firebase.CommunityRepository;
import com.locus.app.data.firebase.CommunitySync;
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.ActivityCommunityBinding;
import com.locus.app.model.CommunityRequest;
import com.locus.app.model.RequestType;
import com.locus.app.ui.adapter.RequestsAdapter;
import com.locus.app.util.ExternalIntents;
import com.locus.app.util.AppPrefs;
import com.locus.app.util.NotificationHelper;
import com.locus.app.util.PermissionHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Community Assistance: blood donation, volunteers, disaster aid ...
 * The list observes Room (LiveData); a Firestore snapshot listener keeps Room up to date in real time.
 */
public class CommunityActivity extends AppCompatActivity {
    private ActivityCommunityBinding b;
    private RequestsAdapter adapter;
    private List<CommunityRequest> all = new ArrayList<>();
    private RequestType filter; // null = all
    private boolean mineOnly;
    private CommunitySync.State syncState = CommunitySync.State.CONNECTING;

    private final ActivityResultLauncher<String[]> notificationLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), r -> {
                // nothing else to do: notifications simply start working when granted
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityCommunityBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        // Ask for the notification permission once, here, where it actually helps (new requests nearby)
        String[] notif = PermissionHelper.notificationPermissions();
        if (notif.length > 0 && !PermissionHelper.hasNotifications(this) && !AppPrefs.notificationsAsked(this)) {
            AppPrefs.setNotificationsAsked(this);
            notificationLauncher.launch(notif);
        }

        adapter = new RequestsAdapter(new RequestsAdapter.Listener() {
            @Override
            public void onClick(CommunityRequest r) {
                showDetails(r);
            }

            @Override
            public void onLongClick(CommunityRequest r) {
                showOwnerActions(r);
            }
        });
        b.recycler.setLayoutManager(new LinearLayoutManager(this));
        b.recycler.setAdapter(adapter);

        buildChips();
        b.fabAdd.setOnClickListener(v -> {
            Intent i = new Intent(this, CreateRequestActivity.class);
            if (filter != null) i.putExtra(CreateRequestActivity.EXTRA_TYPE, filter.name());
            startActivity(i);
        });

        // Everything on the shared board (CommunitySync keeps Room equal to it) - mine and everyone else's
        LocusDatabase.get(this).requestDao().observeAll().observe(this, list -> {
            all = list;
            applyFilter();
        });

        CommunitySync.state().observe(this, s -> {
            syncState = s;
            updateSyncBanner();
        });
        b.tvSync.setOnClickListener(v -> onBannerTap());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSyncBanner(); // refreshes the "updated at" time
    }

    private void updateSyncBanner() {
        if (syncState == CommunitySync.State.LOCAL_ONLY) {
            b.tvSync.setVisibility(View.GONE);
            return;
        }
        boolean good = false;
        switch (syncState) {
            case LIVE:
                good = true;
                long at = CommunitySync.lastUpdate();
                String time = at == 0 ? "" : android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(at));
                b.tvSync.setText(getString(R.string.sync_live, time));
                break;
            case CONNECTING:
                b.tvSync.setText(R.string.sync_connecting);
                break;
            case OFFLINE:
                b.tvSync.setText(R.string.sync_offline);
                break;
            case NO_PERMISSION:
                b.tvSync.setText(R.string.sync_no_permission);
                break;
            case NO_DATABASE:
                b.tvSync.setText(R.string.sync_no_database);
                break;
            case ERROR:
                b.tvSync.setText(R.string.sync_error);
                break;
            default:
                b.tvSync.setVisibility(View.GONE);
                return;
        }
        b.tvSync.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this,
                good ? R.color.sync_ok_bg : R.color.sync_warn_bg));
        b.tvSync.setVisibility(View.VISIBLE);
    }

    private void onBannerTap() {
        if (syncState == CommunitySync.State.NO_PERMISSION || syncState == CommunitySync.State.NO_DATABASE) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.cloud_setup_title)
                    .setMessage(R.string.cloud_setup_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private void buildChips() {
        addChip(getString(R.string.filter_all), null, true, View.generateViewId());
        addChip(getString(R.string.filter_mine), null, false, R.id.chipMine);
        for (RequestType t : RequestType.values()) {
            addChip(getString(t.labelRes), t, false, View.generateViewId());
        }
        b.chipGroup.setOnCheckedStateChangeListener((group, ids) -> {
            if (ids.isEmpty()) return;
            Chip chip = group.findViewById(ids.get(0));
            mineOnly = chip.getId() == R.id.chipMine;
            filter = (RequestType) chip.getTag();
            applyFilter();
        });
    }

    private Chip addChip(String label, RequestType type, boolean checked, int id) {
        Chip chip = new Chip(this);
        chip.setText(label);
        chip.setCheckable(true);
        chip.setTag(type);
        chip.setId(id);
        b.chipGroup.addView(chip);
        if (checked) chip.setChecked(true);
        return chip;
    }

    private void applyFilter() {
        List<CommunityRequest> shown = new ArrayList<>();
        for (CommunityRequest r : all) {
            if (mineOnly && !r.ownerUid.equals(Session.uid(this))) continue;
            if (filter == null || filter.name().equals(r.type)) shown.add(r);
        }
        adapter.submitList(shown);
        b.tvEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private static boolean containsId(List<CommunityRequest> list, String id) {
        for (CommunityRequest r : list) if (r.id.equals(id)) return true;
        return false;
    }

    private void showDetails(CommunityRequest r) {
        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle(r.title)
                .setMessage(getString(R.string.request_details, getString(RequestType.fromName(r.type).labelRes),
                        r.description, r.contact, r.ownerName))
                .setPositiveButton(R.string.action_call, (dialog, w) ->
                        ExternalIntents.start(this, new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + r.contact))))
                .setNeutralButton(R.string.action_share, (dialog, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, getString(R.string.request_share_text,
                            getString(RequestType.fromName(r.type).labelRes), r.title, r.description, r.contact));
                    ExternalIntents.start(this, Intent.createChooser(send, getString(R.string.share_request)));
                });
        if (r.hasLocation) {
            d.setNegativeButton(R.string.action_open_maps, (dialog, w) ->
                    ExternalIntents.openMaps(this, r.lat, r.lon, r.title));
        }
        d.show();
    }

    private void showOwnerActions(CommunityRequest r) {
        if (!r.ownerUid.equals(Session.uid(this))) {
            Toast.makeText(this, R.string.only_owner, Toast.LENGTH_SHORT).show();
            return;
        }
        boolean done = CommunityRequest.DONE.equals(r.status);
        String[] items = {getString(done ? R.string.action_reopen : R.string.action_mark_done),
                getString(R.string.action_delete_request)};
        new AlertDialog.Builder(this)
                .setTitle(r.title)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        CommunityRepository.setStatus(this, r, done ? CommunityRequest.OPEN : CommunityRequest.DONE);
                    } else {
                        new AlertDialog.Builder(this)
                                .setMessage(R.string.delete_confirm)
                                .setPositiveButton(R.string.action_delete, (d2, w2) -> CommunityRepository.delete(this, r))
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    }
                })
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.menu_community, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == R.id.action_check_connection) {
            runConnectionCheck();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Really writes and reads a test document on the server and explains the result in plain words. */
    private void runConnectionCheck() {
        Toast.makeText(this, R.string.diag_running, Toast.LENGTH_SHORT).show();
        CommunityDiagnostics.run(this, (report, allGood) -> {
            if (isFinishing() || isDestroyed()) return;
            AlertDialog.Builder d = new AlertDialog.Builder(this)
                    .setTitle(R.string.menu_check_connection)
                    .setMessage(report)
                    .setPositiveButton(android.R.string.ok, null);
            if (!allGood) {
                d.setNeutralButton(R.string.cloud_setup_title, (dialog, w) -> new AlertDialog.Builder(this)
                        .setTitle(R.string.cloud_setup_title)
                        .setMessage(R.string.cloud_setup_message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show());
            }
            d.show();
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
