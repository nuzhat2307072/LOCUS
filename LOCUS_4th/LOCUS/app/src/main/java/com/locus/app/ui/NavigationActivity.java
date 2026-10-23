package com.locus.app.ui;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.material.chip.Chip;
import com.google.android.material.snackbar.Snackbar;
import com.locus.app.R;
import com.locus.app.data.db.LocusDatabase;
import com.locus.app.data.firebase.HazardRepository;
import com.locus.app.data.firebase.Session;
import com.locus.app.data.sqlite.LocusSqlHelper;
import com.locus.app.databinding.ActivityNavigationBinding;
import com.locus.app.graph.CostModel;
import com.locus.app.graph.Graph;
import com.locus.app.graph.RouteGuide;
import com.locus.app.graph.Router;
import com.locus.app.graph.TrafficModel;
import com.locus.app.model.Hazard;
import com.locus.app.model.HazardType;
import com.locus.app.net.HttpClient;
import com.locus.app.net.NominatimApi;
import com.locus.app.net.OsrmApi;
import com.locus.app.net.OverpassApi;
import com.locus.app.net.WeatherApi;
import com.locus.app.service.LocationTrackingService;
import com.locus.app.util.AppExecutors;
import com.locus.app.util.AppPrefs;
import com.locus.app.util.ExternalIntents;
import com.locus.app.util.GeoUtils;
import com.locus.app.util.LiveLocation;
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
import java.util.concurrent.RejectedExecutionException;

/**
 * Smart Navigation.
 *
 * The user only says WHERE (search or tap the map) and HOW (walk / cycle / car). Everything else is
 * decided by the program:
 *  - the start is the live GPS position (a self-healing feed, see LiveLocation);
 *  - rain comes from a weather service and traffic from a time-of-day / road-class model;
 *  - all sensible routes are found and drawn, congested stretches in red, the fastest is preselected;
 *  - the search algorithm is chosen inside Router;
 *  - hazards close or slow roads, and the route is recalculated when conditions change, a hazard
 *    appears on it, or the driver leaves it;
 *  - once started, turn-by-turn instructions guide the trip.
 */
public class NavigationActivity extends AppCompatActivity {
    public static final String EXTRA_LAT = "dest_lat";
    public static final String EXTRA_LON = "dest_lon";
    public static final String EXTRA_NAME = "dest_name";

    private static final double MAX_SPAN_DEG = 0.048;      // ~5 km between start and destination
    private static final double MIN_HALF_SPAN = 0.02;      // road box is at least ~4.4 km wide, so it is reusable
    private static final long HAZARD_TTL_MS = 12L * 60 * 60 * 1000;
    private static final long REROUTE_COOLDOWN_MS = 15_000;
    private static final long WEATHER_REFRESH_MS = 10L * 60 * 1000;
    private static final long TRAFFIC_REFRESH_MS = 5L * 60 * 1000;
    private static final long FIRST_FIX_WAIT_MS = 7000;
    private static final long CAMERA_THROTTLE_MS = 900;

    public static Intent intentTo(Context c, double lat, double lon, String name) {
        return new Intent(c, NavigationActivity.class)
                .putExtra(EXTRA_LAT, lat).putExtra(EXTRA_LON, lon).putExtra(EXTRA_NAME, name);
    }

    /** One way of getting there, with everything needed to draw and describe it. */
    private static final class RouteOption {
        final List<GeoPoint> points = new ArrayList<>();
        final List<Router.Run> runs;        // stretches of equal traffic level (never null)
        final double seconds;
        final double meters;
        final double trafficDelaySeconds;
        final double heavyMeters;
        final int worstLevel;
        final Router.Result result;         // null for online (OSRM) routes
        final RouteGuide guide;             // null for online (OSRM) routes
        final boolean estimated;            // traffic only estimated for the whole route

        RouteOption(List<Router.Run> runs, double seconds, double meters, double delay, double heavyMeters,
                    int worstLevel, Router.Result result, RouteGuide guide, boolean estimated) {
            this.runs = runs;
            this.seconds = seconds;
            this.meters = meters;
            this.trafficDelaySeconds = delay;
            this.heavyMeters = heavyMeters;
            this.worstLevel = worstLevel;
            this.result = result;
            this.guide = guide;
            this.estimated = estimated;
        }
    }

    private ActivityNavigationBinding b;
    private MapView map;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService routeExec = Executors.newSingleThreadExecutor(); // graph is not thread-safe
    private LocusSqlHelper sql;

    // live position
    private LiveLocation live;
    private Location lastFix;
    private boolean gotFix;
    private boolean follow = true;
    private long lastCameraMove;
    private boolean askedPermission;
    private boolean askedSettings;
    private float downX;
    private float downY;

    // points
    private GeoPoint me;
    private GeoPoint start;
    private GeoPoint dest;
    private boolean startIsMe = true;
    private boolean pendingRoute;

    // overlays
    private Marker startMarker;
    private Marker destMarker;
    private Marker meMarker;
    private Polygon accuracyCircle;
    private final List<Overlay> routeOverlays = new ArrayList<>();
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
    private List<RouteOption> options = new ArrayList<>();
    private int selected;
    private long lastRouteAt;
    private int routeToken;
    private boolean rerouting;
    private double prevMeters;

    // conditions decided by the program
    private int rainLevel;
    private boolean weatherKnown;
    private long conditionsStamp;

    // guidance
    private boolean navigating;
    private int offRouteFixes;

    // search suggestions
    private ArrayAdapter<String> suggestionAdapter;
    private final List<String> suggestionLabels = new ArrayList<>();
    private List<NominatimApi.Result> suggestionResults; // null while the list shows history
    private List<String> historyItems = new ArrayList<>();

