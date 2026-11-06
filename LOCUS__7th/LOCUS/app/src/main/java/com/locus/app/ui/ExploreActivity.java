package com.locus.app.ui;

import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.gms.common.api.ResolvableApiException;
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
import com.locus.app.net.Relevance;
import com.locus.app.ui.adapter.PlacesAdapter;
import com.locus.app.util.AppExecutors;
import com.locus.app.util.AppPrefs;
import com.locus.app.util.GeoUtils;
import com.locus.app.util.LiveLocation;
import com.locus.app.util.PermissionHelper;

import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Explore Places: type ANYTHING (a food, a shop, a service, a brand) and LOCUS lists the places near
 * you that offer it.
 *
 *  - Position: a live, high-accuracy GPS feed (not a one-off coarse guess). A search waits a few seconds for a
 *    good fix, the screen says how accurate it is, and results refresh by themselves when you move.
 *  - Search: Overpass + Nominatim + Photon in widening rings (see PlaceSearch); every result is scored by how
 *    well it matches ("is that kind of place" beats "has the word in its name").
 *  - View: best-match or nearest order, "open now", distance filters, and a map of the results.
 */
public class ExploreActivity extends AppCompatActivity {
    private static final String STATE_QUERY = "query";
    private static final String STATE_NEAREST = "nearest";
    private static final String STATE_KM = "km";

    /** A fix at least this accurate (metres) is good enough to start a search with. */
    private static final float GOOD_ACCURACY_M = 100f;
    /** Waiting for a good fix never takes longer than this - the best available position is used then. */
    private static final long WAIT_FOR_FIX_MS = 9000;
    private static final long FRESH_FIX_MS = 30_000;
    private static final long CACHE_MS = 3 * 60_000L;
    private static final int MAX_MAP_MARKERS = 40;

    private ActivityExploreBinding b;
    private PlacesAdapter adapter;
    private LocusSqlHelper sql;
    private LiveLocation live;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // position used for searching and distances
    private double lat;
    private double lon;
    private float accuracy = -1f;
    /** 0 = default city centre, 1 = last known (old) position, 2 = live GPS fix */
    private int locSource;
    private boolean askedSettings;

    // search state
    private int loadToken;
    private String currentQuery = "";
    private boolean pendingSearch;      // waiting for a good fix before searching
    private boolean hasSearched;
    private double searchedLat;
    private double searchedLon;
    private float searchedAccuracy;
    private long lastAutoRefresh;
    private List<Place> rawResults = new ArrayList<>();
    private final Map<String, CacheEntry> memory = new HashMap<>();

    // view state
    private boolean sortNearest;
    private int maxKm;                  // 0 = any distance
    private boolean showMap;
    private MenuItem mapItem;

    // map
    private MapView map;
    private Marker meMarker;
    private Polygon accuracyCircle;
    private final List<Marker> placeMarkers = new ArrayList<>();
    private boolean fitPending;

    private static final class CacheEntry {
        final List<Place> places;
        final long at;

        CacheEntry(List<Place> places, long at) {
            this.places = places;
            this.at = at;
        }
    }

