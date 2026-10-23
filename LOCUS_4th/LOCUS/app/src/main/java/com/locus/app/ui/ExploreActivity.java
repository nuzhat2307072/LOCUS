package com.locus.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.chip.Chip;
import com.google.android.material.snackbar.Snackbar;
import com.locus.app.R;
import com.locus.app.data.db.LocusDatabase;
import com.locus.app.data.provider.FavoritesContract;
import com.locus.app.data.sqlite.LocusSqlHelper;
import com.locus.app.databinding.ActivityExploreBinding;
import com.locus.app.model.Place;
import com.locus.app.net.HttpClient;
import com.locus.app.net.PlaceSearch;
import com.locus.app.net.QueryPlanner;
import com.locus.app.ui.adapter.PlacesAdapter;
import com.locus.app.util.AppExecutors;
import com.locus.app.util.AppPrefs;
import com.locus.app.util.GeoUtils;
import com.locus.app.util.LocationHelper;
import com.locus.app.util.PermissionHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Explore Places: type ANYTHING (a food, a shop, a service, a brand) and LOCUS lists the places near
 * you that offer it. GPS -> query planner -> Overpass + Nominatim (in parallel) -> JSON/XML parsing ->
 * RecyclerView, with a Room cache so a repeated search shows instantly.
 */
public class ExploreActivity extends AppCompatActivity {
    private static final String STATE_QUERY = "query";

    private ActivityExploreBinding b;
    private PlacesAdapter adapter;
    private LocusSqlHelper sql;

