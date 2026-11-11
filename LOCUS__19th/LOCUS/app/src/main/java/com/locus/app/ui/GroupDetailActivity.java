package com.locus.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.locus.app.R;
import com.locus.app.data.firebase.ChatRepository;
import com.locus.app.data.firebase.GroupRepository;
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.ActivityGroupDetailBinding;
import com.locus.app.databinding.ItemContactBinding;
import com.locus.app.model.FamilyGroup;
import com.locus.app.model.GroupRules;

import java.util.ArrayList;
import java.util.List;

/**
 * One group: its code, the people waiting for the admin's approval (admin only), and the members. The admin can
 * approve, decline, remove, get a new code or delete the group; a member can leave.
 */
public class GroupDetailActivity extends AppCompatActivity {
    public static final String EXTRA_GROUP_ID = "groupId";

    private ActivityGroupDetailBinding b;
    private ChatRepository.Watch watch;
    private FamilyGroup group;
    private final List<Object> rows = new ArrayList<>(); // String header | JoinRequest | Member
    private Adapter adapter;
    private String myUid = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityGroupDetailBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        myUid = Session.uid(this);
        adapter = new Adapter();
        b.rvGroupPeople.setLayoutManager(new LinearLayoutManager(this));
        b.rvGroupPeople.setAdapter(adapter);
        b.btnShareCode.setOnClickListener(v -> shareCode());
        b.btnCopyCode.setOnClickListener(v -> copyCode());
        b.btnNewCode.setOnClickListener(v -> newCode());
        b.btnLeaveGroup.setOnClickListener(v -> confirmLeaveOrDelete());
    }

    @Override
    protected void onStart() {
        super.onStart();
        String id = getIntent().getStringExtra(EXTRA_GROUP_ID);
        if (id == null) {
            finish();
            return;
        }
        watch = GroupRepository.watchGroup(this, id, g -> runOnUiThread(() -> show(g)));
    }

    @Override
    protected void onStop() {
        if (watch != null) {
            watch.stop();
            watch = null;
        }
        super.onStop();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void show(FamilyGroup g) {
        if (isDestroyed()) return;
        if (g == null) {
            if (group != null) Toast.makeText(this, R.string.group_gone, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        group = g;
        b.toolbar.setTitle(g.name);
        b.tvGroupCode.setText(g.code.isEmpty() ? "..." : GroupRules.pretty(g.code));
        b.tvGroupAdmin.setText(getString(R.string.group_admin_is, g.adminName));
        b.btnNewCode.setVisibility(g.isAdmin() ? View.VISIBLE : View.GONE);
        b.btnLeaveGroup.setText(g.isAdmin() ? R.string.group_delete : R.string.group_leave);
        rows.clear();
        if (g.isAdmin() && !g.requests.isEmpty()) {
            rows.add(getString(R.string.group_requests_header, g.requests.size()));
            rows.addAll(g.requests);
        }
        rows.add(getString(R.string.group_members_header, g.members.size()));
        rows.addAll(g.members);
        adapter.notifyDataSetChanged();
    }

    private void toast(String error, int okText, String okArg) {
        if (isDestroyed()) return;
        String text = error == null ? (okArg == null ? getString(okText) : getString(okText, okArg))
                : "full".equals(error) ? getString(R.string.group_err_full) : getString(R.string.group_failed);
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void shareCode() {
        if (group == null || group.code.isEmpty()) return;
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                getString(R.string.group_share_text, group.name, GroupRules.pretty(group.code)));
        startActivity(Intent.createChooser(send, getString(R.string.group_code_share)));
    }

    private void copyCode() {
        if (group == null || group.code.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("code", group.code));
        Toast.makeText(this, R.string.group_code_copied, Toast.LENGTH_SHORT).show();
    }

    private void newCode() {
        if (group == null) return;
        GroupRepository.newCode(this, group, (error, code) -> runOnUiThread(() ->
                toast(error, R.string.group_code_new_done, code == null ? null : GroupRules.pretty(code))));
    }

    private void confirmLeaveOrDelete() {
        if (group == null) return;
        final FamilyGroup g = group;
        if (g.isAdmin()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.group_delete_title)
                    .setMessage(getString(R.string.group_delete_text, g.name))
                    .setPositiveButton(R.string.group_delete, (d, w) -> GroupRepository.deleteGroup(this, g, (e, x) ->
                            runOnUiThread(() -> toast(e, R.string.group_deleted, null))))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.group_leave_title)
                    .setMessage(getString(R.string.group_leave_text, g.name))
                    .setPositiveButton(R.string.group_leave, (d, w) -> GroupRepository.leave(this, g, (e, x) ->
                            runOnUiThread(() -> toast(e, R.string.group_left, null))))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemContactBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Object row = rows.get(position);
            ItemContactBinding v = h.v;
            v.tvNote.setVisibility(View.GONE);
            v.card.setOnClickListener(null);
            v.card.setClickable(false);
            if (row instanceof String) {
                v.tvHeader.setVisibility(View.VISIBLE);
                v.tvHeader.setText((String) row);
                v.card.setVisibility(View.GONE);
                return;
            }
            v.tvHeader.setVisibility(View.GONE);
            v.card.setVisibility(View.VISIBLE);
            if (row instanceof FamilyGroup.JoinRequest) {
                final FamilyGroup.JoinRequest r = (FamilyGroup.JoinRequest) row;
                v.tvName.setText(r.name.isEmpty() ? getString(R.string.chat_someone) : r.name);
                v.tvAbout.setText(R.string.group_wants_to_join);
                v.btnPrimary.setVisibility(View.VISIBLE);
                v.btnPrimary.setText(R.string.group_approve);
                v.btnPrimary.setOnClickListener(x -> GroupRepository.approve(GroupDetailActivity.this, group, r, (e, y) ->
                        runOnUiThread(() -> toast(e, R.string.group_approved_toast, r.name))));
                v.btnSecondary.setVisibility(View.VISIBLE);
                v.btnSecondary.setText(R.string.group_decline);
                v.btnSecondary.setOnClickListener(x -> GroupRepository.decline(GroupDetailActivity.this, group, r, (e, y) ->
                        runOnUiThread(() -> toast(e, R.string.group_declined_toast, null))));
            } else {
                final FamilyGroup.Member m = (FamilyGroup.Member) row;
                String name = m.name.isEmpty() ? getString(R.string.chat_someone) : m.name;
                v.tvName.setText(m.uid.equals(myUid) ? getString(R.string.group_you, name) : name);
                v.tvAbout.setText(GroupRules.isAdmin(m.role) ? R.string.group_role_admin : R.string.group_role_member);
                v.btnPrimary.setVisibility(View.GONE);
                boolean canRemove = group != null && group.isAdmin() && !m.uid.equals(myUid);
                v.btnSecondary.setVisibility(canRemove ? View.VISIBLE : View.GONE);
                v.btnSecondary.setText(R.string.group_remove);
                v.btnSecondary.setOnClickListener(x -> new AlertDialog.Builder(GroupDetailActivity.this)
                        .setTitle(R.string.group_remove_title)
                        .setMessage(getString(R.string.group_remove_text, name, group.name))
                        .setPositiveButton(R.string.group_remove, (d, w) -> GroupRepository.removeMember(GroupDetailActivity.this, group, m, (e, y) ->
                                runOnUiThread(() -> toast(e, R.string.group_removed_toast, null))))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());
            }
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ItemContactBinding v;

            Holder(ItemContactBinding v) {
                super(v.getRoot());
                this.v = v;
            }
        }
    }
}
