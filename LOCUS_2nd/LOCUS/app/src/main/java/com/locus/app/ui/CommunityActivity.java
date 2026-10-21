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
import com.google.firebase.firestore.ListenerRegistration;
import com.locus.app.R;
import com.locus.app.data.db.LocusDatabase;
import com.locus.app.data.firebase.CommunityRepository;
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
    private ListenerRegistration registration;
    private boolean firstLoad = true;

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

        LocusDatabase.get(this).requestDao().observeAll().observe(this, list -> {
            // a request from someone else arriving while the screen is open -> notification
            if (!firstLoad) {
                for (CommunityRequest r : list) {
                    boolean recent = System.currentTimeMillis() - r.createdAt < 10 * 60 * 1000L;
                    if (recent && !containsId(all, r.id) && !r.ownerUid.equals(Session.uid(this))
                            && CommunityRequest.OPEN.equals(r.status)) {
                        NotificationHelper.notify(this, NotificationHelper.CH_COMMUNITY, r.id.hashCode(),
                                RequestType.fromName(r.type).label, r.title, CommunityActivity.class);
                        break;
                    }
                }
            }
            firstLoad = false;
            all = list;
            applyFilter();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        registration = CommunityRepository.listen(this); // real-time sync (null in guest mode)
    }

    @Override
    protected void onStop() {
        if (registration != null) {
            registration.remove();
            registration = null;
        }
        super.onStop();
    }

    private void buildChips() {
        addChip("All", null, true);
        for (RequestType t : RequestType.values()) addChip(t.label, t, false);
        b.chipGroup.setOnCheckedStateChangeListener((group, ids) -> {
            if (ids.isEmpty()) return;
            Chip chip = group.findViewById(ids.get(0));
            filter = (RequestType) chip.getTag();
            applyFilter();
        });
    }

    private void addChip(String label, RequestType type, boolean checked) {
        Chip chip = new Chip(this);
        chip.setText(label);
        chip.setCheckable(true);
        chip.setTag(type);
        chip.setId(View.generateViewId());
        b.chipGroup.addView(chip);
        if (checked) chip.setChecked(true);
    }

    private void applyFilter() {
        List<CommunityRequest> shown = new ArrayList<>();
        for (CommunityRequest r : all) {
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
                .setMessage(RequestType.fromName(r.type).label + "\n\n" + r.description
                        + "\n\nContact: " + r.contact + "\nPosted by " + r.ownerName)
                .setPositiveButton(R.string.action_call, (dialog, w) ->
                        ExternalIntents.start(this, new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + r.contact))))
                .setNeutralButton(R.string.action_share, (dialog, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, "[" + RequestType.fromName(r.type).label + "] " + r.title
                            + "\n" + r.description + "\nContact: " + r.contact);
                    ExternalIntents.start(this, Intent.createChooser(send, "Share request"));
                });
        if (r.hasLocation) {
            d.setNegativeButton(R.string.action_open_maps, (dialog, w) ->
                    ExternalIntents.openMaps(this, r.lat, r.lon, r.title));
        }
        d.show();
    }

    private void showOwnerActions(CommunityRequest r) {
        if (!r.ownerUid.equals(Session.uid(this))) {
            Toast.makeText(this, "Only the person who posted this can change it", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean done = CommunityRequest.DONE.equals(r.status);
        String[] items = {done ? "Re-open request" : "Mark as fulfilled", "Delete request"};
        new AlertDialog.Builder(this)
                .setTitle(r.title)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        CommunityRepository.setStatus(this, r, done ? CommunityRequest.OPEN : CommunityRequest.DONE);
                    } else {
                        new AlertDialog.Builder(this)
                                .setMessage("Delete this request for everyone?")
                                .setPositiveButton("Delete", (d2, w2) -> CommunityRepository.delete(this, r))
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    }
                })
                .show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