    private double lat;
    private double lon;
    /** 0 = default city centre, 1 = last known position, 2 = fresh GPS fix this visit */
    private int locSource;
    private boolean locatedThisVisit;
    private int loadToken;
    private String currentQuery = "";
    private final Map<String, List<Place>> memory = new HashMap<>();

    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), r -> {
                if (PermissionHelper.hasLocation(this)) locateInBackground();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityExploreBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        sql = new LocusSqlHelper(this);
        double[] last = AppPrefs.lastLocation(this);
        lat = last[0];
        lon = last[1];
        locSource = AppPrefs.hasLastLocation(this) ? 1 : 0;

        adapter = new PlacesAdapter(new PlacesAdapter.Listener() {
            @Override
            public void onOpen(Place p) {
                Intent i = new Intent(ExploreActivity.this, PlaceDetailActivity.class);
                i.putExtra(PlaceDetailActivity.EXTRA_PLACE, p); // passing data between activities
                startActivity(i);
            }

            @Override
            public void onNavigate(Place p) {
                startActivity(NavigationActivity.intentTo(ExploreActivity.this, p.lat, p.lon, p.name));
            }

            @Override
            public void onSave(Place p) {
                FavoritesContract.save(ExploreActivity.this, p, () ->
                        Snackbar.make(b.getRoot(), p.name + " saved", Snackbar.LENGTH_SHORT).show());
            }
        });
        b.recycler.setLayoutManager(new LinearLayoutManager(this));
        b.recycler.setAdapter(adapter);
        b.swipe.setColorSchemeResources(R.color.locus_primary);
        b.swipe.setOnRefreshListener(() -> {
            if (currentQuery.isEmpty()) b.swipe.setRefreshing(false);
            else search(currentQuery);
        });

        b.etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                search(String.valueOf(b.etSearch.getText()));
                return true;
            }
            return false;
        });
        b.btnRetry.setOnClickListener(v -> {
            if (!currentQuery.isEmpty()) search(currentQuery);
        });
        b.btnClearRecent.setOnClickListener(v -> {
            AppExecutors.io().execute(() -> sql.clearExploreHistory());
            b.recentBox.setVisibility(View.GONE);
        });

        refreshRecent();
        AppExecutors.io().execute(() -> LocusDatabase.get(this).placeDao()
                .deleteOlderThan(System.currentTimeMillis() - 7L * 24 * 3600 * 1000));

        if (PermissionHelper.hasLocation(this)) {
            locateInBackground();
        } else {
            permissionLauncher.launch(PermissionHelper.locationPermissions());
        }

        if (savedInstanceState != null) { // screen rotated: bring the last search back
            String q = savedInstanceState.getString(STATE_QUERY, "");
            if (!q.isEmpty()) {
                b.etSearch.setText(q);
                search(q);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_QUERY, currentQuery);
    }

    /** Fresh GPS fix once per visit; the result only moves the reference point for later searches. */
    private void locateInBackground() {
        LocationHelper.getLocation(this, loc -> {
            if (isDestroyed()) return;
            applyLocation(loc);
        });
    }

    private void applyLocation(android.location.Location loc) {
        locatedThisVisit = true;
        if (loc != null) {
            lat = loc.getLatitude();
            lon = loc.getLongitude();
            locSource = 2;
        }
        updateAreaLabel();
    }

    private void updateAreaLabel() {
        if (currentQuery.isEmpty()) return;
        b.tvArea.setText(locSource == 2 ? R.string.area_gps
                : locSource == 1 ? R.string.area_last : R.string.area_default);
        b.tvArea.setVisibility(View.VISIBLE);
    }

    // ---- searching -----------------------------------------------------------------------------

    private void search(String raw) {
        final String key = QueryPlanner.normalize(raw);
        if (key.length() < 2) {
            adapter.submitList(Collections.<Place>emptyList());
            showMessage(getString(R.string.explore_hint), false);
            b.swipe.setRefreshing(false);
            return;
        }
        hideKeyboard();
        currentQuery = key;
        final int token = ++loadToken;
        b.swipe.setRefreshing(true);

        AppExecutors.io().execute(() -> sql.addExploreHistory(key));
        refreshRecent();

        showCached(key, token); // instant content while the fresh answer loads
        if (locatedThisVisit || !PermissionHelper.hasLocation(this)) {
            updateAreaLabel();
            fetch(key, token);
        } else {
            // the first fix of this visit is still on its way (at most ~6 seconds)
            LocationHelper.getLocation(this, loc -> {
                if (isDestroyed()) return;
                applyLocation(loc);
                if (token == loadToken) fetch(key, token);
            });
        }
    }

    /** Memory first, then the Room cache - only used while the network result is still on its way. */
    private void showCached(final String key, final int token) {
        List<Place> mem = memory.get(key);
        if (mem != null) {
            show(mem, false);
            return;
        }
        adapter.submitList(Collections.<Place>emptyList());
        hideMessage();
        AppExecutors.io().execute(() -> {
            final List<Place> cached = LocusDatabase.get(this).placeDao().getBySearch(key);
            AppExecutors.main().post(() -> {
                if (isDestroyed() || token != loadToken || cached.isEmpty()) return;
                if (adapter.getItemCount() == 0) show(cached, false);
            });
        });
    }

    private void fetch(final String key, final int token) {
        final double fLat = lat;
        final double fLon = lon;
        AppExecutors.io().execute(() -> {
            try {
                final List<Place> places = PlaceSearch.search(key, fLat, fLon, partial ->
                        AppExecutors.main().post(() -> {
                            // first answers appear while the slower services are still working
                            if (!isDestroyed() && token == loadToken) show(partial, false);
                        }));
                LocusDatabase.get(this).placeDao().replaceSearch(key, places);
                AppExecutors.main().post(() -> {
                    if (isDestroyed() || token != loadToken) return;
                    b.swipe.setRefreshing(false);
                    memory.put(key, places);
                    show(places, true);
                });
            } catch (Exception e) {
                final String message = HttpClient.friendlyMessage(this, e);
                final List<Place> saved = savedMatches(key);
                AppExecutors.main().post(() -> {
                    if (isDestroyed() || token != loadToken) return;
                    b.swipe.setRefreshing(false);
                    if (adapter.getItemCount() > 0) {
                        Snackbar.make(b.getRoot(), R.string.cached_places, Snackbar.LENGTH_LONG).show();
                    } else if (!saved.isEmpty()) {
                        show(saved, false);
                        Snackbar.make(b.getRoot(), R.string.cached_places, Snackbar.LENGTH_LONG).show();
                    } else {
                        showMessage(message, true);
                    }
                });
            }
        });
    }

    /** Offline fallback: places kept from earlier searches that match the text (one row per place). */
    private List<Place> savedMatches(String key) {
        try {
            List<Place> rows = LocusDatabase.get(this).placeDao().searchCached("%" + key + "%");
            Map<String, Place> unique = new HashMap<>();
            for (Place p : rows) unique.put(p.name + "|" + Math.round(p.lat * 10000) + "|" + Math.round(p.lon * 10000), p);
            return new ArrayList<>(unique.values());
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void showMessage(String text, boolean withRetry) {
        b.tvMessage.setText(text);
        b.btnRetry.setVisibility(withRetry ? View.VISIBLE : View.GONE);
        b.messageBox.setVisibility(View.VISIBLE);
    }

    private void hideMessage() {
        b.messageBox.setVisibility(View.GONE);
    }

    /** Distances are recomputed from the current position, so cached rows are never stale. */
    private void show(List<Place> places, boolean isFinal) {
        List<Place> fresh = new ArrayList<>(places.size());
        for (Place p : places) {
            Place c = new Place();
            c.id = p.id;
            c.searchKey = p.searchKey;
            c.name = p.name;
            c.category = p.category;
            c.lat = p.lat;
            c.lon = p.lon;
            c.phone = p.phone;
            c.address = p.address;
            c.openingHours = p.openingHours;
            c.fetchedAt = p.fetchedAt;
            c.distanceMeters = GeoUtils.distance(lat, lon, p.lat, p.lon);
            fresh.add(c);
        }
        Collections.sort(fresh, (x, y) -> Double.compare(x.distanceMeters, y.distanceMeters));
        adapter.submitList(fresh);
        if (fresh.isEmpty() && isFinal) {
            showMessage(getString(R.string.explore_nothing, currentQuery), true);
        } else {
            hideMessage();
        }
    }

    // ---- recent searches -----------------------------------------------------------------------

    private void refreshRecent() {
        AppExecutors.io().execute(() -> {
            final List<String> recent = sql.recentExploreHistory(8);
            AppExecutors.main().post(() -> {
                if (isDestroyed()) return;
                b.recentChips.removeAllViews();
                for (String q : recent) {
                    Chip chip = new Chip(this);
                    chip.setText(q);
                    chip.setOnClickListener(v -> {
                        b.etSearch.setText(q);
                        b.etSearch.setSelection(q.length());
                        search(q);
                    });
                    b.recentChips.addView(chip);
                }
                b.recentBox.setVisibility(recent.isEmpty() ? View.GONE : View.VISIBLE);
            });
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(b.etSearch.getWindowToken(), 0);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sql.close();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
