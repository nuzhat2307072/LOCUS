package com.locus.app.ui;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import com.locus.app.R;
import com.locus.app.data.db.LocusDatabase;
import com.locus.app.data.firebase.HazardRepository;
import com.locus.app.data.firebase.Session;
import com.locus.app.data.sqlite.LocusSqlHelper;
import com.locus.app.databinding.ActivityNavigationBinding;
import com.locus.app.graph.CostModel;
import com.locus.app.graph.Graph;
import com.locus.app.graph.Router;
import com.locus.app.model.Hazard;
import com.locus.app.model.HazardType;
import com.locus.app.net.HttpClient;
import com.locus.app.net.NominatimApi;
import com.locus.app.net.OsrmApi;
import com.locus.app.net.OverpassApi;
import com.locus.app.service.LocationTrackingService;
import com.locus.app.util.AppExecutors;
import com.locus.app.util.AppPrefs;
import com.locus.app.util.ExternalIntents;
import com.locus.app.util.GeoUtils;
import com.locus.app.util.LocationHelper;
import com.locus.app.util.PermissionHelper;

import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Polyline;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Smart Navigation.
 *
 * The user only says WHERE (search or tap the map) and HOW (walk / cycle / car, rain, traffic).
 * Everything else is automatic: the start is the GPS position, the road network is downloaded and
 * turned into a weighted graph, reported hazards close or slow roads, the program picks the search
 * algorithm itself (Router.route), animates the search, draws the route and recalculates by itself
 * when conditions change, a hazard appears on the route, or the driver leaves the route.
 */
public class NavigationActivity extends AppCompatActivity {
    public static final String EXTRA_LAT = "dest_lat";
    public static final String EXTRA_LON = "dest_lon";
    public static final String EXTRA_NAME = "dest_name";

    private static final double MAX_SPAN_DEG = 0.048;      // ~5 km between start and destination
    private static final double MIN_HALF_SPAN = 0.02;      // road box is at least ~4.4 km wide, so it is reusable
    private static final long HAZARD_TTL_MS = 12L * 60 * 60 * 1000;
    private static final long REROUTE_COOLDOWN_MS = 15_000;

    public static Intent intentTo(Context c, double lat, double lon, String name) {
        return new Intent(c, NavigationActivity.class)
                .putExtra(EXTRA_LAT, lat).putExtra(EXTRA_LON, lon).putExtra(EXTRA_NAME, name);
    }

    private ActivityNavigationBinding b;
    private MapView map;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService routeExec = Executors.newSingleThreadExecutor(); // graph is not thread-safe
    private LocusSqlHelper sql;

    // points
    private GeoPoint me;
    private GeoPoint start;
    private GeoPoint dest;
    private boolean startIsMe = true;

    // overlays
    private Marker startMarker;
    private Marker destMarker;
    private Marker meMarker;
    private Polyline routeLine;
    private VisitedOverlay visitedOverlay;
    private Runnable animRunnable;
    private final List<Overlay> hazardOverlays = new ArrayList<>();

    // hazards
    private List<Hazard> hazards = new ArrayList<>();
    private Set<String> knownHazardIds = new HashSet<>();
    private boolean hazardsLoaded;
    private HazardRepository.Subscription hazardSub;

    // road graph + routing state
    private Graph graph;
    private double[] graphBox;
    private boolean graphLoading;
    private boolean routeAfterLoad;
    private Router.Result lastResult;
    private long lastRouteAt;
    private int routeToken;
    private boolean rerouting;
    private double prevMeters;
    private boolean tracking;

    // search suggestions
    private ArrayAdapter<String> suggestionAdapter;
    private final List<String> suggestionLabels = new ArrayList<>();
    private List<NominatimApi.Result> suggestionResults; // null while the list shows history
    private List<String> historyItems = new ArrayList<>();

