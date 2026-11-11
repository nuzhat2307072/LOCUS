package com.locus.app.ui;

import android.content.ContentUris;
import android.content.ContentValues;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.locus.app.R;
import com.locus.app.data.provider.FavoritesContract;
import com.locus.app.databinding.ActivitySavedPlacesBinding;
import com.locus.app.databinding.ItemFavoriteBinding;
import com.locus.app.util.AppExecutors;
import com.locus.app.util.Labels;

import java.util.ArrayList;
import java.util.List;
import android.os.Handler;
import android.os.Looper;

/**
 * Saved places. All data access goes through the FavoritesProvider Content Provider using a
 * ContentResolver (query / update / delete), and a ContentObserver refreshes the list on changes.
 */
public class SavedPlacesActivity extends AppCompatActivity {

    private static class Favorite {
        long id;
        String name;
        String category;
        double lat;
        double lon;
        String note;
        String list = "";
    }

    private ActivitySavedPlacesBinding b;
    private FavAdapter adapter;
    private List<Favorite> all = new ArrayList<>();
    /** null = every place, otherwise the list key being shown ("" = places without a list). */
    private String shownList;

    private final ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            load();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivitySavedPlacesBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        adapter = new FavAdapter();
        b.recycler.setLayoutManager(new LinearLayoutManager(this));
        b.recycler.setAdapter(adapter);
        // Search works offline: it only filters what is saved on the phone (name, kind of place, note).
        b.etFilter.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int c, int d) {
                applyFilter();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });
    }

    private void applyFilter() {
        String q = b.etFilter.getText() == null ? "" : b.etFilter.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
        List<Favorite> shown = new ArrayList<>();
        for (Favorite f : all) {
            String hay = ((f.name == null ? "" : f.name) + " " + Labels.category(this, f.category) + " "
                    + (f.category == null ? "" : f.category) + " " + (f.note == null ? "" : f.note))
                    .toLowerCase(java.util.Locale.ROOT);
            boolean inList = shownList == null || shownList.equals(f.list);
            if (inList && (q.isEmpty() || hay.contains(q))) shown.add(f);
        }
        adapter.items = shown;
        adapter.notifyDataSetChanged();
        b.tvEmpty.setText(all.isEmpty() ? R.string.no_saved
                : q.isEmpty() ? R.string.saved_list_empty : R.string.saved_no_match);
        b.tvEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        getContentResolver().registerContentObserver(FavoritesContract.CONTENT_URI, true, observer);
        load();
    }

    @Override
    protected void onStop() {
        getContentResolver().unregisterContentObserver(observer);
        super.onStop();
    }

    private void load() {
        AppExecutors.io().execute(() -> {
            List<Favorite> list = new ArrayList<>();
            try (Cursor c = getContentResolver().query(FavoritesContract.CONTENT_URI, null, null, null, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        Favorite f = new Favorite();
                        f.id = c.getLong(c.getColumnIndexOrThrow(FavoritesContract.COL_ID));
                        f.name = c.getString(c.getColumnIndexOrThrow(FavoritesContract.COL_NAME));
                        f.category = c.getString(c.getColumnIndexOrThrow(FavoritesContract.COL_CATEGORY));
                        f.lat = c.getDouble(c.getColumnIndexOrThrow(FavoritesContract.COL_LAT));
                        f.lon = c.getDouble(c.getColumnIndexOrThrow(FavoritesContract.COL_LON));
                        f.note = c.getString(c.getColumnIndexOrThrow(FavoritesContract.COL_NOTE));
                        int li = c.getColumnIndex(FavoritesContract.COL_LIST);
                        f.list = li < 0 || c.isNull(li) ? "" : c.getString(li);
                        list.add(f);
                    }
                }
            }
            AppExecutors.main().post(() -> {
                all = list;
                buildListChips();
                applyFilter();
            });
        });
    }

    // ---- lists (Home, Work, your own) and sharing -------------------------------------------------

    private String listLabel(String key) {
        if (FavoritesContract.LIST_HOME.equals(key)) return getString(R.string.list_home);
        if (FavoritesContract.LIST_WORK.equals(key)) return getString(R.string.list_work);
        return key;
    }

    /** All lists that can be chosen: Home, Work and every list name already used. */
    private List<String> knownLists() {
        List<String> keys = new ArrayList<>();
        keys.add(FavoritesContract.LIST_HOME);
        keys.add(FavoritesContract.LIST_WORK);
        java.util.TreeSet<String> custom = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Favorite f : all) {
            if (!f.list.isEmpty() && !keys.contains(f.list)) custom.add(f.list);
        }
        keys.addAll(custom);
        return keys;
    }

    private void buildListChips() {
        b.chipLists.setOnCheckedStateChangeListener(null);
        b.chipLists.removeAllViews();
        addChip(getString(R.string.list_all), null);
        for (String key : knownLists()) addChip(listLabel(key), key);
        com.google.android.material.chip.Chip add = new com.google.android.material.chip.Chip(this);
        add.setText(R.string.list_new);
        add.setCheckable(false);
        add.setOnClickListener(v -> askListName(null));
        b.chipLists.addView(add);
        b.chipLists.setOnCheckedStateChangeListener((group, ids) -> {
            shownList = ids.isEmpty() ? null : (String) group.findViewById(ids.get(0)).getTag();
            applyFilter();
        });
    }

    private void addChip(String label, String key) {
        com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(this);
        chip.setId(View.generateViewId());
        chip.setText(label);
        chip.setTag(key);
        chip.setCheckable(true);
        b.chipLists.addView(chip);
        boolean mine = key == null ? shownList == null : key.equals(shownList);
        if (mine) chip.setChecked(true);
    }

    private void showOptions(Favorite f) {
        String[] items = {getString(R.string.saved_edit_note), getString(R.string.saved_change_list),
                getString(R.string.action_share)};
        new AlertDialog.Builder(this)
                .setTitle(f.name)
                .setItems(items, (d, which) -> {
                    if (which == 0) editNote(f);
                    else if (which == 1) chooseList(f);
                    else sharePlace(f);
                })
                .show();
    }

    private void chooseList(Favorite f) {
        final List<String> keys = knownLists();
        final List<String> labels = new ArrayList<>();
        for (String k : keys) labels.add(listLabel(k));
        labels.add(getString(R.string.list_new));
        labels.add(getString(R.string.list_none));
        new AlertDialog.Builder(this)
                .setTitle(R.string.saved_change_list)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which < keys.size()) setList(f, keys.get(which));
                    else if (which == keys.size()) askListName(f);
                    else setList(f, "");
                })
                .show();
    }

    /** A new list; when a place is given it is put into it straight away. */
    private void askListName(final Favorite f) {
        final EditText input = new EditText(this);
        input.setHint(R.string.list_name_hint);
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.list_new)
                .setView(input)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    String name = input.getText().toString().trim().replace('@', ' ').trim();
                    if (name.isEmpty()) return;
                    if (name.length() > 24) name = name.substring(0, 24);
                    if (f != null) setList(f, name);
                    else {
                        shownList = name; // an empty list is shown at once; it appears as a chip after the first place
                        applyFilter();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setList(final Favorite f, final String key) {
        AppExecutors.io().execute(() -> {
            ContentValues v = new ContentValues();
            v.put(FavoritesContract.COL_LIST, key);
            getContentResolver().update(ContentUris.withAppendedId(FavoritesContract.CONTENT_URI, f.id), v, null, null);
        });
    }

    private void sharePlace(Favorite f) {
        String text = getString(R.string.share_place_text, f.name,
                com.locus.app.util.ShareLinks.placeUrl(f.lat, f.lon));
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text);
        com.locus.app.util.ExternalIntents.start(this,
                android.content.Intent.createChooser(send, getString(R.string.action_share)));
    }

    private void editNote(Favorite f) {
        final EditText input = new EditText(this);
        input.setText(f.note);
        input.setHint(R.string.note_hint);
        new AlertDialog.Builder(this)
                .setTitle(f.name)
                .setView(input)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    final String note = input.getText().toString().trim();
                    AppExecutors.io().execute(() -> {
                        ContentValues v = new ContentValues();
                        v.put(FavoritesContract.COL_NOTE, note);
                        Uri uri = ContentUris.withAppendedId(FavoritesContract.CONTENT_URI, f.id);
                        getContentResolver().update(uri, v, null, null); // UPDATE through the provider
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void delete(Favorite f) {
        AppExecutors.io().execute(() -> {
            Uri uri = ContentUris.withAppendedId(FavoritesContract.CONTENT_URI, f.id);
            getContentResolver().delete(uri, null, null); // DELETE through the provider
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private class FavAdapter extends RecyclerView.Adapter<FavAdapter.Holder> {
        List<Favorite> items = new ArrayList<>();

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemFavoriteBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Favorite f = items.get(position);
            h.b.tvName.setText(f.name);
            String kind = Labels.category(SavedPlacesActivity.this, f.category);
            h.b.tvMeta.setText(f.list.isEmpty() ? kind : kind + "  ·  " + listLabel(f.list));
            h.b.tvNote.setText(f.note == null || f.note.isEmpty() ? getString(R.string.no_note) : f.note);
            h.b.btnNav.setOnClickListener(v ->
                    startActivity(NavigationActivity.intentTo(SavedPlacesActivity.this, f.lat, f.lon, f.name)));
            h.b.btnEdit.setOnClickListener(v -> showOptions(f));
            h.b.btnDelete.setOnClickListener(v -> delete(f));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ItemFavoriteBinding b;

            Holder(ItemFavoriteBinding b) {
                super(b.getRoot());
                this.b = b;
            }
        }
    }
}
