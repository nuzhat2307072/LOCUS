package com.locus.app.ui;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.locus.app.R;
import com.locus.app.data.firebase.LiveShareRepository;
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.ActivityShareHubBinding;
import com.locus.app.databinding.ItemContactBinding;
import com.locus.app.service.LocationShareService;
import com.locus.app.util.AppPrefs;
import com.locus.app.util.ExternalIntents;
import com.locus.app.util.PermissionHelper;
import com.locus.app.util.ShareState;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Live location sharing screen: start / stop my share (15 minutes, 1 hour or until I arrive), invite people by
 * e-mail or code, revoke them, and open the shares other people invited me to.
 */
public class ShareHubActivity extends AppCompatActivity {
    private ActivityShareHubBinding b;
    private Object viewersHandle;
    private String listeningCode = "";

    private final ActivityResultLauncher<String[]> locationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> {
                if (PermissionHelper.hasLocation(this)) startSharing();
                else Toast.makeText(this, R.string.share_need_location, Toast.LENGTH_LONG).show();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityShareHubBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        b.btnStartStop.setOnClickListener(v -> {
            if (ShareState.active(this)) confirmStop();
            else if (PermissionHelper.hasLocation(this)) startSharing();
            else locationPermission.launch(PermissionHelper.locationPermissions());
        });
        b.btnInviteEmail.setOnClickListener(v -> invite(true));
        b.btnShareCode.setOnClickListener(v -> invite(false));
        b.btnJoin.setOnClickListener(v -> askCode());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A share whose time ran out while the app was closed ends now; one that is still valid keeps being published.
        if (ShareState.active(this)) {
            long end = ShareState.expiresAt(this);
            if (end > 0 && System.currentTimeMillis() >= end) {
                LiveShareRepository.stop(this);
            } else if (PermissionHelper.hasLocation(this)) {
                ContextCompat.startForegroundService(this, new Intent(this, LocationShareService.class));
            }
        }
        refresh();
    }

    @Override
    protected void onPause() {
        stopViewerListener();
        super.onPause();
    }

    private void stopViewerListener() {
        if (viewersHandle != null) LiveShareRepository.stopListening(viewersHandle);
        viewersHandle = null;
        listeningCode = "";
    }

    private void refresh() {
        boolean online = Session.canSyncOnline(this);
        b.tvNeedCloud.setVisibility(online ? View.GONE : View.VISIBLE);
        boolean active = ShareState.active(this);
        b.panelActive.setVisibility(active ? View.VISIBLE : View.GONE);
        b.groupDuration.setVisibility(active ? View.GONE : View.VISIBLE);
        b.btnStartStop.setText(active ? R.string.share_stop : R.string.share_start);
        b.btnStartStop.setEnabled(online || active);
        if (active) {
            String code = ShareState.code(this);
            b.tvCode.setText(code);
            DateFormat f = DateFormat.getTimeInstance(DateFormat.SHORT);
            b.tvShareState.setText(ShareState.untilArrive(this)
                    ? getString(R.string.share_state_arrive, AppPrefs.lastDestinationName(this))
                    : getString(R.string.share_state_until, f.format(new Date(ShareState.expiresAt(this)))));
            if (!code.equals(listeningCode)) {
                stopViewerListener();
                listeningCode = code;
                viewersHandle = LiveShareRepository.listenViewers(code, this::showViewers);
            }
        } else {
            b.tvShareState.setText(R.string.share_state_off);
        }
        showJoined();
    }

