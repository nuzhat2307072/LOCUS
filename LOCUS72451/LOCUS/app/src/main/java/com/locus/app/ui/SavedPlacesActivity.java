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

import com.locus.app.data.provider.FavoritesContract;
import com.locus.app.databinding.ActivitySavedPlacesBinding;
import com.locus.app.databinding.ItemFavoriteBinding;
import com.locus.app.model.PlaceCategory;
import com.locus.app.util.AppExecutors;

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
    }

    private ActivitySavedPlacesBinding b;
    private FavAdapter adapter;

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
                        list.add(f);
                    }
                }
            }
            AppExecutors.main().post(() -> {
                adapter.items = list;
                adapter.notifyDataSetChanged();
                b.tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void editNote(Favorite f) {
        final EditText input = new EditText(this);
        input.setText(f.note);
        input.setHint("Note (e.g. open late, good coffee)");
        new AlertDialog.Builder(this)
                .setTitle(f.name)
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
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
            h.b.tvMeta.setText(PlaceCategory.fromName(f.category).label);
            h.b.tvNote.setText(f.note == null || f.note.isEmpty() ? "No note" : f.note);
            h.b.btnNav.setOnClickListener(v ->
                    startActivity(NavigationActivity.intentTo(SavedPlacesActivity.this, f.lat, f.lon, f.name)));
            h.b.btnEdit.setOnClickListener(v -> editNote(f));
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