    private final ActivityResultLauncher<String[]> locationPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), r -> {
                askedPermission = true;
                live.start();
            });

    private final ActivityResultLauncher<IntentSenderRequest> settingsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartIntentSenderForResult(), r -> live.start());

    private final ActivityResultLauncher<String[]> notificationPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), r -> startTrackingService());

    /** The foreground service re-broadcasts its fixes; used only when the live feed has gone quiet. */
    private final BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (SystemClock.elapsedRealtime() - lastFixElapsed < 4000) return;
            Location l = new Location("service");
            l.setLatitude(intent.getDoubleExtra(LocationTrackingService.EXTRA_LAT, 0));
            l.setLongitude(intent.getDoubleExtra(LocationTrackingService.EXTRA_LON, 0));
            onFix(l);
        }
    };
    private long lastFixElapsed;

    private final Runnable firstFixTimeout = () -> {
        if (start == null && dest != null) {
            start = lastKnownPoint();
            startIsMe = true;
            pendingRoute = false;
            snack(getString(R.string.gps_last_known));
            requestRoute();
        }
    };

    private final Runnable weatherRefresh = new Runnable() {
        @Override
        public void run() {
            fetchWeather();
            handler.postDelayed(this, WEATHER_REFRESH_MS);
        }
    };

    private final Runnable trafficRefresh = new Runnable() {
        @Override
        public void run() {
            updateConditionChips();
            if (dest != null && navigating) requestRoute(true); // keep the trip current while driving
            handler.postDelayed(this, TRAFFIC_REFRESH_MS);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityNavigationBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        sql = new LocusSqlHelper(this);

        live = new LiveLocation(this, new LiveLocation.Listener() {
            @Override
            public void onLocation(Location location) {
                onFix(location);
            }

            @Override
            public void onState(LiveLocation.State state) {
                updateGpsBanner();
            }

            @Override
            public void onSettingsResolution(ResolvableApiException e) {
                if (askedSettings) return;
                askedSettings = true;
                try {
                    settingsLauncher.launch(new IntentSenderRequest.Builder(e.getResolution()).build());
                } catch (RuntimeException ignored) {
                    updateGpsBanner();
                }
            }
        });

        setupMap();
        setupControls();
        setupSearch();
        setupBack();
        updateConditionChips();

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
            follow = false;
            b.fabRecenter.setVisibility(View.VISIBLE);
        }

        // Location is asked for in context, not at app start
        if (!PermissionHelper.hasLocation(this)) {
            askedPermission = true;
            locationPermissionLauncher.launch(PermissionHelper.locationPermissions());
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        hazardSub = HazardRepository.listen(this);
        ContextCompat.registerReceiver(this, locationReceiver,
                new IntentFilter(LocationTrackingService.ACTION_LOCATION_UPDATE), ContextCompat.RECEIVER_NOT_EXPORTED);
        live.start(); // continuous, self-healing position feed
        handler.post(weatherRefresh);
        handler.postDelayed(trafficRefresh, TRAFFIC_REFRESH_MS);
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
        handler.removeCallbacks(weatherRefresh);
        handler.removeCallbacks(trafficRefresh);
        if (!navigating) live.stop(); // while navigating, guidance keeps running in the background
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        routeToken++;
        live.stop();
        stopAnimation();
        handler.removeCallbacksAndMessages(null);
        routeExec.shutdownNow();
        stopService(new Intent(this, LocationTrackingService.class));
        map.onDetach();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ setup

    @SuppressLint("ClickableViewAccessibility")
    private void setupMap() {
        map = b.mapView;
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.setTilesScaledToDpi(true); // readable labels on high-density phone screens
        map.setMinZoomLevel(4.0);
        map.setMaxZoomLevel(19.5);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        map.getController().setZoom(16.0);
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
                if (navigating) return true; // no accidental new destinations while driving
                setDest(p, getString(R.string.dropped_pin), true);
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                showLongPressMenu(p);
                return true;
            }
        }));

        // Dragging the map by hand stops the camera from following the GPS dot (until "my location" is tapped)
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop() * 2;
        map.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = ev.getX();
                downY = ev.getY();
            } else if (ev.getActionMasked() == MotionEvent.ACTION_MOVE && follow
                    && (Math.abs(ev.getX() - downX) > slop || Math.abs(ev.getY() - downY) > slop)) {
                follow = false;
                b.fabRecenter.setVisibility(View.VISIBLE);
            }
            return false;
        });
    }

    private void setupControls() {
        // Travel mode: changing it recalculates automatically. Rain and traffic are not controls.
        b.modeGroup.setOnCheckedStateChangeListener((group, ids) -> requestRoute());

        b.fabRecenter.setVisibility(View.GONE); // the camera follows the GPS dot until the map is dragged
        b.btnStart.setEnabled(false);
        b.fabHazard.setOnClickListener(v -> {
            GeoPoint where = me != null ? me : lastKnownPoint();
            showHazardTypeDialog(where);
        });
        b.btnMyLoc.setOnClickListener(v -> recenter());
        b.fabRecenter.setOnClickListener(v -> recenter());
        b.tvGpsStatus.setOnClickListener(v -> fixLocationProblem());
        b.btnExternal.setOnClickListener(v -> openExternalMaps());
        b.btnClear.setOnClickListener(v -> clearAll());
        b.btnStart.setOnClickListener(v -> {
            if (navigating) confirmEndNavigation();
            else startNavigation();
        });
    }

    /** Back: closes suggestions first, then asks before ending a running navigation, then leaves the screen. */
    private void setupBack() {
        b.btnBack.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (b.cardSuggestions.getVisibility() == View.VISIBLE) {
                    hideSuggestions();
                } else if (navigating) {
                    confirmEndNavigation();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
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
            snack(getString(R.string.type_place_first));
            return;
        }
        hideKeyboard();
        showProgress(true);
        AppExecutors.io().execute(() -> sql.addHistory(q)); // SQLite insert
        HttpClient.async(this, () -> NominatimApi.search(q), new HttpClient.Callback<List<NominatimApi.Result>>() {
            @Override
            public void onSuccess(List<NominatimApi.Result> results) {
                showProgress(false);
                if (results.isEmpty()) {
                    snack(getString(R.string.nothing_found_for, q));
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

    // ------------------------------------------------------------------ live position

    private void onFix(Location loc) {
        if (isDestroyed()) return;
        lastFix = loc;
        lastFixElapsed = SystemClock.elapsedRealtime();
        final GeoPoint p = new GeoPoint(loc.getLatitude(), loc.getLongitude());
        updateMe(p, loc.hasAccuracy() ? loc.getAccuracy() : 0f);

        final boolean first = !gotFix;
        gotFix = true;
        if (startIsMe) start = p;

        if (first) {
            handler.removeCallbacks(firstFixTimeout);
            if (graph == null && !graphLoading) prefetchRoads(p);
            fetchWeather();
            if (follow) {
                map.getController().setZoom(17.0);
                map.getController().setCenter(p);
            }
            if (pendingRoute) {
                pendingRoute = false;
                requestRoute();
            } else if (dest != null && selectedOption() == null && !graphLoading) {
                requestRoute();
            }
        }
        if (follow) followCamera(p);
        if (navigating) updateGuidance(p, loc);
        updateGpsBanner();
    }

    private void followCamera(GeoPoint p) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastCameraMove < CAMERA_THROTTLE_MS) return;
        lastCameraMove = now;
        map.getController().animateTo(p);
    }

    private void recenter() {
        follow = true;
        b.fabRecenter.setVisibility(View.GONE);
        boolean wasCustomStart = !startIsMe;
        startIsMe = true;
        if (startMarker != null) {
            map.getOverlays().remove(startMarker);
            startMarker = null;
        }
        if (!PermissionHelper.hasLocation(this)) {
            fixLocationProblem();
            return;
        }
        live.start();
        if (me != null) {
            start = me;
            map.getController().setZoom(Math.max(map.getZoomLevelDouble(), 16.0));
            map.getController().animateTo(me);
            if (wasCustomStart && dest != null) requestRoute();
        } else {
            snack(getString(R.string.gps_searching));
        }
    }

    private void updateGpsBanner() {
        if (b == null || live == null) return;
        LiveLocation.State st = live.state();
        String msg = null;
        if (st == LiveLocation.State.NO_PERMISSION) {
            msg = getString(R.string.gps_no_permission);
        } else if (st == LiveLocation.State.GPS_OFF) {
            msg = getString(R.string.gps_off);
        } else if (st == LiveLocation.State.SEARCHING) {
            msg = getString(R.string.gps_searching);
        } else if (st == LiveLocation.State.OK && lastFix != null && lastFix.hasAccuracy()
                && lastFix.getAccuracy() > 60) {
            msg = getString(R.string.gps_accuracy, Math.round(lastFix.getAccuracy()));
        }
        if (msg == null) {
            b.tvGpsStatus.setVisibility(View.GONE);
        } else {
            b.tvGpsStatus.setText(msg);
            b.tvGpsStatus.setVisibility(View.VISIBLE);
        }
    }

    /** Tapping the status pill: ask for the permission, switch location on, or open the right settings page. */
    private void fixLocationProblem() {
        if (!PermissionHelper.hasLocation(this)) {
            boolean canAsk = !askedPermission
                    || shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_FINE_LOCATION);
            if (canAsk) {
                askedPermission = true;
                locationPermissionLauncher.launch(PermissionHelper.locationPermissions());
            } else { // "don't ask again": only the system settings can help
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
            }
            return;
        }
        if (live.state() == LiveLocation.State.GPS_OFF) {
            askedSettings = false;
            live.start(); // shows the "turn on location" system dialog
            handler.postDelayed(() -> {
                if (!isDestroyed() && live.state() == LiveLocation.State.GPS_OFF && askedSettings) {
                    try {
                        startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                    } catch (ActivityNotFoundException ignored) {
                        // nothing else to offer
                    }
                }
            }, 1500);
        } else {
            live.start();
        }
    }

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

    private void updateMe(GeoPoint p, float accuracyMeters) {
        me = p;
        if (meMarker == null) {
            accuracyCircle = new Polygon();
            accuracyCircle.getFillPaint().setColor(0x222196F3);
            accuracyCircle.getOutlinePaint().setColor(0x552196F3);
            accuracyCircle.getOutlinePaint().setStrokeWidth(2f);
            accuracyCircle.setOnClickListener((polygon, mapView, pos) -> false);
            map.getOverlays().add(accuracyCircle);

            meMarker = makeMarker(R.drawable.ic_pin_me, getString(R.string.you_are_here), true);
            meMarker.setInfoWindow(null);
            meMarker.setOnMarkerClickListener((m, mv) -> true);
            map.getOverlays().add(meMarker);
        }
        meMarker.setPosition(p);
        if (accuracyMeters > 3 && accuracyMeters < 1000) {
            accuracyCircle.setPoints(Polygon.pointsAsCircle(p, accuracyMeters));
        } else {
            accuracyCircle.setPoints(new ArrayList<GeoPoint>());
        }
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
                startMarker = makeMarker(R.drawable.ic_pin_start, getString(R.string.start_point), false);
                map.getOverlays().add(startMarker);
            }
            startMarker.setPosition(p);
        }
        map.invalidate();
    }

    private void setDest(GeoPoint p, String title, boolean route) {
        dest = p;
        if (destMarker == null) {
            destMarker = makeMarker(R.drawable.ic_pin_dest, getString(R.string.destination), false);
            map.getOverlays().add(destMarker);
        }
        destMarker.setTitle(title == null ? getString(R.string.destination) : title);
        destMarker.setPosition(p);
        map.invalidate();
        if (route) requestRoute();
    }

    private void showLongPressMenu(GeoPoint p) {
        if (navigating) return;
        String[] items = {getString(R.string.menu_go_here), getString(R.string.menu_start_here),
                getString(R.string.menu_report_here)};
        new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        setDest(p, getString(R.string.dropped_pin), true);
                    } else if (which == 1) {
                        setStart(p, false);
                        requestRoute();
                    } else {
                        showHazardTypeDialog(p);
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ conditions decided by the program

    private void fetchWeather() {
        final GeoPoint at = me != null ? me : (start != null ? start : lastKnownPoint());
        HttpClient.async(this, () -> WeatherApi.current(at.getLatitude(), at.getLongitude()),
                new HttpClient.Callback<WeatherApi.Weather>() {
                    @Override
                    public void onSuccess(WeatherApi.Weather w) {
                        if (isDestroyed()) return;
                        boolean changed = !weatherKnown || w.rainLevel != rainLevel;
                        weatherKnown = true;
                        rainLevel = w.rainLevel;
                        updateConditionChips();
                        if (changed && dest != null && !graphLoading) requestRoute(selectedOption() != null);
                    }

                    @Override
                    public void onError(String message) {
                        // keep the previous reading; the next refresh tries again
                    }
                });
    }

    /** Read-only chips: what the program found out about the weather and the traffic. */
    private void updateConditionChips() {
        if (b == null) return;
        int weatherText = !weatherKnown ? R.string.weather_unknown
                : rainLevel == 0 ? R.string.weather_clear
                : rainLevel == 1 ? R.string.weather_rain : R.string.weather_heavy_rain;
        b.chipWeather.setText(weatherText);
        setChipDot(b.chipWeather, !weatherKnown ? 0xFF9E9E9E : rainLevel == 0 ? 0xFF2E7D32
                : rainLevel == 1 ? 0xFFF9A825 : 0xFFD32F2F);

        int level = TrafficModel.now().cityLevel();
        b.chipTraffic.setText(level == TrafficModel.HEAVY ? R.string.traffic_heavy
                : level == TrafficModel.MODERATE ? R.string.traffic_moderate : R.string.traffic_light);
        setChipDot(b.chipTraffic, levelColor(level));
    }

    private void setChipDot(Chip chip, int color) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        int px = (int) (12 * getResources().getDisplayMetrics().density);
        dot.setSize(px, px);
        chip.setChipIcon(dot);
        chip.setChipIconSize(px);
        chip.setChipIconVisible(true);
    }

    private int levelColor(int level) {
        return ContextCompat.getColor(this, level == TrafficModel.HEAVY ? R.color.traffic_heavy
                : level == TrafficModel.MODERATE ? R.color.traffic_moderate : R.color.traffic_free);
    }

    private CostModel.Mode currentMode() {
        int id = b.modeGroup.getCheckedChipId();
        if (id == R.id.chipWalk) return CostModel.Mode.WALK;
        if (id == R.id.chipCycle) return CostModel.Mode.CYCLE;
        return CostModel.Mode.CAR;
    }

    private String modeLabel(CostModel.Mode m) {
        switch (m) {
            case WALK:
                return getString(R.string.mode_walk);
            case CYCLE:
                return getString(R.string.mode_cycle);
            default:
                return getString(R.string.mode_car);
        }
    }

    /** The program, not the user, decides rain (weather service) and traffic (time of day, road class). */
    private CostModel costModel() {
        return new CostModel(currentMode(), rainLevel, TrafficModel.now());
    }

    // ------------------------------------------------------------------ hazards

    private void showHazardTypeDialog(GeoPoint where) {
        HazardType[] types = HazardType.values();
        String[] labels = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            labels[i] = getString(types[i].blocking ? R.string.hazard_closes_road : R.string.hazard_slows_traffic,
                    getString(types[i].labelRes));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.hazard_what_is_here)
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
        snack(getString(R.string.hazard_reported, getString(t.labelRes)));
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

            Marker m = makeMarker(R.drawable.ic_pin_hazard, getString(t.labelRes), true);
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
                .setTitle(t.labelRes)
                .setMessage(getString(R.string.hazard_details, h.radiusM,
                        getString(t.blocking ? R.string.effect_closed : R.string.effect_slower), when,
                        mine ? getString(R.string.by_you) : ""))
                .setPositiveButton(android.R.string.ok, null);
        if (mine) {
            d.setNegativeButton(R.string.remove_hazard, (dialog, w) -> {
                HazardRepository.delete(this, h);
                if (dest != null) requestRoute(); // roads reopen -> better route may exist
            });
        }
        d.show();
    }

    private boolean routeNear(Hazard h) {
        RouteOption o = selectedOption();
        if (o == null || o.result == null || !o.result.found) return false;
        List<Graph.Node> path = o.result.path;
        for (int i = 0; i + 1 < path.size(); i++) {
            Graph.Node a = path.get(i);
            Graph.Node c = path.get(i + 1);
            if (GeoUtils.distanceToSegment(h.lat, h.lon, a.lat, a.lon, c.lat, c.lon) <= h.radiusM + 20) return true;
        }
        return false;
    }

    private void rerouteBecauseOf(Hazard h) {
        snack(getString(R.string.hazard_on_route, getString(HazardType.fromName(h.type).labelRes)));
        rerouting = true;
        RouteOption o = selectedOption();
        prevMeters = o == null ? 0 : o.meters;
        requestRoute();
    }

    // ------------------------------------------------------------------ routing

    private RouteOption selectedOption() {
        return selected >= 0 && selected < options.size() ? options.get(selected) : null;
    }

    private void requestRoute() {
        requestRoute(false);
    }

    /**
     * Single entry point: (re)calculates every route for the current start, destination, mode, weather
     * and traffic. silent = refresh in the background without clearing the map or replaying the search.
     */
    private void requestRoute(boolean silent) {
        if (b == null || dest == null) return;
        if (start == null) { // no position yet: wait for the first fix, but never forever
            if (PermissionHelper.hasLocation(this)) {
                pendingRoute = true;
                showProgress(true);
                showSummary(getString(R.string.gps_searching), null);
                handler.removeCallbacks(firstFixTimeout);
                handler.postDelayed(firstFixTimeout, FIRST_FIX_WAIT_MS);
            } else {
                firstFixTimeout.run();
            }
            return;
        }
        final int token = ++routeToken;
        if (!silent) {
            clearRouteOverlays();
            showProgress(true);
            showSummary(getString(R.string.finding_route), null);
            b.btnStart.setEnabled(false);
        }

        final double[] box = boxFor(start, dest);
        if (box == null) { // too far for the local road graph -> online routing, chosen automatically
            routeWithOsrm(token, silent);
            return;
        }
        if (graph != null && graphBox != null && covers(graphBox, start) && covers(graphBox, dest)) {
            computeRoute(token, silent);
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
        if (thenRoute) showSummary(getString(R.string.loading_roads), null);
        HttpClient.async(this, () -> OverpassApi.fetchRoadNetwork(box[0], box[1], box[2], box[3]),
                new HttpClient.Callback<Graph>() {
                    @Override
                    public void onSuccess(Graph g) {
                        graphLoading = false;
                        if (isDestroyed()) return;
                        graph = g;
                        graphBox = box;
                        if (dest != null && start != null && (routeAfterLoad || selectedOption() == null)) {
                            requestRoute();
                        }
                    }

                    @Override
                    public void onError(String message) {
                        graphLoading = false;
                        if (isDestroyed() || !routeAfterLoad) return; // a failed prefetch is not worth bothering the user
                        showProgress(false);
                        if (currentMode() == CostModel.Mode.CAR) {
                            routeWithOsrm(routeToken, false); // driving: fall back to the online router automatically
                        } else {
                            showSummary(getString(R.string.err_roads_load), message);
                            Snackbar.make(b.getRoot(), R.string.err_roads_load, Snackbar.LENGTH_INDEFINITE)
                                    .setAction(R.string.retry, v -> requestRoute()).show();
                        }
                    }
                });
    }

    private void computeRoute(final int token, final boolean silent) {
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
                boolean noRoad = sn == null || dn == null
                        || GeoUtils.distance(sn.lat, sn.lon, s.getLatitude(), s.getLongitude()) > 600
                        || GeoUtils.distance(dn.lat, dn.lon, d.getLatitude(), d.getLongitude()) > 600;
                // 3) every sensible route; the program picks the algorithm and the fastest one first
                final List<RouteOption> found = new ArrayList<>();
                if (!noRoad) {
                    CostModel dry = new CostModel(cm.mode, cm.rainLevel, null); // same trip without traffic
                    for (Router.Result r : Router.alternatives(g, sn.id, dn.id, cm, 3)) {
                        found.add(buildOption(g, r, cm, dry));
                    }
                }
                final int hazardsConsidered = considered;
                final boolean failedToSnap = noRoad;
                AppExecutors.main().post(() -> {
                    if (token == routeToken && !isDestroyed()) {
                        onRoutesComputed(found, failedToSnap, cm, hazardsConsidered, silent);
                    }
                });
            });
        } catch (RejectedExecutionException ignored) {
            // activity is closing
        }
    }

    private static RouteOption buildOption(Graph g, Router.Result r, CostModel cm, CostModel dry) {
        List<Router.Run> runs = Router.runs(g, r.path, cm);
        int worst = TrafficModel.FREE;
        double heavy = 0;
        for (Router.Run run : runs) {
            worst = Math.max(worst, run.level);
            if (run.level == TrafficModel.HEAVY) {
                for (int i = 0; i + 1 < run.points.size(); i++) {
                    double[] a = run.points.get(i);
                    double[] c = run.points.get(i + 1);
                    heavy += Graph.haversine(a[0], a[1], c[0], c[1]);
                }
            }
        }
        double delay = Math.max(0, r.costSeconds - Router.costOf(g, r.path, dry));
        RouteOption o = new RouteOption(runs, r.costSeconds, r.distanceMeters, delay, heavy, worst, r,
                new RouteGuide(g, r.path), false);
        for (Graph.Node n : r.path) o.points.add(new GeoPoint(n.lat, n.lon));
        return o;
    }

    private void onRoutesComputed(List<RouteOption> found, boolean failedToSnap, CostModel cm,
                                  int hazardsConsidered, boolean silent) {
        showProgress(false);
        lastRouteAt = System.currentTimeMillis();
        if (failedToSnap) {
            rerouting = false;
            if (!silent) {
                options = new ArrayList<>();
                showSummary(getString(R.string.err_no_road, modeLabel(cm.mode).toLowerCase(Locale.getDefault())), null);
            }
            return;
        }
        if (found.isEmpty()) {
            rerouting = false;
            options = new ArrayList<>();
            clearRouteOverlays();
            showSummary(getString(R.string.err_no_route, modeLabel(cm.mode).toLowerCase(Locale.getDefault())),
                    getString(R.string.err_roads_closed));
            return;
        }
        if (found.get(0).points.size() < 2) {
            rerouting = false;
            options = new ArrayList<>();
            showSummary(getString(R.string.err_already_there), null);
            return;
        }
        options = found;
        selected = 0;
        hazardCount = hazardsConsidered;

        final Router.Result best = found.get(0).result;
        final Runnable show = () -> {
            drawRoutes();
            buildRouteChips();
            showSelected();
            b.btnStart.setEnabled(!navigating);
            if (rerouting) {
                double diff = found.get(0).meters - prevMeters;
                snack(getString(R.string.rerouted_around, (diff >= 0 ? "+" : "-")
                        + GeoUtils.distanceText(this, Math.abs(diff))));
                rerouting = false;
            }
        };
        if (silent || navigating) {
            show.run();
        } else {
            zoomToGeoPoints(found.get(0).points);
            showSummary(getString(R.string.finding_route), null);
            animateSearch(best, cm, show);
        }
    }

    private int hazardCount;

    /** Long trips: the online OSRM server (driving) takes over automatically. */
    private void routeWithOsrm(final int token, final boolean silent) {
        final GeoPoint s = start;
        final GeoPoint d = dest;
        final CostModel.Mode mode = currentMode();
        final int rain = rainLevel;
        HttpClient.async(this, () -> OsrmApi.routes(s.getLatitude(), s.getLongitude(), d.getLatitude(), d.getLongitude()),
                new HttpClient.Callback<List<OsrmApi.Route>>() {
                    @Override
                    public void onSuccess(List<OsrmApi.Route> routes) {
                        if (token != routeToken || isDestroyed()) return;
                        showProgress(false);
                        lastRouteAt = System.currentTimeMillis();
                        // OSRM knows no live traffic: apply the same time-of-day estimate to the whole trip
                        int level = mode == CostModel.Mode.CAR ? TrafficModel.now().cityLevel() : TrafficModel.FREE;
                        double factor = level == TrafficModel.HEAVY ? 1.6 : level == TrafficModel.MODERATE ? 1.25 : 1.0;
                        factor *= 1.0 + 0.08 * rain;
                        List<RouteOption> list = new ArrayList<>();
                        for (OsrmApi.Route r : routes) {
                            Router.Run run = new Router.Run(level);
                            run.points.addAll(r.points);
                            List<Router.Run> runs = new ArrayList<>();
                            runs.add(run);
                            double secs = r.durationSeconds * factor;
                            RouteOption o = new RouteOption(runs, secs, r.distanceMeters, secs - r.durationSeconds,
                                    level == TrafficModel.HEAVY ? r.distanceMeters : 0, level, null, null, true);
                            for (double[] p : r.points) o.points.add(new GeoPoint(p[0], p[1]));
                            list.add(o);
                        }
                        java.util.Collections.sort(list, (a, c) -> Double.compare(a.seconds, c.seconds));
                        options = list;
                        selected = 0;
                        hazardCount = 0;
                        drawRoutes();
                        buildRouteChips();
                        if (!silent && !navigating) zoomToGeoPoints(list.get(0).points);
                        showSelected();
                        b.btnStart.setEnabled(!navigating);
                    }

                    @Override
                    public void onError(String message) {
                        if (token != routeToken || isDestroyed()) return;
                        showProgress(false);
                        showSummary(getString(R.string.err_route_failed), message);
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

    // ------------------------------------------------------------------ route options + drawing

    private void selectOption(int index) {
        if (index < 0 || index >= options.size() || index == selected) return;
        selected = index;
        drawRoutes();
        showSelected();
    }

    private void buildRouteChips() {
        b.routeGroup.setOnCheckedStateChangeListener(null);
        b.routeGroup.removeAllViews();
        boolean show = options.size() > 1 && !navigating;
        b.routeScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        for (int i = 0; i < options.size(); i++) {
            RouteOption o = options.get(i);
            Chip chip = new Chip(this);
            chip.setId(View.generateViewId());
            chip.setCheckable(true);
            chip.setText(getString(R.string.route_chip,
                    i == 0 ? getString(R.string.route_fastest) : getString(R.string.route_number, i + 1),
                    GeoUtils.durationText(this, o.seconds)));
            setChipDot(chip, levelColor(o.worstLevel));
            chip.setTag(i);
            b.routeGroup.addView(chip);
            if (i == selected) chip.setChecked(true);
        }
        b.routeGroup.setOnCheckedStateChangeListener((group, ids) -> {
            if (ids.isEmpty()) return;
            Chip chip = group.findViewById(ids.get(0));
            if (chip != null && chip.getTag() instanceof Integer) selectOption((Integer) chip.getTag());
        });
    }

    /** Every route is drawn; congested stretches are red, the chosen one is thicker with a white outline. */
    private void drawRoutes() {
        for (Overlay o : routeOverlays) map.getOverlays().remove(o);
        routeOverlays.clear();
        int index = 1 + (visitedOverlay != null ? 1 : 0);
        // the others first, so the chosen route is on top
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < options.size(); i++) {
                boolean isSelected = i == selected;
                if (isSelected != (pass == 1)) continue;
                if (navigating && !isSelected) continue;
                index = addRouteOverlays(options.get(i), i, isSelected, index);
            }
        }
        map.invalidate();
    }

    private int addRouteOverlays(RouteOption o, final int optionIndex, boolean isSelected, int index) {
        if (isSelected) {
            List<GeoPoint> all = o.points;
            Polyline casing = new Polyline();
            casing.setPoints(all);
            casing.getOutlinePaint().setColor(ContextCompat.getColor(this, R.color.route_casing));
            casing.getOutlinePaint().setStrokeWidth(24f);
            casing.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
            casing.setOnClickListener((pl, mv, pos) -> false);
            map.getOverlays().add(index++, casing);
            routeOverlays.add(casing);
        }
        CostModel.Mode mode = currentMode();
        for (Router.Run run : o.runs) {
            List<GeoPoint> pts = new ArrayList<>(run.points.size());
            for (double[] p : run.points) pts.add(new GeoPoint(p[0], p[1]));
            Polyline line = new Polyline();
            line.setPoints(pts);
            // green / amber / red for traffic; walking and cycling keep their own colour while the way is clear
            int color = mode == CostModel.Mode.CAR || run.level != TrafficModel.FREE
                    ? levelColor(run.level) : routeColor(mode);
            if (!isSelected) color = (color & 0x00FFFFFF) | 0x99000000;
            line.getOutlinePaint().setColor(color);
            line.getOutlinePaint().setStrokeWidth(isSelected ? 15f : 9f);
            line.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
            if (isSelected) {
                line.setOnClickListener((pl, mv, pos) -> false);
            } else {
                line.setOnClickListener((pl, mv, pos) -> {
                    selectOption(optionIndex);
                    buildRouteChips();
                    return true;
                });
            }
            map.getOverlays().add(index++, line);
            routeOverlays.add(line);
        }
        return index;
    }

    /** Summary of the chosen route: time, distance, arrival, and what traffic / weather / hazards do to it. */
    private void showSelected() {
        RouteOption o = selectedOption();
        if (o == null) return;
        String primary = getString(R.string.route_summary, GeoUtils.durationText(this, o.seconds),
                GeoUtils.distanceText(this, o.meters));
        DateFormat tf = DateFormat.getTimeInstance(DateFormat.SHORT);
        List<String> parts = new ArrayList<>();
        parts.add(modeLabel(currentMode()));
        parts.add(getString(R.string.route_arrive_at, tf.format(new Date(System.currentTimeMillis()
                + (long) (o.seconds * 1000)))));
        if (currentMode() != CostModel.Mode.WALK) {
            if (o.trafficDelaySeconds >= 60) {
                parts.add(getString(R.string.route_traffic_delay,
                        GeoUtils.durationText(this, o.trafficDelaySeconds)));
            } else {
                parts.add(getString(R.string.route_traffic_none));
            }
            if (o.heavyMeters >= 150) {
                parts.add(getString(R.string.route_traffic_heavy_part, GeoUtils.distanceText(this, o.heavyMeters)));
            }
        }
        if (rainLevel > 0) parts.add(getString(R.string.route_rain_slows));
        if (hazardCount > 0) {
            parts.add(getResources().getQuantityString(R.plurals.avoiding_hazards, hazardCount, hazardCount));
        }
        if (o.estimated) parts.add(getString(R.string.long_trip_main_roads));
        showSummary(primary, join(parts));
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (sb.length() > 0) sb.append("  -  ");
            sb.append(s);
        }
        return sb.toString();
    }

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
                    // let the explored-roads layer disappear shortly after the routes appear
                    handler.postDelayed(() -> {
                        if (visitedOverlay == overlay) removeVisited();
                    }, 900);
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

    private void zoomToGeoPoints(List<GeoPoint> pts) {
        if (pts.size() < 2) return;
        follow = false;
        b.fabRecenter.setVisibility(View.VISIBLE);
        BoundingBox box = BoundingBox.fromGeoPoints(pts);
        if (box.getLatitudeSpan() < 1e-5 && box.getLongitudeSpan() < 1e-5) {
            map.getController().setZoom(17.0);
            map.getController().setCenter(pts.get(0));
        } else {
            map.zoomToBoundingBox(box, true, 200);
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
        for (Overlay o : routeOverlays) map.getOverlays().remove(o);
        routeOverlays.clear();
        removeVisited();
        options = new ArrayList<>();
        selected = 0;
        b.routeScroll.setVisibility(View.GONE);
        b.routeGroup.removeAllViews();
    }

    private void clearAll() {
        if (navigating) endNavigation();
        routeToken++;
        pendingRoute = false;
        handler.removeCallbacks(firstFixTimeout);
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
        b.btnStart.setEnabled(false);
        showSummary(getString(R.string.route_hint), null);
        map.invalidate();
    }

    // ------------------------------------------------------------------ guidance (turn-by-turn)

    private void startNavigation() {
        RouteOption o = selectedOption();
        if (o == null) return;
        navigating = true;
        offRouteFixes = 0;
        follow = true;
        b.fabRecenter.setVisibility(View.GONE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        b.cardGuidance.setVisibility(View.VISIBLE);
        b.btnStart.setText(R.string.end_navigation);
        b.btnStart.setEnabled(true);
        buildRouteChips();
        drawRoutes();
        live.start();

        String[] notif = PermissionHelper.notificationPermissions();
        if (notif.length > 0 && !PermissionHelper.hasNotifications(this)) {
            notificationPermissionLauncher.launch(notif); // the trip continues even if the user says no
        } else {
            startTrackingService();
        }
        if (me != null) {
            map.getController().setZoom(18.0);
            map.getController().animateTo(me);
            updateGuidance(me, lastFix);
        } else {
            b.tvTurnDistance.setText(R.string.gps_searching);
            b.tvTurnText.setText("");
        }
        showSelected();
    }

    private void confirmEndNavigation() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.end_navigation_title)
                .setMessage(R.string.end_navigation_message)
                .setNegativeButton(R.string.keep_going, null)
                .setPositiveButton(R.string.end_navigation, (d, w) -> endNavigation())
                .show();
    }

    private void endNavigation() {
        navigating = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        b.cardGuidance.setVisibility(View.GONE);
        b.btnStart.setText(R.string.start_navigation);
        b.btnStart.setEnabled(selectedOption() != null);
        stopService(new Intent(this, LocationTrackingService.class));
        buildRouteChips();
        drawRoutes();
        if (selectedOption() != null) showSelected();
    }

    private void startTrackingService() {
        if (!navigating) return;
        Intent i = new Intent(this, LocationTrackingService.class).setAction(LocationTrackingService.ACTION_START);
        try {
            ContextCompat.startForegroundService(this, i);
        } catch (RuntimeException ignored) {
            // the system may refuse a background start; guidance still works while the screen is open
        }
    }

    private void updateGuidance(GeoPoint p, Location loc) {
        RouteOption o = selectedOption();
        if (o == null || dest == null) return;
        double remaining;
        RouteGuide.Step next = null;
        double toNext = 0;
        if (o.guide != null) {
            RouteGuide.Position pos = o.guide.locate(p.getLatitude(), p.getLongitude());
            double accuracy = loc != null && loc.hasAccuracy() ? loc.getAccuracy() : 0;
            double tolerance = Math.max(50, accuracy * 1.5);
            if (startIsMe && pos.off > tolerance) {
                offRouteFixes++;
                if (offRouteFixes >= 2 && System.currentTimeMillis() - lastRouteAt > REROUTE_COOLDOWN_MS) {
                    offRouteFixes = 0;
                    snack(getString(R.string.off_route));
                    requestRoute(true);
                    return;
                }
            } else {
                offRouteFixes = 0;
            }
            remaining = Math.max(0, o.guide.totalMeters() - pos.along);
            next = o.guide.nextStep(pos.along);
            if (next != null) toNext = Math.max(0, next.atMeters - pos.along);
        } else {
            remaining = GeoUtils.distance(p.getLatitude(), p.getLongitude(), dest.getLatitude(), dest.getLongitude());
        }

        double destDistance = GeoUtils.distance(p.getLatitude(), p.getLongitude(), dest.getLatitude(), dest.getLongitude());
        if (destDistance < 35 || remaining < 20) {
            snack(getString(R.string.arrived));
            endNavigation();
            return;
        }

        if (next != null) {
            b.ivTurn.setImageResource(next.maneuver == RouteGuide.Maneuver.ARRIVE
                    ? R.drawable.ic_pin_dest : R.drawable.ic_arrow_up);
            b.ivTurn.setRotation(rotationFor(next.maneuver));
            b.tvTurnDistance.setText(getString(R.string.turn_in_distance, GeoUtils.distanceText(this, toNext)));
            b.tvTurnText.setText(instruction(next));
        } else {
            b.ivTurn.setImageResource(R.drawable.ic_arrow_up);
            b.ivTurn.setRotation(0);
            b.tvTurnDistance.setText(GeoUtils.distanceText(this, remaining));
            b.tvTurnText.setText(R.string.turn_straight);
        }
        double secondsLeft = o.meters <= 0 ? 0 : o.seconds * Math.min(1.0, remaining / o.meters);
        DateFormat tf = DateFormat.getTimeInstance(DateFormat.SHORT);
        b.tvPrimary.setText(getString(R.string.route_summary,
                GeoUtils.durationText(this, secondsLeft), getString(R.string.remaining_left,
                        GeoUtils.distanceText(this, remaining))));
        b.tvSecondary.setText(getString(R.string.route_arrive_at,
                tf.format(new Date(System.currentTimeMillis() + (long) (secondsLeft * 1000)))));
        b.tvSecondary.setVisibility(View.VISIBLE);
    }

    private String instruction(RouteGuide.Step s) {
        int res;
        switch (s.maneuver) {
            case STRAIGHT:
                res = R.string.turn_straight;
                break;
            case SLIGHT_LEFT:
                res = R.string.turn_slight_left;
                break;
            case LEFT:
                res = R.string.turn_left;
                break;
            case SHARP_LEFT:
                res = R.string.turn_sharp_left;
                break;
            case SLIGHT_RIGHT:
                res = R.string.turn_slight_right;
                break;
            case RIGHT:
                res = R.string.turn_right;
                break;
            case SHARP_RIGHT:
                res = R.string.turn_sharp_right;
                break;
            case UTURN:
                res = R.string.turn_uturn;
                break;
            case ARRIVE:
                return getString(R.string.turn_arrive);
            default:
                res = R.string.turn_depart;
        }
        String base = getString(res);
        return s.street.isEmpty() ? base : getString(R.string.turn_with_street, base, s.street);
    }

    private static float rotationFor(RouteGuide.Maneuver m) {
        switch (m) {
            case SLIGHT_LEFT:
                return -45f;
            case LEFT:
                return -90f;
            case SHARP_LEFT:
                return -135f;
            case SLIGHT_RIGHT:
                return 45f;
            case RIGHT:
                return 90f;
            case SHARP_RIGHT:
                return 135f;
            case UTURN:
                return 180f;
            default:
                return 0f;
        }
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
            snack(getString(R.string.choose_destination_first));
            return;
        }
        // Implicit intent: Google Maps turn-by-turn if installed, otherwise any app that handles geo:
        Intent nav = new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US,
                "google.navigation:q=%.6f,%.6f", dest.getLatitude(), dest.getLongitude())));
        nav.setPackage("com.google.android.apps.maps");
        try {
            startActivity(nav);
        } catch (ActivityNotFoundException e) {
            ExternalIntents.openMaps(this, dest.getLatitude(), dest.getLongitude(), getString(R.string.destination));
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
