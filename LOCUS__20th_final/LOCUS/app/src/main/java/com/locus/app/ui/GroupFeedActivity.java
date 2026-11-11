package com.locus.app.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.locus.app.R;
import com.locus.app.data.firebase.ChatRepository;
import com.locus.app.data.firebase.GroupFeedRepository;
import com.locus.app.data.firebase.GroupRepository;
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.ActivityGroupFeedBinding;
import com.locus.app.databinding.ItemGroupPostBinding;
import com.locus.app.model.FamilyGroup;
import com.locus.app.model.GroupPost;
import com.locus.app.model.GroupPostRules;
import com.locus.app.util.AppExecutors;
import com.locus.app.util.ExternalIntents;
import com.locus.app.util.ImageCodec;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The stream of a family-and-friends group: messages and photos, trip and party plans and help requests (with
 * voting), and the Community posts and SOS that members shared. Everyone in the group sees the same stream.
 */
public class GroupFeedActivity extends AppCompatActivity {
    public static final String EXTRA_GROUP_ID = "groupId";

    private ActivityGroupFeedBinding b;
    private String gid = "";
    private String myUid = "";
    private FamilyGroup group;
    private ChatRepository.Watch groupWatch;
    private ChatRepository.Watch feedWatch;
    private final List<GroupPost> posts = new ArrayList<>();
    private Map<String, Map<String, String>> votes = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private final android.util.LruCache<String, Bitmap> photos = new android.util.LruCache<>(16);
    private Adapter adapter;
    private LinearLayoutManager layout;
    private boolean firstLoad = true;

