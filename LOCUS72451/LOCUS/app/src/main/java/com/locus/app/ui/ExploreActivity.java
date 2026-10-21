package com.locus.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.chip.Chip;
import com.google.android.material.snackbar.Snackbar;
import com.locus.app.R;
import com.locus.app.data.db.LocusDatabase;
import com.locus.app.data.provider.FavoritesContract;
import com.locus.app.databinding.ActivityExploreBinding;
import com.locus.app.model.Place;
import com.locus.app.model.PlaceCategory;
import com.locus.app.net.HttpClient;
import com.locus.app.net.NominatimApi;
import com.locus.app.net.OverpassApi;
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
 * Explore Places: GPS -> Overpass / Nominatim API -> JSON/XML parsing -> RecyclerView.
 *
 * Built to feel instant: whatever was saved last time (memory, then the Room cache) is shown
 * immediately while the fresh results load in the background, with automatic Nominatim fallback.
 */
public class ExploreActivity extends AppCompatActivity {
    private static final int RADIUS_M = 3000;

    private ActivityExploreBinding b;
    private PlacesAdapter adapter;
    private PlaceCategory current = PlaceCategory.PIZZA;
    private double lat;
    private double lon;
    private int loadToken;
    private final Map<String, List<Place>> memory = new HashMap<>();

    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), r -> locateAndLoad());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityExploreBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        double[] last = AppPrefs.lastLocation(this);
        lat = last[0];
        lon = last[1];

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
        b.swipe.setOnRefreshListener(this::locateAndLoad);

        setupSearch();
        buildChips();

        showCached(current, ++loadToken); // instant content from the last visit
        if (PermissionHelper.hasLocation(this)) {
            locateAndLoad();
        } else {
            permissionLauncher.launch(PermissionHelper.locationPermissions());
        }
    }

    private void setupSearch() {
        b.etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch();
                return true;
            }
            return false;
        });

        b.tilSearch.setEndIconOnClickListener(v -> {
            b.etSearch.setText("");
            performSearch();
        });
    }

    private void performSearch() {
        String query = b.etSearch.getText() != null ? b.etSearch.getText().toString().trim() : "";
        int token = ++loadToken;
        if (query.isEmpty()) {
            fetch(current, token);
        } else {
            b.chipGroup.clearCheck();
            fetchByQuery(query, token);
        }
    }

    private void buildChips() {
        for (PlaceCategory c : PlaceCategory.values()) {
            Chip chip = new Chip(this);
            chip.setText(c.label);
            chip.setCheckable(true);
            chip.setTag(c);
            chip.setId(View.generateViewId());
            b.chipGroup.addView(chip);
            if (c == current) chip.setChecked(true);
        }
        b.chipGroup.setOnCheckedStateChangeListener((group, ids) -> {
            if (ids.isEmpty()) return;
            Chip chip = group.findViewById(ids.get(0));
            current = (PlaceCategory) chip.getTag();
            b.etSearch.setText("");
            int token = ++loadToken;
            showCached(current, token); // no waiting: show what we have, refresh behind it
            fetch(current, token);
        });
    }

    /** New GPS fix, then fetch. Falls back to the last known position when GPS is slow or off. */
    private void locateAndLoad() {
        final int token = ++loadToken;
        b.swipe.setRefreshing(true);
        LocationHelper.getLocation(this, loc -> {
            if (isDestroyed()) return;
            if (loc != null) { // always keep the newest position, even if the user already switched category
                lat = loc.getLatitude();
                lon = loc.getLongitude();
            }
            if (token == loadToken) {
                String query = b.etSearch.getText() != null ? b.etSearch.getText().toString().trim() : "";
                if (!query.isEmpty()) {
                    fetchByQuery(query, token);
                } else {
                    fetch(current, token);
                }
            }
        });
    }

    /** Memory first, then the Room cache - only used while the network result is still on its way. */
    private void showCached(final PlaceCategory category, final int token) {
        List<Place> mem = memory.get(category.name());
        if (mem != null) {
            show(mem, false);
            return;
        }
        adapter.submitList(Collections.<Place>emptyList());
        b.tvMessage.setVisibility(View.GONE);
        AppExecutors.io().execute(() -> {
            final List<Place> cached = LocusDatabase.get(this).placeDao().getByCategory(category.name());
            AppExecutors.main().post(() -> {
                if (isDestroyed() || token != loadToken || cached.isEmpty()) return;
                if (adapter.getItemCount() == 0) show(cached, false);
            });
        });
    }

    private void fetch(final PlaceCategory category, final int token) {
        b.swipe.setRefreshing(true);
        HttpClient.async(() -> {
            List<Place> places;
            try {
                places = OverpassApi.searchPlaces(lat, lon, category, RADIUS_M);
            } catch (Exception e) {
                places = new ArrayList<>();
            }
            if (places.isEmpty()) {
                try {
                    places = NominatimApi.searchPlacesNearby(category.label, lat, lon);
                } catch (Exception ignored) {
                }
            }
            return places;
        }, new HttpClient.Callback<List<Place>>() {
            @Override
            public void onSuccess(List<Place> places) {
                if (isDestroyed() || token != loadToken) return;
                b.swipe.setRefreshing(false);
                if (!places.isEmpty()) {
                    memory.put(category.name(), places);
                    AppExecutors.io().execute(() ->
                            LocusDatabase.get(ExploreActivity.this).placeDao().replaceCategory(category.name(), places));
                }
                show(places, true);
            }

            @Override
            public void onError(String message) {
                if (isDestroyed() || token != loadToken) return;
                b.swipe.setRefreshing(false);
                if (adapter.getItemCount() > 0) {
                    Snackbar.make(b.getRoot(), R.string.cached_places, Snackbar.LENGTH_LONG).show();
                } else {
                    b.tvMessage.setText(message);
                    b.tvMessage.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void fetchByQuery(final String query, final int token) {
        b.swipe.setRefreshing(true);
        HttpClient.async(() -> {
            List<Place> places;
            try {
                places = OverpassApi.searchPlacesByQuery(lat, lon, query, RADIUS_M);
            } catch (Exception e) {
                places = new ArrayList<>();
            }
            if (places.isEmpty()) {
                try {
                    places = NominatimApi.searchPlacesNearby(query, lat, lon);
                } catch (Exception ignored) {
                }
            }
            return places;
        }, new HttpClient.Callback<List<Place>>() {
            @Override
            public void onSuccess(List<Place> places) {
                if (isDestroyed() || token != loadToken) return;
                b.swipe.setRefreshing(false);
                show(places, true);
            }

            @Override
            public void onError(String message) {
                if (isDestroyed() || token != loadToken) return;
                b.swipe.setRefreshing(false);
                b.tvMessage.setText("No places found for \"" + query + "\"");
                b.tvMessage.setVisibility(View.VISIBLE);
            }
        });
    }

    /** Distances are recomputed from the current position, so cached rows are never stale. */
    private void show(List<Place> places, boolean isFinal) {
        List<Place> fresh = new ArrayList<>(places.size());
        for (Place p : places) {
            Place c = new Place();
            c.id = p.id;
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
            b.tvMessage.setText(R.string.no_places);
            b.tvMessage.setVisibility(View.VISIBLE);
        } else {
            b.tvMessage.setVisibility(View.GONE);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