    private final ActivityResultLauncher<String[]> locationPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), r -> {
                if (PermissionHelper.hasLocation(this)) {
                    useMyLocation(true);
                } else {
                    fallbackToLastLocation();
                    snack("Location is off - using your last known position");
                }
            });

    private final ActivityResultLauncher<String[]> notificationPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), r -> startTrackingService());

    private final BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onLocationUpdate(new GeoPoint(
                    intent.getDoubleExtra(LocationTrackingService.EXTRA_LAT, 0),
                    intent.getDoubleExtra(LocationTrackingService.EXTRA_LON, 0)));
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityNavigationBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        sql = new LocusSqlHelper(this);

        setupMap();
        setupControls();
        setupSearch();

        // Hazards (Room LiveData). Only recent ones count; a new one on the current route triggers a reroute.
        final long since = System.currentTimeMillis() - HAZARD_TTL_MS;
        AppExecutors.io().execute(() -> LocusDatabase.get(this).hazardDao().deleteOlderThan(since));
        LocusDatabase.get(this).hazardDao().observeSince(since).observe(this, list -> {
            List<Hazard> fresh = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (Hazard h : list) {
                ids.add(h.id);
                if (hazardsLoaded && !knownHazardIds.contains(h.id)) fresh.add(h);
            }
            hazards = list;
            knownHazardIds = ids;
            hazardsLoaded = true;
            redrawHazards();
            for (Hazard h : fresh) {
                if (routeNear(h)) {
                    rerouteBecauseOf(h);
                    break;
                }
            }
        });

        // Destination handed over from Explore / Place details / Saved places
        Intent in = getIntent();
        if (in.hasExtra(EXTRA_LAT)) {
            GeoPoint d = new GeoPoint(in.getDoubleExtra(EXTRA_LAT, 0), in.getDoubleExtra(EXTRA_LON, 0));
            String name = in.getStringExtra(EXTRA_NAME);
            setDest(d, name, false);
            if (name != null) b.etSearch.setText(name);
            b.etSearch.clearFocus();
            map.getController().setCenter(d);
        }

        // Start point = GPS position (asked for in context, not at app start)
        if (PermissionHelper.hasLocation(this)) {
            useMyLocation(dest == null);
        } else {
            locationPermissionLauncher.launch(PermissionHelper.locationPermissions());
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        hazardSub = HazardRepository.listen(this);
        ContextCompat.registerReceiver(this, locationReceiver,
                new IntentFilter(LocationTrackingService.ACTION_LOCATION_UPDATE), ContextCompat.RECEIVER_NOT_EXPORTED);
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
        if (hazardSub != null) {
            hazardSub.stop();
            hazardSub = null;
        }
        unregisterReceiver(locationReceiver);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        routeToken++;
        stopAnimation();
        handler.removeCallbacksAndMessages(null);
        routeExec.shutdownNow();
        map.onDetach();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ setup

    private void setupMap() {
        map = b.mapView;
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.setTilesScaledToDpi(true); // readable labels on high-density phone screens
        map.setMinZoomLevel(4.0);
        map.setMaxZoomLevel(19.5);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        map.getController().setZoom(15.0);
        double[] last = AppPrefs.lastLocation(this);
        map.getController().setCenter(new GeoPoint(last[0], last[1]));

        // Added first = lowest priority, so markers still receive their own taps.
        map.getOverlays().add(0, new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                if (b.cardSuggestions.getVisibility() == View.VISIBLE) {
                    hideSuggestions();
                    return true;
                }
                hideKeyboard();
                setDest(p, "Dropped pin", true);
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                showLongPressMenu(p);
                return true;
            }
        }));
    }

    private void setupControls() {
        // Travel mode and conditions: changing any of them recalculates automatically
        b.modeGroup.setOnCheckedStateChangeListener((group, ids) -> requestRoute());
        b.chipRain.setOnCheckedChangeListener((chip, checked) -> requestRoute());
        b.chipTraffic.setOnCheckedChangeListener((chip, checked) -> requestRoute());

        b.fabHazard.setOnClickListener(v -> {
            GeoPoint where = me != null ? me : lastKnownPoint();
            showHazardTypeDialog(where);
        });
        b.btnMyLoc.setOnClickListener(v -> {
            startIsMe = true;
            if (PermissionHelper.hasLocation(this)) {
                useMyLocation(true);
            } else {
                locationPermissionLauncher.launch(PermissionHelper.locationPermissions());
            }
        });
        b.btnExternal.setOnClickListener(v -> openExternalMaps());
        b.btnClear.setOnClickListener(v -> clearAll());
        b.switchLive.setOnCheckedChangeListener((button, on) -> {
            if (on) startTracking();
            else stopTracking();
        });
    }

    private void setupSearch() {
        suggestionAdapter = new ArrayAdapter<>(this, R.layout.item_suggestion, R.id.tvText, suggestionLabels);
        b.listSuggestions.setAdapter(suggestionAdapter);
        b.listSuggestions.setOnItemClickListener((parent, view, position, id) -> {
            if (suggestionResults != null) {
                NominatimApi.Result r = suggestionResults.get(position);
                b.etSearch.setText(shortName(r.name));
                hideSuggestions();
                setDest(new GeoPoint(r.lat, r.lon), shortName(r.name), true);
            } else {
                b.etSearch.setText(historyItems.get(position));
                b.etSearch.setSelection(b.etSearch.getText().length());
                doSearch();
            }
        });

        b.etSearch.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && text(b.etSearch).isEmpty()) showHistory();
        });
        b.etSearch.setOnClickListener(v -> {
            if (text(b.etSearch).isEmpty()) showHistory();
        });
        b.etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int bf, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (s.length() == 0 && b.etSearch.hasFocus()) showHistory();
                else if (suggestionResults != null) hideSuggestions(); // stale results for old text
            }
        });
        b.etSearch.setOnEditorActionListener((tv, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch();
                return true;
            }
            return false;
        });
        b.btnSearch.setOnClickListener(v -> doSearch());
    }

    // ------------------------------------------------------------------ search (inline suggestions)

    private void doSearch() {
        final String q = text(b.etSearch);
        if (q.length() < 2) {
            snack("Type a place name first");
            return;
        }
        hideKeyboard();
        showProgress(true);
        AppExecutors.io().execute(() -> sql.addHistory(q)); // SQLite insert
        HttpClient.async(() -> NominatimApi.search(q), new HttpClient.Callback<List<NominatimApi.Result>>() {
            @Override
            public void onSuccess(List<NominatimApi.Result> results) {
                showProgress(false);
                if (results.isEmpty()) {
                    snack("Nothing found for \"" + q + "\"");
                    return;
                }
                if (results.size() == 1) {
                    NominatimApi.Result r = results.get(0);
                    hideSuggestions();
                    setDest(new GeoPoint(r.lat, r.lon), shortName(r.name), true);
                    return;
                }
                suggestionResults = results;
                suggestionLabels.clear();
                for (NominatimApi.Result r : results) suggestionLabels.add(r.name);
                suggestionAdapter.notifyDataSetChanged();
                b.cardSuggestions.setVisibility(View.VISIBLE);
            }

            @Override
            public void onError(String message) {
                showProgress(false);
                snack(message);
            }
        });
    }

    private void showHistory() {
        AppExecutors.io().execute(() -> {
            final List<String> items = sql.recentHistory(6); // SQLite read
            AppExecutors.main().post(() -> {
                if (isDestroyed() || !text(b.etSearch).isEmpty() || items.isEmpty()) return;
                historyItems = items;
                suggestionResults = null;
                suggestionLabels.clear();
                suggestionLabels.addAll(items);
                suggestionAdapter.notifyDataSetChanged();
                b.cardSuggestions.setVisibility(View.VISIBLE);
            });
        });
    }

    private void hideSuggestions() {
        suggestionResults = null;
        b.cardSuggestions.setVisibility(View.GONE);
        hideKeyboard();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(b.etSearch.getWindowToken(), 0);
        b.etSearch.clearFocus();
    }

    private static String shortName(String full) {
        int comma = full.indexOf(',');
        return comma > 0 ? full.substring(0, comma) : full;
    }

    private static String text(android.widget.EditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    // ------------------------------------------------------------------ position + markers

    private Marker makeMarker(int iconRes, String title, boolean centerAnchor) {
        Marker m = new Marker(map);
        m.setIcon(ContextCompat.getDrawable(this, iconRes));
        m.setAnchor(Marker.ANCHOR_CENTER, centerAnchor ? Marker.ANCHOR_CENTER : Marker.ANCHOR_BOTTOM);
        m.setTitle(title);
        return m;
    }

    private GeoPoint lastKnownPoint() {
        double[] last = AppPrefs.lastLocation(this);
        return new GeoPoint(last[0], last[1]);
    }

    private void fallbackToLastLocation() {
        if (me == null) {
            GeoPoint p = lastKnownPoint();
            updateMe(p);
            if (startIsMe) start = p;
            if (dest != null && lastResult == null) requestRoute();
        }
    }

    private void useMyLocation(boolean recenter) {
        LocationHelper.getLocation(this, loc -> {
            if (isDestroyed()) return;
            final boolean first = me == null;
            GeoPoint p;
            if (loc != null) {
                p = new GeoPoint(loc.getLatitude(), loc.getLongitude());
            } else {
                p = lastKnownPoint();
                if (first) snack("No GPS fix yet - using your last known position");
            }
            updateMe(p);
            if (startIsMe) setStart(p, true);
            if (recenter) {
                map.getController().setZoom(16.0);
                map.getController().animateTo(p);
            }
            if (first && graph == null && !graphLoading) prefetchRoads(p);
            if (dest != null && lastResult == null) requestRoute();
        });
    }

    private void updateMe(GeoPoint p) {
        me = p;
        if (meMarker == null) {
            meMarker = makeMarker(R.drawable.ic_pin_me, "You are here", true);
            meMarker.setInfoWindow(null);
            meMarker.setOnMarkerClickListener((m, mv) -> true);
            map.getOverlays().add(meMarker);
        }
        meMarker.setPosition(p);
        map.invalidate();
    }

    /** auto = the start is simply "my location": no extra marker, and it keeps following GPS. */
    private void setStart(GeoPoint p, boolean auto) {
        start = p;
        startIsMe = auto;
        if (auto) {
            if (startMarker != null) {
                map.getOverlays().remove(startMarker);
                startMarker = null;
            }
        } else {
            if (startMarker == null) {
                startMarker = makeMarker(R.drawable.ic_pin_start, "Start", false);
                map.getOverlays().add(startMarker);
            }
            startMarker.setPosition(p);
        }
        map.invalidate();
    }

    private void setDest(GeoPoint p, String title, boolean route) {
        dest = p;
        if (destMarker == null) {
            destMarker = makeMarker(R.drawable.ic_pin_dest, "Destination", false);
            map.getOverlays().add(destMarker);
        }
        destMarker.setTitle(title == null ? "Destination" : title);
        destMarker.setPosition(p);
        map.invalidate();
        if (route) requestRoute();
    }

    private void showLongPressMenu(GeoPoint p) {
        String[] items = {"Go here", "Start from here", "Report a hazard here"};
        new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> {
                    if (which == 0) setDest(p, "Dropped pin", true);
                    else if (which == 1) {
                        setStart(p, false);
                        requestRoute();
                    } else showHazardTypeDialog(p);
                })
                .show();
    }

    // ------------------------------------------------------------------ live GPS

    private void startTracking() {
        if (!PermissionHelper.hasLocation(this)) {
            b.switchLive.setChecked(false);
            locationPermissionLauncher.launch(PermissionHelper.locationPermissions());
            return;
        }
        tracking = true;
        if (me != null) {
            map.getController().animateTo(me);
        }
        String[] notif = PermissionHelper.notificationPermissions();
        if (notif.length > 0 && !PermissionHelper.hasNotifications(this)) {
            notificationPermissionLauncher.launch(notif); // the service still runs if the user says no
        } else {
            startTrackingService();
        }
    }

    private void startTrackingService() {
        if (!tracking) return;
        Intent i = new Intent(this, LocationTrackingService.class).setAction(LocationTrackingService.ACTION_START);
        ContextCompat.startForegroundService(this, i);
    }

    private void stopTracking() {
        tracking = false;
        stopService(new Intent(this, LocationTrackingService.class));
    }

    private void onLocationUpdate(GeoPoint p) {
        updateMe(p);
        if (startIsMe) start = p;
        if (tracking) {
            map.getController().animateTo(p);
        }
        if (!tracking || dest == null || lastResult == null) return;

        if (GeoUtils.distance(p.getLatitude(), p.getLongitude(), dest.getLatitude(), dest.getLongitude()) < 35) {
            snack(getString(R.string.arrived));
            stopTracking();
            b.switchLive.setChecked(false);
            return;
        }
        // Left the route? Recalculate from the new position (with a cooldown so it never flickers).
        if (startIsMe && System.currentTimeMillis() - lastRouteAt > REROUTE_COOLDOWN_MS
                && distanceToRoute(p) > 100) {
            snack(getString(R.string.off_route));
            requestRoute();
        }
    }

    private double distanceToRoute(GeoPoint p) {
        List<Graph.Node> path = lastResult.path;
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < path.size(); i++) {
            Graph.Node a = path.get(i);
            Graph.Node c = path.get(i + 1);
            best = Math.min(best, GeoUtils.distanceToSegment(p.getLatitude(), p.getLongitude(),
                    a.lat, a.lon, c.lat, c.lon));
        }
        return best;
    }

    // ------------------------------------------------------------------ hazards

    private void showHazardTypeDialog(GeoPoint where) {
        HazardType[] types = HazardType.values();
        String[] labels = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            labels[i] = types[i].label + (types[i].blocking ? "  (closes the road)" : "  (slows traffic)");
        }
        new AlertDialog.Builder(this)
                .setTitle("What is here?")
                .setItems(labels, (d, which) -> createHazard(types[which], where))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void createHazard(HazardType t, GeoPoint p) {
        Hazard h = new Hazard();
        h.id = UUID.randomUUID().toString();
        h.type = t.name();
        h.description = t.label;
        h.lat = p.getLatitude();
        h.lon = p.getLongitude();
        h.radiusM = t.defaultRadius;
        h.createdAt = System.currentTimeMillis();
        h.reporterUid = Session.uid(this);
        // Room first (the LiveData observer redraws the map and reroutes if needed), Firebase when signed in
        HazardRepository.add(this, h, null);
        snack(t.label + " reported");
    }

    private void redrawHazards() {
        for (Overlay o : hazardOverlays) map.getOverlays().remove(o);
        hazardOverlays.clear();
        for (Hazard h : hazards) {
            final HazardType t = HazardType.fromName(h.type);
            GeoPoint c = new GeoPoint(h.lat, h.lon);

            Polygon circle = new Polygon();
            circle.setPoints(Polygon.pointsAsCircle(c, h.radiusM));
            circle.getFillPaint().setColor(t.blocking ? 0x33C62828 : 0x33FF8F00);
            circle.getOutlinePaint().setColor(t.blocking ? 0xFFC62828 : 0xFFFF8F00);
            circle.getOutlinePaint().setStrokeWidth(3f);
            circle.setOnClickListener((polygon, mapView, pos) -> false); // taps fall through to the map
            map.getOverlays().add(circle);
            hazardOverlays.add(circle);

            Marker m = makeMarker(R.drawable.ic_pin_hazard, t.label, true);
            m.setPosition(c);
            m.setOnMarkerClickListener((marker, mv) -> {
                showHazardDetails(h, t);
                return true;
            });
            map.getOverlays().add(m);
            hazardOverlays.add(m);
        }
        map.invalidate();
    }

    private void showHazardDetails(Hazard h, HazardType t) {
        boolean mine = h.reporterUid != null && h.reporterUid.equals(Session.uid(this));
        String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(h.createdAt));
        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle(t.label)
                .setMessage(String.format(Locale.US, "Radius: %d m\nEffect: %s\nReported: %s%s",
                        h.radiusM, t.blocking ? "road closed" : "slower travel", when, mine ? " (by you)" : ""))
                .setPositiveButton(android.R.string.ok, null);
        if (mine) {
            d.setNegativeButton("Remove hazard", (dialog, w) -> {
                HazardRepository.delete(this, h);
                if (dest != null) requestRoute(); // roads reopen -> better route may exist
            });
        }
        d.show();
    }

    private boolean routeNear(Hazard h) {
        if (lastResult == null || !lastResult.found) return false;
        List<Graph.Node> path = lastResult.path;
        for (int i = 0; i + 1 < path.size(); i++) {
            Graph.Node a = path.get(i);
            Graph.Node c = path.get(i + 1);
            if (GeoUtils.distanceToSegment(h.lat, h.lon, a.lat, a.lon, c.lat, c.lon) <= h.radiusM + 20) return true;
        }
        return false;
    }

    private void rerouteBecauseOf(Hazard h) {
        snack(HazardType.fromName(h.type).label + " on your route - recalculating...");
        rerouting = true;
        prevMeters = lastResult.distanceMeters;
        requestRoute();
    }

    // ------------------------------------------------------------------ routing

    private CostModel costModel() {
        CostModel.Mode mode = CostModel.Mode.CAR;
        int id = b.modeGroup.getCheckedChipId();
        if (id == R.id.chipWalk) mode = CostModel.Mode.WALK;
        else if (id == R.id.chipCycle) mode = CostModel.Mode.CYCLE;
        return new CostModel(mode, b.chipRain.isChecked(), b.chipTraffic.isChecked());
    }

    /** Single entry point: (re)calculates the route for the current start, destination, mode and conditions. */
    private void requestRoute() {
        if (b == null || dest == null) return;
        if (start == null) { // no position yet: get one, then come back
            if (PermissionHelper.hasLocation(this)) useMyLocation(false);
            else fallbackToLastLocation();
            return;
        }
        final int token = ++routeToken;
        clearRouteOverlays();
        showProgress(true);
        b.tvPrimary.setText(R.string.finding_route);
        b.tvSecondary.setVisibility(View.GONE);

        final double[] box = boxFor(start, dest);
        if (box == null) { // too far for the local road graph -> online routing, chosen automatically
            routeWithOsrm(token);
            return;
        }
        if (graph != null && graphBox != null && covers(graphBox, start) && covers(graphBox, dest)) {
            computeRoute(token);
            return;
        }
        loadRoads(box, true);
    }

    /** Silent background download of the roads around the user so the first route is instant. */
    private void prefetchRoads(GeoPoint p) {
        double pad = MIN_HALF_SPAN;
        loadRoads(new double[]{p.getLatitude() - pad, p.getLongitude() - pad,
                p.getLatitude() + pad, p.getLongitude() + pad}, false);
    }

    private void loadRoads(final double[] box, final boolean thenRoute) {
        if (graphLoading) {
            routeAfterLoad |= thenRoute;
            return;
        }
        graphLoading = true;
        routeAfterLoad = thenRoute;
        if (thenRoute) b.tvPrimary.setText(R.string.loading_roads);
        HttpClient.async(() -> OverpassApi.fetchRoadNetwork(box[0], box[1], box[2], box[3]),
                new HttpClient.Callback<Graph>() {
                    @Override
                    public void onSuccess(Graph g) {
                        graphLoading = false;
                        if (isDestroyed()) return;
                        graph = g;
                        graphBox = box;
                        if (dest != null && start != null && (routeAfterLoad || lastResult == null)) requestRoute();
                    }

                    @Override
                    public void onError(String message) {
                        graphLoading = false;
                        if (isDestroyed() || !routeAfterLoad) return; // a failed prefetch is not worth bothering the user
                        showProgress(false);
                        if (costModel().mode == CostModel.Mode.CAR) {
                            routeWithOsrm(routeToken); // driving: fall back to the online router automatically
                        } else {
                            b.tvPrimary.setText("Could not load roads");
                            b.tvSecondary.setText(message);
                            b.tvSecondary.setVisibility(View.VISIBLE);
                            Snackbar.make(b.getRoot(), "Could not load roads", Snackbar.LENGTH_INDEFINITE)
                                    .setAction("Retry", v -> requestRoute()).show();
                        }
                    }
                });
    }

    private void computeRoute(final int token) {
        final Graph g = graph;
        final CostModel cm = costModel();
        final List<Hazard> hs = new ArrayList<>(hazards);
        final GeoPoint s = start;
        final GeoPoint d = dest;
        try {
            routeExec.execute(() -> {
                // 1) road conditions: hazards close roads or make them slower
                g.resetConditions();
                int considered = 0;
                for (Hazard h : hs) {
                    HazardType t = HazardType.fromName(h.type);
                    if (g.applyHazard(h.lat, h.lon, h.radiusM, t.blocking, t.penalty) > 0) considered++;
                }
                // 2) snap start and destination to the biggest connected network usable in this mode
                Set<Long> network = g.largestComponent(cm);
                Graph.Node sn = g.nearest(s.getLatitude(), s.getLongitude(), network);
                Graph.Node dn = g.nearest(d.getLatitude(), d.getLongitude(), network);
                String error = null;
                Router.Result res = null;
                if (sn == null || dn == null
                        || GeoUtils.distance(sn.lat, sn.lon, s.getLatitude(), s.getLongitude()) > 600
                        || GeoUtils.distance(dn.lat, dn.lon, d.getLatitude(), d.getLongitude()) > 600) {
                    error = "No " + cm.mode.label.toLowerCase(Locale.US)
                            + " road found near the start or destination";
                } else {
                    res = Router.route(g, sn.id, dn.id, cm); // 3) the program picks the algorithm
                }
                final String err = error;
                final Router.Result result = res;
                final int hazardsConsidered = considered;
                AppExecutors.main().post(() -> {
                    if (token == routeToken && !isDestroyed()) onRouteComputed(result, err, cm, hazardsConsidered);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // activity is closing
        }
    }

    private void onRouteComputed(Router.Result res, String error, CostModel cm, int hazardsConsidered) {
        showProgress(false);
        lastRouteAt = System.currentTimeMillis();
        if (error != null || res == null) {
            rerouting = false;
            lastResult = null;
            showSummary(error == null ? "No route found" : error, null);
            return;
        }
        if (!res.found) {
            rerouting = false;
            lastResult = null;
            showSummary("No " + cm.mode.label.toLowerCase(Locale.US) + " route available",
                    "Roads may be closed by reported hazards");
            return;
        }
        if (res.path.size() < 2) {
            rerouting = false;
            lastResult = null;
            showSummary("You are already at the destination", null);
            return;
        }
        lastResult = res;

        final StringBuilder sec = new StringBuilder(cm.mode.label);
        if (cm.rain) sec.append("  -  rain");
        if (cm.traffic) sec.append("  -  traffic");
        if (hazardsConsidered > 0) {
            sec.append("  -  avoiding ").append(hazardsConsidered)
                    .append(hazardsConsidered == 1 ? " hazard" : " hazards");
        }
        final String primary = GeoUtils.formatDuration(res.costSeconds) + "  -  " + GeoUtils.formatDistance(res.distanceMeters);

        zoomToPath(res.path);
        b.tvPrimary.setText(R.string.finding_route);
        animateSearch(res, cm, () -> {
            drawRoute(res, cm);
            showSummary(primary, sec.toString());
            if (rerouting) {
                double diff = res.distanceMeters - prevMeters;
                snack(String.format(Locale.US, "Rerouted around the hazard (%s%s)",
                        diff >= 0 ? "+" : "-", GeoUtils.formatDistance(Math.abs(diff))));
                rerouting = false;
            }
        });
    }

    /** Long trips: the online OSRM server (driving) takes over automatically. */
    private void routeWithOsrm(final int token) {
        final GeoPoint s = start;
        final GeoPoint d = dest;
        final CostModel.Mode mode = costModel().mode;
        HttpClient.async(() -> OsrmApi.route(s.getLatitude(), s.getLongitude(), d.getLatitude(), d.getLongitude()),
                new HttpClient.Callback<OsrmApi.Route>() {
                    @Override
                    public void onSuccess(OsrmApi.Route r) {
                        if (token != routeToken || isDestroyed()) return;
                        showProgress(false);
                        List<GeoPoint> pts = new ArrayList<>();
                        for (double[] p : r.points) pts.add(new GeoPoint(p[0], p[1]));
                        lastResult = null;
                        lastRouteAt = System.currentTimeMillis();
                        showLine(pts, ContextCompat.getColor(NavigationActivity.this, R.color.route_car));
                        showSummary(GeoUtils.formatDuration(r.durationSeconds) + "  -  " + GeoUtils.formatDistance(r.distanceMeters),
                                mode == CostModel.Mode.CAR ? "Long trip  -  main roads"
                                        : "Long trip  -  showing the driving route");
                    }

                    @Override
                    public void onError(String message) {
                        if (token != routeToken || isDestroyed()) return;
                        showProgress(false);
                        showSummary("Could not find a route", message);
                    }
                });
    }

    /** Road box around start + destination, at least ~4.4 km wide so that it can be reused for later trips. */
    private static double[] boxFor(GeoPoint s, GeoPoint d) {
        double latSpan = Math.abs(s.getLatitude() - d.getLatitude());
        double lonSpan = Math.abs(s.getLongitude() - d.getLongitude());
        if (latSpan > MAX_SPAN_DEG || lonSpan > MAX_SPAN_DEG) return null;
        double cLat = (s.getLatitude() + d.getLatitude()) / 2;
        double cLon = (s.getLongitude() + d.getLongitude()) / 2;
        double halfLat = Math.max(MIN_HALF_SPAN, latSpan / 2 + 0.008);
        double halfLon = Math.max(MIN_HALF_SPAN, lonSpan / 2 + 0.008);
        return new double[]{cLat - halfLat, cLon - halfLon, cLat + halfLat, cLon + halfLon};
    }

    private static boolean covers(double[] box, GeoPoint p) {
        double m = 0.002;
        return p.getLatitude() >= box[0] + m && p.getLatitude() <= box[2] - m
                && p.getLongitude() >= box[1] + m && p.getLongitude() <= box[3] - m;
    }

    // ------------------------------------------------------------------ drawing + search animation

    /** Short (~1 s) visualisation of the roads the search explored; skipped for tiny searches. */
    private void animateSearch(Router.Result res, CostModel cm, Runnable onDone) {
        stopAnimation();
        final int total = res.visited.size();
        if (total < 40) {
            onDone.run();
            return;
        }
        final VisitedOverlay overlay = new VisitedOverlay(res.visited, routeColor(cm.mode));
        visitedOverlay = overlay;
        map.getOverlays().add(1, overlay);
        final int step = Math.max(1, (int) Math.ceil(total / 26.0));
        animRunnable = new Runnable() {
            @Override
            public void run() {
                overlay.count += step;
                map.invalidate();
                if (overlay.count >= total) {
                    animRunnable = null;
                    onDone.run();
                } else {
                    handler.postDelayed(this, 40);
                }
            }
        };
        handler.post(animRunnable);
    }

    private void stopAnimation() {
        if (animRunnable != null) handler.removeCallbacks(animRunnable);
        animRunnable = null;
    }

    private void drawRoute(Router.Result res, CostModel cm) {
        List<GeoPoint> pts = new ArrayList<>();
        for (Graph.Node n : res.path) pts.add(new GeoPoint(n.lat, n.lon));
        showLine(pts, routeColor(cm.mode));
        // let the explored-roads layer disappear shortly after the route appears (only this search's layer)
        final VisitedOverlay shown = visitedOverlay;
        handler.postDelayed(() -> {
            if (visitedOverlay == shown) removeVisited();
        }, 900);
    }

    private void showLine(List<GeoPoint> pts, int color) {
        if (routeLine != null) map.getOverlays().remove(routeLine);
        routeLine = new Polyline();
        routeLine.setPoints(pts);
        routeLine.getOutlinePaint().setColor(color);
        routeLine.getOutlinePaint().setStrokeWidth(14f);
        map.getOverlays().add(visitedOverlay != null ? 2 : 1, routeLine);
        if (lastResult == null) zoomToGeoPoints(pts); // OSRM route: frame it (local routes are framed earlier)
        map.invalidate();
    }

    private void zoomToPath(List<Graph.Node> path) {
        List<GeoPoint> pts = new ArrayList<>(path.size());
        for (Graph.Node n : path) pts.add(new GeoPoint(n.lat, n.lon));
        zoomToGeoPoints(pts);
    }

    private void zoomToGeoPoints(List<GeoPoint> pts) {
        if (pts.size() < 2) return;
        BoundingBox box = BoundingBox.fromGeoPoints(pts);
        if (box.getLatitudeSpan() < 1e-5 && box.getLongitudeSpan() < 1e-5) {
            map.getController().setZoom(17.0);
            map.getController().setCenter(pts.get(0));
        } else {
            map.zoomToBoundingBox(box, true, 160);
        }
    }

    private int routeColor(CostModel.Mode mode) {
        switch (mode) {
            case WALK:
                return ContextCompat.getColor(this, R.color.route_walk);
            case CYCLE:
                return ContextCompat.getColor(this, R.color.route_cycle);
            default:
                return ContextCompat.getColor(this, R.color.route_car);
        }
    }

    private void removeVisited() {
        if (visitedOverlay != null) {
            map.getOverlays().remove(visitedOverlay);
            visitedOverlay = null;
            map.invalidate();
        }
    }

    private void clearRouteOverlays() {
        stopAnimation();
        if (routeLine != null) {
            map.getOverlays().remove(routeLine);
            routeLine = null;
        }
        removeVisited();
        lastResult = null;
    }

    private void clearAll() {
        routeToken++;
        clearRouteOverlays();
        if (startMarker != null) map.getOverlays().remove(startMarker);
        if (destMarker != null) map.getOverlays().remove(destMarker);
        startMarker = null;
        destMarker = null;
        dest = null;
        startIsMe = true;
        if (me != null) start = me;
        rerouting = false;
        showProgress(false);
        b.etSearch.setText("");
        b.tvPrimary.setText(R.string.route_hint);
        b.tvSecondary.setVisibility(View.GONE);
        map.invalidate();
    }

    // ------------------------------------------------------------------ small UI helpers

    private void showProgress(boolean on) {
        b.progress.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void showSummary(String primary, String secondary) {
        b.tvPrimary.setText(primary);
        if (secondary == null || secondary.isEmpty()) {
            b.tvSecondary.setVisibility(View.GONE);
        } else {
            b.tvSecondary.setText(secondary);
            b.tvSecondary.setVisibility(View.VISIBLE);
        }
    }

    private void snack(String message) {
        if (b != null) Snackbar.make(b.getRoot(), message, Snackbar.LENGTH_LONG).show();
    }

    private void openExternalMaps() {
        if (dest == null) {
            snack("Choose a destination first");
            return;
        }
        // Implicit intent: Google Maps turn-by-turn if installed, otherwise any app that handles geo:
        Intent nav = new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US,
                "google.navigation:q=%.6f,%.6f", dest.getLatitude(), dest.getLongitude())));
        nav.setPackage("com.google.android.apps.maps");
        try {
            startActivity(nav);
        } catch (ActivityNotFoundException e) {
            ExternalIntents.openMaps(this, dest.getLatitude(), dest.getLongitude(), "Destination");
        }
    }

    /** Draws the nodes the search has explored so far. */
    private static class VisitedOverlay extends Overlay {
        private final List<Graph.Node> nodes;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final GeoPoint tmp = new GeoPoint(0.0, 0.0);
        private final Point pt = new Point();
        int count;

        VisitedOverlay(List<Graph.Node> nodes, int color) {
            this.nodes = nodes;
            paint.setColor(color);
            paint.setAlpha(120);
        }

        @Override
        public void draw(Canvas c, MapView mapView, boolean shadow) {
            if (shadow) return;
            Projection pr = mapView.getProjection();
            int n = Math.min(count, nodes.size());
            int skip = Math.max(1, n / 6000); // keeps drawing fast on huge searches
            for (int i = 0; i < n; i += skip) {
                Graph.Node nd = nodes.get(i);
                tmp.setCoords(nd.lat, nd.lon);
                pr.toPixels(tmp, pt);
                c.drawCircle(pt.x, pt.y, 4f, paint);
            }
        }
    }
}
