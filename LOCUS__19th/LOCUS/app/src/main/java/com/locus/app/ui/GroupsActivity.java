package com.locus.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
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
import com.locus.app.databinding.ActivityGroupsBinding;
import com.locus.app.databinding.ItemGroupBinding;
import com.locus.app.model.FamilyGroup;
import com.locus.app.model.GroupRules;

import java.util.ArrayList;
import java.util.List;

/** Family and friends: my groups, start a new one (I become its admin) or join one with a code. */
public class GroupsActivity extends AppCompatActivity {
    private ActivityGroupsBinding b;
    private final List<FamilyGroup> groups = new ArrayList<>();
    private Adapter adapter;
    private ChatRepository.Watch watch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityGroupsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        adapter = new Adapter();
        b.rvGroups.setLayoutManager(new LinearLayoutManager(this));
        b.rvGroups.setAdapter(adapter);
        boolean online = Session.canSyncOnline(this);
        b.tvEmptyGroups.setText(online ? R.string.groups_empty : R.string.chat_need_cloud);
        b.tvEmptyGroups.setVisibility(View.VISIBLE);
        b.btnCreateGroup.setOnClickListener(v -> askName());
        b.btnJoinGroup.setOnClickListener(v -> askCode());
    }

    @Override
    protected void onStart() {
        super.onStart();
        watch = GroupRepository.watchMine(this, list -> runOnUiThread(() -> {
            groups.clear();
            groups.addAll(list);
            adapter.notifyDataSetChanged();
            b.tvEmptyGroups.setText(R.string.groups_empty);
            b.tvEmptyGroups.setVisibility(groups.isEmpty() ? View.VISIBLE : View.GONE);
        }));
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

    private EditText input(int hintRes, int maxLen, boolean caps) {
        EditText et = new EditText(this);
        et.setHint(hintRes);
        et.setSingleLine(true);
        et.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(maxLen)});
        et.setInputType(caps ? android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                : android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        return et;
    }

    private FrameLayout box(EditText et) {
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout f = new FrameLayout(this);
        f.setPadding(pad, pad / 2, pad, 0);
        f.addView(et);
        return f;
    }

    private void askName() {
        final EditText et = input(R.string.group_name_hint, GroupRules.MAX_NAME, false);
        new AlertDialog.Builder(this)
                .setTitle(R.string.group_create_title)
                .setView(box(et))
                .setPositiveButton(R.string.group_create, (d, w) -> {
                    Toast.makeText(this, R.string.group_creating, Toast.LENGTH_SHORT).show();
                    GroupRepository.create(this, et.getText().toString(), (error, code) -> runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        if (error != null) {
                            Toast.makeText(this, explain(error, ""), Toast.LENGTH_LONG).show();
                            return;
                        }
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.group_created_title)
                                .setMessage(getString(R.string.group_created, GroupRules.pretty(code)))
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                    }));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void askCode() {
        final EditText et = input(R.string.group_code_hint, 10, true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.group_join_title)
                .setView(box(et))
                .setPositiveButton(R.string.group_join_send, (d, w) ->
                        GroupRepository.requestJoin(this, et.getText().toString(), (error, name) -> runOnUiThread(() -> {
                            if (isDestroyed()) return;
                            if (error != null) {
                                Toast.makeText(this, explain(error, name), Toast.LENGTH_LONG).show();
                                return;
                            }
                            new AlertDialog.Builder(this)
                                    .setMessage(getString(R.string.group_join_sent, name))
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show();
                        })))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String explain(String error, String name) {
        switch (error) {
            case "noname": return getString(R.string.group_err_noname);
            case "badcode": return getString(R.string.group_err_badcode);
            case "nocode": return getString(R.string.group_err_nocode);
            case "own": return getString(R.string.group_err_own);
            case "already": return getString(R.string.group_err_already, name == null ? "" : name);
            case "full": return getString(R.string.group_err_full);
            case "cloud": return getString(R.string.chat_need_cloud);
            case "nodb": return getString(R.string.chat_need_db);
            case "offline": return getString(R.string.chat_offline);
            case "rules": return getString(R.string.chat_rules);
            default: return getString(R.string.group_failed);
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemGroupBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            final FamilyGroup g = groups.get(position);
            h.v.tvGroupName.setText(g.name);
            h.v.tvGroupRole.setText(g.isAdmin() ? getString(R.string.group_role_admin)
                    : getString(R.string.group_admin_is, g.adminName));
            h.v.getRoot().setOnClickListener(x -> startActivity(new Intent(GroupsActivity.this, GroupDetailActivity.class)
                    .putExtra(GroupDetailActivity.EXTRA_GROUP_ID, g.id)));
        }

        @Override
        public int getItemCount() {
            return groups.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ItemGroupBinding v;

            Holder(ItemGroupBinding v) {
                super(v.getRoot());
                this.v = v;
            }
        }
    }
}