    private final Runnable releaseSearch = this::releasePending;

    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), r -> {
                live.start();
                updateAreaLabel();
            });

    private final ActivityResultLauncher<IntentSenderRequest> settingsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartIntentSenderForResult(), r -> live.start());

    private final PlacesAdapter.Listener placeListener = new PlacesAdapter.Listener() {
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
                    Snackbar.make(b.getRoot(), p.name + " ★", Snackbar.LENGTH_SHORT).show());
        }
    };

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

        live = new LiveLocation(this, new LiveLocation.Listener() {
            @Override
            public void onLocation(Location location) {
                onFix(location);
            }

            @Override
            public void onState(LiveLocation.State state) {
                updateAreaLabel();
                if (pendingSearch && (state == LiveLocation.State.GPS_OFF
                        || state == LiveLocation.State.NO_PERMISSION)) {
                    releasePending(); // no GPS to wait for: search around the best known position
                }
            }

            @Override
            public void onSettingsResolution(ResolvableApiException e) {
                if (askedSettings) return;
                askedSettings = true;
                try {
                    settingsLauncher.launch(new IntentSenderRequest.Builder(e.getResolution()).build());
                } catch (RuntimeException ignored) {
                    updateAreaLabel();
                }
            }
        });

        adapter = new PlacesAdapter(placeListener);
        b.recycler.setLayoutManager(new LinearLayoutManager(this));
        b.recycler.setAdapter(adapter);
        b.swipe.setColorSchemeResources(R.color.locus_primary);
        b.swipe.setOnRefreshListener(() -> {
            if (currentQuery.isEmpty()) b.swipe.setRefreshing(false);
            else search(currentQuery, true);
        });

        b.etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                search(String.valueOf(b.etSearch.getText()), false);
                return true;
            }
            return false;
        });
        b.btnRetry.setOnClickListener(v -> {
            if (!currentQuery.isEmpty()) search(currentQuery, true);
        });
        b.btnClearRecent.setOnClickListener(v -> {
            AppExecutors.io().execute(() -> sql.clearExploreHistory());
            b.recentBox.setVisibility(View.GONE);
        });
        b.tvArea.setOnClickListener(v -> improveLocation());

        setupFilters(savedInstanceState);
        setupMap();

        refreshRecent();
        AppExecutors.io().execute(() -> LocusDatabase.get(this).placeDao()
                .deleteOlderThan(System.currentTimeMillis() - 7L * 24 * 3600 * 1000));

        if (!PermissionHelper.hasLocation(this)) {
            permissionLauncher.launch(PermissionHelper.locationPermissions());
        }
        updateAreaLabel();

        if (savedInstanceState != null) { // screen rotated: bring the last search back
            String q = savedInstanceState.getString(STATE_QUERY, "");
            if (!q.isEmpty()) {
                b.etSearch.setText(q);
                search(q, false);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_QUERY, currentQuery);
        out.putBoolean(STATE_NEAREST, sortNearest);
        out.putInt(STATE_KM, maxKm);
    }

    @Override
    protected void onStart() {
        super.onStart();
        live.start(); // continuous, self-healing position feed
    }

    @Override
    protected void onResume() {
        super.onResume();
        map.onResume();
    }

    @Override
    protected void onPause() {
        map.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        live.stop();
        handler.removeCallbacks(releaseSearch);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        loadToken++; // stops any search still running in the background
        live.stop();
        map.onDetach();
        sql.close();
        super.onDestroy();
    }

    // ---- position ------------------------------------------------------------------------------

    private static long ageMs(Location l) {
        return (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000L;
    }

    private void onFix(Location loc) {
        boolean fresh = ageMs(loc) < FRESH_FIX_MS;
        lat = loc.getLatitude();
        lon = loc.getLongitude();
        accuracy = loc.hasAccuracy() ? loc.getAccuracy() : -1f;
        locSource = fresh ? 2 : 1;
        updateAreaLabel();
        updateMe();
        if (!fresh) return;

        if (pendingSearch && accuracy >= 0 && accuracy <= GOOD_ACCURACY_M) {
            releasePending();
            return;
        }
        // you walked somewhere else (or the position just became much better): refresh the results
        if (hasSearched && !pendingSearch && !currentQuery.isEmpty()
                && SystemClock.elapsedRealtime() - lastAutoRefresh > 15_000) {
            double moved = GeoUtils.distance(searchedLat, searchedLon, lat, lon);
            boolean improved = searchedAccuracy > 250 && accuracy >= 0 && accuracy <= GOOD_ACCURACY_M && moved > 120;
            if (moved > 300 || improved) {
                lastAutoRefresh = SystemClock.elapsedRealtime();
                Snackbar.make(b.getRoot(), R.string.location_updated, Snackbar.LENGTH_SHORT).show();
                startFetch(currentQuery, ++loadToken);
            }
        }
    }

    private void updateAreaLabel() {
        LiveLocation.State st = live.state();
        if (pendingSearch) {
            b.tvArea.setText(getString(R.string.area_waiting));
        } else if (st == LiveLocation.State.NO_PERMISSION || !PermissionHelper.hasLocation(this)) {
            b.tvArea.setText(getString(R.string.area_no_permission));
        } else if (st == LiveLocation.State.GPS_OFF) {
            b.tvArea.setText(getString(R.string.area_gps_off));
        } else if (locSource == 2 && accuracy >= 0) {
            int text = accuracy <= GOOD_ACCURACY_M ? R.string.area_gps_acc : R.string.area_gps_rough;
            b.tvArea.setText(getString(text, Math.round(accuracy)));
        } else if (locSource == 2) {
            b.tvArea.setText(getString(R.string.area_gps));
        } else if (locSource == 1) {
            b.tvArea.setText(getString(R.string.area_last));
        } else {
            b.tvArea.setText(getString(R.string.area_default));
        }
        b.tvArea.setVisibility(View.VISIBLE);
    }

    /** Tap on the position line: ask for permission / switch location on / get a fresh, better fix. */
    private void improveLocation() {
        if (!PermissionHelper.hasLocation(this)) {
            permissionLauncher.launch(PermissionHelper.locationPermissions());
            return;
        }
        askedSettings = false;
        live.reset();
        locSource = Math.min(locSource, 1);
        live.start();
        if (!currentQuery.isEmpty()) search(currentQuery, true);
        else updateAreaLabel();
    }

    // ---- searching -----------------------------------------------------------------------------

    private void search(String raw, boolean force) {
        final String key = QueryPlanner.normalize(raw);
        if (key.length() < 2) {
            rawResults = new ArrayList<>();
            currentQuery = "";
            render(false);
            showMessage(getString(R.string.explore_hint), false);
            b.swipe.setRefreshing(false);
            return;
        }
        hideKeyboard();
        currentQuery = key;
        final int token = ++loadToken;
        handler.removeCallbacks(releaseSearch);
        pendingSearch = false;
        b.swipe.setRefreshing(true);

        AppExecutors.io().execute(() -> sql.addExploreHistory(key));
        refreshRecent();

        CacheEntry fresh = force ? null : memory.get(cacheKey(key));
        if (fresh != null && SystemClock.elapsedRealtime() - fresh.at < CACHE_MS) {
            show(fresh.places, true); // same search, same spot, a minute ago: no need to ask the servers again
            searchedLat = lat;
            searchedLon = lon;
            searchedAccuracy = accuracy < 0 ? 9999f : accuracy;
            hasSearched = true;
            fitPending = true;
            b.swipe.setRefreshing(false);
            return;
        }
        showCached(key, token); // instant content while the fresh answer loads

        boolean gpsAvailable = PermissionHelper.hasLocation(this)
                && live.state() != LiveLocation.State.GPS_OFF && live.state() != LiveLocation.State.NO_PERMISSION;
        boolean goodFix = locSource == 2 && accuracy >= 0 && accuracy <= GOOD_ACCURACY_M;
        if (!gpsAvailable || goodFix) {
            startFetch(key, token);
        } else {
            // wait (a few seconds at most) for an accurate position - a wrong centre means wrong results
            pendingSearch = true;
            updateAreaLabel();
            handler.postDelayed(releaseSearch, WAIT_FOR_FIX_MS);
        }
    }

    private void releasePending() {
        handler.removeCallbacks(releaseSearch);
        if (!pendingSearch) return;
        pendingSearch = false;
        updateAreaLabel();
        if (!currentQuery.isEmpty()) startFetch(currentQuery, loadToken);
    }

    /** About 300 m grid: the same search a few steps away reuses the answer. */
    private String cacheKey(String key) {
        return key + "|" + Math.round(lat * 350) + "," + Math.round(lon * 350);
    }

    /** Room cache of earlier searches - shown only while the answer loads, and only if it is from around here. */
    private void showCached(final String key, final int token) {
        hideMessage();
        AppExecutors.io().execute(() -> {
            final List<Place> cached = LocusDatabase.get(this).placeDao().getBySearch(key);
            AppExecutors.main().post(() -> {
                if (isDestroyed() || token != loadToken || cached.isEmpty() || !rawResults.isEmpty()) return;
                double nearest = Double.MAX_VALUE;
                for (Place p : cached) nearest = Math.min(nearest, GeoUtils.distance(lat, lon, p.lat, p.lon));
                if (nearest < 5000) show(cached, false);
            });
        });
    }

    private void startFetch(final String key, final int token) {
        final double fLat = lat;
        final double fLon = lon;
        searchedLat = fLat;
        searchedLon = fLon;
        searchedAccuracy = accuracy < 0 ? 9999f : accuracy;
        hasSearched = true;
        b.swipe.setRefreshing(true);
        AppExecutors.io().execute(() -> {
            try {
                final List<Place> places = PlaceSearch.search(key, fLat, fLon, partial ->
                        AppExecutors.main().post(() -> {
                            // first answers appear while the slower services are still working
                            if (!isDestroyed() && token == loadToken) show(partial, false);
                        }), () -> isDestroyed() || token != loadToken);
                if (isDestroyed() || token != loadToken) return;
                LocusDatabase.get(this).placeDao().replaceSearch(key, places);
                AppExecutors.main().post(() -> {
                    if (isDestroyed() || token != loadToken) return;
                    b.swipe.setRefreshing(false);
                    memory.put(cacheKey(key), new CacheEntry(places, SystemClock.elapsedRealtime()));
                    fitPending = true;
                    show(places, true);
                });
            } catch (Exception e) {
                final String message = HttpClient.friendlyMessage(this, e);
                final List<Place> saved = savedMatches(key);
                AppExecutors.main().post(() -> {
                    if (isDestroyed() || token != loadToken) return;
                    b.swipe.setRefreshing(false);
                    if (!rawResults.isEmpty()) {
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

    /** Offline fallback: places kept from earlier searches that match the text (one row per place), near here. */
    private List<Place> savedMatches(String key) {
        try {
            List<Place> rows = LocusDatabase.get(this).placeDao().searchCached("%" + key + "%");
            Map<String, Place> unique = new HashMap<>();
            for (Place p : rows) {
                if (GeoUtils.distance(lat, lon, p.lat, p.lon) > 30000) continue;
                unique.put(p.name + "|" + Math.round(p.lat * 10000) + "|" + Math.round(p.lon * 10000), p);
            }
            return new ArrayList<>(unique.values());
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // ---- showing results -----------------------------------------------------------------------

    private void show(List<Place> places, boolean isFinal) {
        rawResults = places;
        render(isFinal);
    }

    private static Place copyOf(Place p, double lat, double lon) {
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
        c.relevance = p.relevance;
        c.distanceMeters = GeoUtils.distance(lat, lon, p.lat, p.lon); // always from the current position
        return c;
    }

    /** Applies precision rules, filters and the chosen order to the raw results and updates list and map. */
    private void render(boolean isFinal) {
        int strong = 0;
        for (Place p : rawResults) {
            if (p.relevance >= Relevance.NAME) strong++;
        }
        boolean dropLoose = strong >= 3; // enough real matches: loose text matches are noise

        List<Place> view = new ArrayList<>();
        for (Place raw : rawResults) {
            if (dropLoose && raw.relevance < Relevance.NAME) continue;
            Place p = copyOf(raw, lat, lon);
            if (maxKm > 0 && p.distanceMeters > maxKm * 1000.0) continue;
            view.add(p);
        }
        if (sortNearest) {
            Collections.sort(view, (x, y) -> Double.compare(x.distanceMeters, y.distanceMeters));
        } else {
            Collections.sort(view, (x, y) -> Double.compare(Relevance.rank(x.relevance, x.distanceMeters),
                    Relevance.rank(y.relevance, y.distanceMeters)));
        }
        adapter.submitList(view);

        boolean haveResults = !rawResults.isEmpty();
        b.filterScroll.setVisibility(haveResults ? View.VISIBLE : View.GONE);
        if (view.isEmpty()) {
            b.tvCount.setVisibility(View.GONE);
            if (haveResults) {
                showMessage(getString(R.string.explore_filtered_empty), false);
            } else if (isFinal) {
                showMessage(getString(R.string.explore_nothing, currentQuery), true);
            } else {
                hideMessage();
            }
        } else {
            hideMessage();
            b.tvCount.setText(getResources().getQuantityString(R.plurals.result_count, view.size(), view.size()));
            b.tvCount.setVisibility(View.VISIBLE);
        }
        updateMapMarkers(view);
    }

    private void showMessage(String text, boolean withRetry) {
        b.tvMessage.setText(text);
        b.btnRetry.setVisibility(withRetry ? View.VISIBLE : View.GONE);
        b.messageBox.setVisibility(View.VISIBLE);
    }

    private void hideMessage() {
        b.messageBox.setVisibility(View.GONE);
    }

    // ---- filters -------------------------------------------------------------------------------

    private void setupFilters(Bundle saved) {
        if (saved != null) {
            sortNearest = saved.getBoolean(STATE_NEAREST);
            maxKm = saved.getInt(STATE_KM);
        }
        b.chip1km.setText(getString(R.string.unit_km, "1"));
        b.chip3km.setText(getString(R.string.unit_km, "3"));
        b.chip10km.setText(getString(R.string.unit_km, "10"));
        b.chipBest.setChecked(!sortNearest);
        b.chipNearest.setChecked(sortNearest);
        b.chip1km.setChecked(maxKm == 1);
        b.chip3km.setChecked(maxKm == 3);
        b.chip10km.setChecked(maxKm == 10);

        b.groupSort.setOnCheckedStateChangeListener((group, ids) -> {
            sortNearest = !ids.isEmpty() && ids.get(0) == R.id.chipNearest;
            render(true);
        });
        b.groupDist.setOnCheckedStateChangeListener((group, ids) -> {
            int id = ids.isEmpty() ? 0 : ids.get(0);
            maxKm = id == R.id.chip1km ? 1 : id == R.id.chip3km ? 3 : id == R.id.chip10km ? 10 : 0;
            render(true);
        });
    }

    // ---- map -----------------------------------------------------------------------------------

    private void setupMap() {
        map = b.exploreMap;
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.setTilesScaledToDpi(true);
        map.setMinZoomLevel(4.0);
        map.setMaxZoomLevel(19.5);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        map.getController().setZoom(15.0);
        map.getController().setCenter(new GeoPoint(lat, lon));
        // tap on empty map hides the place card (added first = lowest priority, markers keep their taps)
        map.getOverlays().add(0, new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                b.mapCard.getRoot().setVisibility(View.GONE);
                return false;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                return false;
            }
        }));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_explore, menu);
        mapItem = menu.findItem(R.id.action_map_toggle);
        updateMapItem();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_map_toggle) {
            showMap = !showMap;
            b.swipe.setVisibility(showMap ? View.GONE : View.VISIBLE);
            b.exploreMap.setVisibility(showMap ? View.VISIBLE : View.GONE);
            if (!showMap) b.mapCard.getRoot().setVisibility(View.GONE);
            updateMapItem();
            if (showMap) {
                fitPending = true;
                map.post(this::fitMap);
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateMapItem() {
        if (mapItem == null) return;
        mapItem.setIcon(showMap ? R.drawable.ic_list : R.drawable.ic_map);
        mapItem.setTitle(showMap ? R.string.menu_list : R.string.menu_map);
    }

    private Marker newMarker(GeoPoint at, String title, int iconRes, boolean centered) {
        Marker m = new Marker(map);
        if (iconRes != 0) m.setIcon(ContextCompat.getDrawable(this, iconRes));
        m.setAnchor(Marker.ANCHOR_CENTER, centered ? Marker.ANCHOR_CENTER : Marker.ANCHOR_BOTTOM);
        m.setTitle(title);
        m.setPosition(at);
        return m;
    }

    private void updateMe() {
        GeoPoint p = new GeoPoint(lat, lon);
        if (meMarker == null) {
            accuracyCircle = new Polygon();
            accuracyCircle.getFillPaint().setColor(0x222196F3);
            accuracyCircle.getOutlinePaint().setColor(0x552196F3);
            accuracyCircle.getOutlinePaint().setStrokeWidth(2f);
            accuracyCircle.setOnClickListener((polygon, mapView, pos) -> false);
            map.getOverlays().add(accuracyCircle);
            meMarker = newMarker(p, getString(R.string.you_are_here), R.drawable.ic_pin_me, true);
            meMarker.setInfoWindow(null);
            meMarker.setOnMarkerClickListener((m, mv) -> true);
            map.getOverlays().add(meMarker);
        }
        meMarker.setPosition(p);
        if (accuracy > 0 && accuracy < 2000) {
            accuracyCircle.setPoints(Polygon.pointsAsCircle(p, accuracy));
        } else {
            accuracyCircle.setPoints(new ArrayList<GeoPoint>());
        }
        map.invalidate();
    }

    private void updateMapMarkers(List<Place> view) {
        map.getOverlays().removeAll(placeMarkers);
        placeMarkers.clear();
        int n = 0;
        for (final Place p : view) {
            if (n++ >= MAX_MAP_MARKERS) break;
            Marker m = newMarker(new GeoPoint(p.lat, p.lon), p.name, 0, false);
            m.setInfoWindow(null);
            m.setOnMarkerClickListener((marker, mv) -> {
                PlacesAdapter.bind(b.mapCard, p, placeListener);
                b.mapCard.getRoot().setVisibility(View.VISIBLE);
                map.getController().animateTo(marker.getPosition());
                return true;
            });
            placeMarkers.add(m);
        }
        // keep "my position" on top of the place markers
        if (meMarker != null) {
            map.getOverlays().remove(meMarker);
            map.getOverlays().addAll(placeMarkers);
            map.getOverlays().add(meMarker);
        } else {
            map.getOverlays().addAll(placeMarkers);
        }
        map.invalidate();
        if (showMap) map.post(this::fitMap);
    }

    /** Zooms so that you and the nearest results are all visible. */
    private void fitMap() {
        if (!fitPending || map.getWidth() == 0 || placeMarkers.isEmpty()) return;
        fitPending = false;
        List<GeoPoint> pts = new ArrayList<>();
        pts.add(new GeoPoint(lat, lon));
        int n = 0;
        for (Marker m : placeMarkers) {
            if (n++ >= 15) break; // the nearest ones - a far outlier must not zoom the map out to the sea
            pts.add(m.getPosition());
        }
        BoundingBox box = BoundingBox.fromGeoPointsSafe(pts);
        map.zoomToBoundingBox(box.increaseByScale(1.3f), true, 60);
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
                        search(q, false);
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
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