    private void showViewers(Map<String, String> viewers) {
        if (isDestroyed()) return;
        b.listViewers.removeAllViews();
        b.tvNoViewers.setVisibility(viewers.isEmpty() ? View.VISIBLE : View.GONE);
        final String code = ShareState.code(this);
        for (final Map.Entry<String, String> e : viewers.entrySet()) {
            ItemContactBinding row = ItemContactBinding.inflate(getLayoutInflater(), b.listViewers, false);
            row.tvHeader.setVisibility(View.GONE);
            row.tvNote.setVisibility(View.GONE);
            row.btnPrimary.setVisibility(View.GONE);
            row.tvName.setText(e.getValue());
            row.tvAbout.setText(R.string.share_can_see_you);
            row.btnSecondary.setVisibility(View.VISIBLE);
            row.btnSecondary.setText(R.string.share_revoke);
            row.btnSecondary.setContentDescription(getString(R.string.share_revoke));
            row.btnSecondary.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                    .setMessage(getString(R.string.share_revoke_q, e.getValue()))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.share_revoke, (d, w) -> LiveShareRepository.revoke(code, e.getKey()))
                    .show());
            b.listViewers.addView(row.getRoot());
        }
    }

    private void showJoined() {
        b.listJoined.removeAllViews();
        List<String[]> joined = ShareState.joined(this);
        b.tvNoJoined.setVisibility(joined.isEmpty() ? View.VISIBLE : View.GONE);
        for (final String[] s : joined) {
            ItemContactBinding row = ItemContactBinding.inflate(getLayoutInflater(), b.listJoined, false);
            row.tvHeader.setVisibility(View.GONE);
            row.tvNote.setVisibility(View.GONE);
            row.btnPrimary.setVisibility(View.GONE);
            row.tvName.setText(s[1]);
            row.tvAbout.setText(getString(R.string.share_code_is, s[0]));
            row.getRoot().setOnClickListener(v -> startActivity(new Intent(this, LiveViewActivity.class)
                    .putExtra(LiveViewActivity.EXTRA_CODE, s[0]).putExtra(LiveViewActivity.EXTRA_NAME, s[1])));
            row.btnSecondary.setVisibility(View.VISIBLE);
            row.btnSecondary.setText(R.string.share_leave);
            row.btnSecondary.setContentDescription(getString(R.string.share_leave));
            row.btnSecondary.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                    .setMessage(getString(R.string.share_leave_q, s[1]))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.share_leave, (d, w) -> {
                        LiveShareRepository.leave(this, s[0]);
                        showJoined();
                    })
                    .show());
            b.listJoined.addView(row.getRoot());
        }
    }

    // ------------------------------------------------------------------ my share

    private void startSharing() {
        long duration;
        double destLat = 0, destLon = 0;
        String destName = "";
        int checked = b.groupDuration.getCheckedChipId();
        if (checked == R.id.chipArrive) {
            if (!AppPrefs.hasLastDestination(this)) {
                Toast.makeText(this, R.string.share_need_destination, Toast.LENGTH_LONG).show();
                return;
            }
            double[] d = AppPrefs.lastDestination(this);
            destLat = d[0];
            destLon = d[1];
            destName = AppPrefs.lastDestinationName(this);
            duration = 0;
        } else {
            duration = checked == R.id.chip60 ? 3600_000L : 900_000L;
        }
        b.btnStartStop.setEnabled(false);
        b.tvShareState.setText(R.string.share_starting);
        LiveShareRepository.start(this, duration, destLat, destLon, destName, error -> {
            if (isDestroyed()) return;
            b.btnStartStop.setEnabled(true);
            if (error != null) {
                b.tvShareState.setText(error.equals("cloud") ? getString(R.string.share_need_cloud)
                        : error.equals("timeout") ? getString(R.string.share_timeout)
                        : getString(R.string.share_failed, error));
                return;
            }
            refresh();
        });
    }

    private void confirmStop() {
        new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.share_stop_q)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.share_stop, (d, w) -> {
                    LiveShareRepository.stop(this);
                    stopViewerListener();
                    refresh();
                })
                .show();
    }

    private void invite(boolean email) {
        String code = ShareState.code(this);
        if (code.isEmpty()) return;
        String text = getString(R.string.share_invite_text, Session.displayName(this), code);
        Intent i;
        if (email) {
            i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                    .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_invite_subject, Session.displayName(this)))
                    .putExtra(Intent.EXTRA_TEXT, text);
        } else {
            i = Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text), getString(R.string.share_send_code));
        }
        ExternalIntents.start(this, i);
    }

    // ------------------------------------------------------------------ shares of others

    private void askCode() {
        final EditText code = new EditText(this);
        code.setHint(R.string.share_code_hint);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(code);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.share_join)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.share_join_go, (d, w) -> join(code.getText().toString()))
                .show();
    }

    private void join(String code) {
        Toast.makeText(this, R.string.share_joining, Toast.LENGTH_SHORT).show();
        LiveShareRepository.join(this, code, error -> {
            if (isDestroyed()) return;
            if (error == null) {
                Toast.makeText(this, R.string.share_joined, Toast.LENGTH_SHORT).show();
                showJoined();
            } else {
                Toast.makeText(this, error.equals("code") ? R.string.share_bad_code
                        : error.equals("cloud") ? R.string.share_need_cloud
                        : error.equals("timeout") ? R.string.share_timeout
                        : R.string.share_code_unknown, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