    private final ActivityResultLauncher<String> pickPhoto =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::sendPhoto);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityGroupFeedBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        String id = getIntent().getStringExtra(EXTRA_GROUP_ID);
        if (id == null) {
            finish();
            return;
        }
        gid = id;
        myUid = Session.uid(this);
        adapter = new Adapter();
        layout = new LinearLayoutManager(this);
        layout.setStackFromEnd(true);
        b.rvFeed.setLayoutManager(layout);
        b.rvFeed.setAdapter(adapter);

        b.btnFeedSend.setOnClickListener(v -> sendText());
        b.btnFeedPhoto.setOnClickListener(v -> pickPhoto.launch("image/*"));
        b.btnFeedNew.setOnClickListener(this::showNewMenu);
    }

    @Override
    protected void onStart() {
        super.onStart();
        groupWatch = GroupRepository.watchGroup(this, gid, g -> runOnUiThread(() -> onGroup(g)));
        feedWatch = GroupFeedRepository.watch(this, gid, (list, v) -> runOnUiThread(() -> onFeed(list, v)));
    }

    @Override
    protected void onStop() {
        if (groupWatch != null) groupWatch.stop();
        if (feedWatch != null) feedWatch.stop();
        groupWatch = null;
        feedWatch = null;
        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_group_feed, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_group_info) {
            startActivity(new Intent(this, GroupDetailActivity.class).putExtra(GroupDetailActivity.EXTRA_GROUP_ID, gid));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void onGroup(FamilyGroup g) {
        if (isDestroyed()) return;
        if (g == null) {
            if (group != null) Toast.makeText(this, R.string.group_gone, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        group = g;
        b.toolbar.setTitle(g.name);
        b.toolbar.setSubtitle(getString(R.string.group_members_count, g.members.size()));
        names.clear();
        for (FamilyGroup.Member m : g.members) names.put(m.uid, m.name);
        adapter.notifyDataSetChanged();
    }

    private void onFeed(List<GroupPost> list, Map<String, Map<String, String>> v) {
        if (isDestroyed()) return;
        boolean atBottom = firstLoad || layout.findLastVisibleItemPosition() >= posts.size() - 2;
        posts.clear();
        posts.addAll(list);
        votes = v;
        adapter.notifyDataSetChanged();
        b.tvFeedEmpty.setVisibility(posts.isEmpty() ? View.VISIBLE : View.GONE);
        if (atBottom && !posts.isEmpty()) b.rvFeed.scrollToPosition(posts.size() - 1);
        firstLoad = false;
        if (!posts.isEmpty()) GroupFeedRepository.markSeen(this, gid, posts.get(posts.size() - 1).at);
    }

    // ---- sending ----------------------------------------------------------------------------

    private void sendText() {
        String text = b.etFeedMessage.getText().toString();
        GroupPost p = new GroupPost();
        p.type = GroupPostRules.TEXT;
        p.text = text;
        if (!GroupPostRules.validNew(p.type, "", text, false, 0, System.currentTimeMillis())) return;
        b.etFeedMessage.setText("");
        GroupFeedRepository.post(this, gid, p, null, error -> runOnUiThread(() -> {
            if (error != null) {
                b.etFeedMessage.setText(text); // nothing is lost: the message is back in the box
                Toast.makeText(this, explain(error), Toast.LENGTH_LONG).show();
            }
        }));
    }

    private void sendPhoto(android.net.Uri uri) {
        if (uri == null) return;
        Toast.makeText(this, R.string.chat_photo_sending, Toast.LENGTH_SHORT).show();
        final android.content.ContentResolver resolver = getContentResolver();
        AppExecutors.io().execute(() -> {
            final String data = ImageCodec.encode(resolver, uri);
            AppExecutors.main().post(() -> {
                if (isDestroyed()) return;
                if (data == null) {
                    Toast.makeText(this, R.string.chat_photo_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                GroupPost p = new GroupPost();
                p.type = GroupPostRules.PHOTO;
                GroupFeedRepository.post(this, gid, p, data, error -> runOnUiThread(() -> {
                    if (error != null) Toast.makeText(this, explain(error), Toast.LENGTH_LONG).show();
                }));
            });
        });
    }

    private void showNewMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, R.string.group_new_trip);
        m.getMenu().add(0, 2, 1, R.string.group_new_party);
        m.getMenu().add(0, 3, 2, R.string.group_new_help);
        m.setOnMenuItemClickListener(i -> {
            String kind = i.getItemId() == 1 ? GroupPostRules.TRIP : i.getItemId() == 2 ? GroupPostRules.PARTY : GroupPostRules.HELP;
            startActivity(new Intent(this, GroupPostEditActivity.class)
                    .putExtra(GroupPostEditActivity.EXTRA_GROUP_ID, gid)
                    .putExtra(GroupPostEditActivity.EXTRA_KIND, kind));
            return true;
        });
        m.show();
    }

    private String explain(String error) {
        switch (error) {
            case "invalid": return getString(R.string.group_post_invalid);
            case "cloud": return getString(R.string.chat_need_cloud);
            case "nodb": return getString(R.string.chat_need_db);
            case "offline": return getString(R.string.chat_offline);
            case "rules": return getString(R.string.chat_rules);
            default: return getString(R.string.group_failed);
        }
    }

    // ---- actions on a post ----------------------------------------------------------------------

    private void castVote(GroupPost p, String tapped) {
        Map<String, String> per = votes.get(p.id);
        String mine = per == null ? null : per.get(myUid);
        GroupFeedRepository.vote(this, gid, p.id, GroupPostRules.nextVote(mine, tapped), error -> runOnUiThread(() -> {
            if (error != null) Toast.makeText(this, explain(error), Toast.LENGTH_SHORT).show();
        }));
    }

    private void confirmDelete(final GroupPost p) {
        boolean admin = group != null && group.isAdmin();
        if (!GroupPostRules.canDelete(myUid, p.uid, admin)) return;
        new AlertDialog.Builder(this)
                .setMessage(R.string.group_post_delete_confirm)
                .setPositiveButton(R.string.trip_delete, (d, w) -> GroupFeedRepository.delete(this, gid, p.id, error ->
                        runOnUiThread(() -> {
                            if (error != null) Toast.makeText(this, explain(error), Toast.LENGTH_SHORT).show();
                        })))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void addToCalendar(GroupPost p) {
        Intent i = new Intent(this, TripEditActivity.class)
                .putExtra(TripEditActivity.EXTRA_START, p.when)
                .putExtra(TripEditActivity.EXTRA_TITLE, p.title);
        if (p.hasLocation) {
            i.putExtra(TripEditActivity.EXTRA_DEST_NAME, p.place.isEmpty() ? p.title : p.place)
                    .putExtra(TripEditActivity.EXTRA_DEST_LAT, p.lat)
                    .putExtra(TripEditActivity.EXTRA_DEST_LON, p.lon);
        }
        startActivity(i);
    }

    private String nameOf(String uid, String fallback) {
        if (uid.equals(myUid)) return getString(R.string.group_you_short);
        String n = names.get(uid);
        if (n != null && !n.isEmpty()) return n;
        return fallback == null || fallback.isEmpty() ? getString(R.string.chat_someone) : fallback;
    }

    // ---- list ---------------------------------------------------------------------------------------

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemGroupPostBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            final GroupPost p = posts.get(position);
            final ItemGroupPostBinding v = h.v;
            String when = DateUtils.formatDateTime(GroupFeedActivity.this, p.at,
                    DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH);
            v.tvPostHead.setText(nameOf(p.uid, p.name) + " · " + when);

            // type label
            String type = null;
            switch (p.type) {
                case GroupPostRules.TRIP: type = getString(R.string.group_type_trip); break;
                case GroupPostRules.PARTY: type = getString(R.string.group_type_party); break;
                case GroupPostRules.HELP: type = getString(R.string.group_type_help) + " · " + helpKind(p.kind); break;
                case GroupPostRules.COMMUNITY: type = getString(R.string.group_type_community); break;
                case GroupPostRules.EMERGENCY: type = getString(R.string.group_type_emergency); break;
                default: break;
            }
            v.tvPostType.setVisibility(type == null ? View.GONE : View.VISIBLE);
            if (type != null) v.tvPostType.setText(type);
            boolean emergency = GroupPostRules.EMERGENCY.equals(p.type);
            v.tvPostType.setTextColor(androidx.core.content.ContextCompat.getColor(GroupFeedActivity.this,
                    emergency ? R.color.locus_danger : R.color.locus_primary));

            v.tvPostTitle.setVisibility(p.title.isEmpty() ? View.GONE : View.VISIBLE);
            v.tvPostTitle.setText(p.title);
            v.tvPostText.setVisibility(p.text.isEmpty() ? View.GONE : View.VISIBLE);
            v.tvPostText.setText(p.text);

            // when / where of a plan
            String line = "";
            if (p.when > 0) {
                line = DateUtils.formatDateTime(GroupFeedActivity.this, p.when, DateUtils.FORMAT_SHOW_TIME
                        | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_ALL);
            }
            if (!p.place.isEmpty()) line += (line.isEmpty() ? "" : " · ") + p.place;
            v.tvPostWhen.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
            v.tvPostWhen.setText(line);

            bindPhoto(v, p);
            bindVotes(v, p);
            bindAction(v, p);
            v.cardPost.setOnLongClickListener(x -> {
                confirmDelete(p);
                return true;
            });
        }

        private void bindPhoto(final ItemGroupPostBinding v, final GroupPost p) {
            if (!p.hasPhoto) {
                v.ivPostPhoto.setVisibility(View.GONE);
                return;
            }
            v.ivPostPhoto.setVisibility(View.VISIBLE);
            v.ivPostPhoto.setTag(p.id);
            Bitmap cached = photos.get(p.id);
            if (cached != null) {
                v.ivPostPhoto.setImageBitmap(cached);
                return;
            }
            v.ivPostPhoto.setImageDrawable(null);
            v.ivPostPhoto.setMinimumHeight(160);
            GroupFeedRepository.loadPhoto(GroupFeedActivity.this, gid, p.id, bmp -> runOnUiThread(() -> {
                if (bmp == null || isDestroyed()) return;
                photos.put(p.id, bmp);
                if (p.id.equals(v.ivPostPhoto.getTag())) v.ivPostPhoto.setImageBitmap(bmp);
            }));
        }

        private void bindVotes(ItemGroupPostBinding v, final GroupPost p) {
            if (!GroupPostRules.canVote(p.type)) {
                v.llVotes.setVisibility(View.GONE);
                v.tvVoters.setVisibility(View.GONE);
                return;
            }
            boolean help = GroupPostRules.HELP.equals(p.type);
            String lYes = getString(help ? R.string.vote_help_yes : R.string.vote_going);
            String lMaybe = getString(R.string.vote_maybe);
            String lNo = getString(help ? R.string.vote_help_no : R.string.vote_not_going);
            Map<String, String> per = votes.get(p.id);
            if (per == null) per = new HashMap<>();
            int[] c = GroupPostRules.tally(per.values());
            String mine = per.get(myUid);
            v.llVotes.setVisibility(View.VISIBLE);
            styleVote(v.btnVoteYes, lYes, c[0], GroupPostRules.YES.equals(mine));
            styleVote(v.btnVoteMaybe, lMaybe, c[1], GroupPostRules.MAYBE.equals(mine));
            styleVote(v.btnVoteNo, lNo, c[2], GroupPostRules.NO.equals(mine));
            v.btnVoteYes.setOnClickListener(x -> castVote(p, GroupPostRules.YES));
            v.btnVoteMaybe.setOnClickListener(x -> castVote(p, GroupPostRules.MAYBE));
            v.btnVoteNo.setOnClickListener(x -> castVote(p, GroupPostRules.NO));

            StringBuilder who = new StringBuilder();
            appendVoters(who, lYes, per, GroupPostRules.YES);
            appendVoters(who, lMaybe, per, GroupPostRules.MAYBE);
            appendVoters(who, lNo, per, GroupPostRules.NO);
            v.tvVoters.setVisibility(who.length() == 0 ? View.GONE : View.VISIBLE);
            v.tvVoters.setText(who.toString().trim());
        }

        private void styleVote(android.widget.Button btn, String label, int count, boolean selected) {
            btn.setText((selected ? "✓ " : "") + label + " (" + count + ")");
            btn.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            btn.setAlpha(selected ? 1f : 0.75f);
        }

        private void appendVoters(StringBuilder sb, String label, Map<String, String> per, String which) {
            List<String> who = new ArrayList<>();
            for (Map.Entry<String, String> e : per.entrySet()) if (which.equals(e.getValue())) who.add(nameOf(e.getKey(), ""));
            if (who.isEmpty()) return;
            String shown = who.size() > 6 ? android.text.TextUtils.join(", ", who.subList(0, 6)) + " +" + (who.size() - 6)
                    : android.text.TextUtils.join(", ", who);
            sb.append(getString(R.string.group_voters, label, shown)).append('\n');
        }

        private void bindAction(ItemGroupPostBinding v, final GroupPost p) {
            v.btnPostAction.setVisibility(View.GONE);
            if (GroupPostRules.TRIP.equals(p.type) && p.when > System.currentTimeMillis()) {
                v.btnPostAction.setVisibility(View.VISIBLE);
                v.btnPostAction.setText(R.string.group_add_to_calendar);
                v.btnPostAction.setOnClickListener(x -> addToCalendar(p));
            } else if (p.hasLocation && (GroupPostRules.EMERGENCY.equals(p.type) || GroupPostRules.COMMUNITY.equals(p.type)
                    || GroupPostRules.PARTY.equals(p.type) || GroupPostRules.HELP.equals(p.type))) {
                v.btnPostAction.setVisibility(View.VISIBLE);
                v.btnPostAction.setText(R.string.group_show_location);
                v.btnPostAction.setOnClickListener(x -> ExternalIntents.openMaps(GroupFeedActivity.this, p.lat, p.lon,
                        p.title.isEmpty() ? p.name : p.title));
            }
        }

        private String helpKind(String k) {
            switch (k == null ? "" : k) {
                case GroupPostRules.HELP_DONATION: return getString(R.string.group_help_donation);
                case GroupPostRules.HELP_VOLUNTEER: return getString(R.string.group_help_volunteer);
                case GroupPostRules.HELP_BLOOD: return getString(R.string.group_help_blood);
                default: return getString(R.string.group_help_other);
            }
        }

        @Override
        public int getItemCount() {
            return posts.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ItemGroupPostBinding v;

            Holder(ItemGroupPostBinding v) {
                super(v.getRoot());
                this.v = v;
            }
        }
    }
}
