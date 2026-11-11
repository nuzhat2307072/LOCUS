package com.locus.app.ui;

import android.net.Uri;
import android.os.Bundle;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.locus.app.R;
import com.locus.app.data.db.LocusDatabase;
import com.locus.app.data.firebase.Session;
import com.locus.app.databinding.ActivityRequestMapBinding;
import com.locus.app.model.CommunityRequest;
import com.locus.app.model.RequestType;
import com.locus.app.util.AppPrefs;
import com.locus.app.util.ExternalIntents;
import com.locus.app.util.ThemeHelper;

import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.overlay.Marker;

import java.util.ArrayList;
import java.util.List;

/** Every open Community request that has a place, shown on one map. Tap a pin for details, directions or a message. */
public class RequestMapActivity extends AppCompatActivity {
    private ActivityRequestMapBinding b;
    private boolean fitted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityRequestMapBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        b.requestMap.setTileSource(TileSourceFactory.MAPNIK);
        b.requestMap.setMultiTouchControls(true);
        b.requestMap.setTilesScaledToDpi(true);
        b.requestMap.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        double[] last = AppPrefs.lastLocation(this);
        b.requestMap.getController().setZoom(13.0);
        b.requestMap.getController().setCenter(new GeoPoint(last[0], last[1]));

        LocusDatabase.get(this).requestDao().observeAll().observe(this, this::showRequests);
    }

    private void showRequests(List<CommunityRequest> all) {
        b.requestMap.getOverlays().clear();
        List<GeoPoint> points = new ArrayList<>();
        for (CommunityRequest r : all) {
            if (!r.hasLocation || !CommunityRequest.OPEN.equals(r.status)) continue;
            GeoPoint p = new GeoPoint(r.lat, r.lon);
            points.add(p);
            Marker m = new Marker(b.requestMap);
            m.setPosition(p);
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            m.setIcon(ContextCompat.getDrawable(this, r.isSos() ? R.drawable.ic_pin_hazard : R.drawable.ic_pin_dest));
            m.setTitle(r.title);
            m.setSnippet(getString(RequestType.fromName(r.type).labelRes));
            m.setInfoWindow(null);
            m.setOnMarkerClickListener((marker, mv) -> {
                showRequest(r);
                return true;
            });
            b.requestMap.getOverlays().add(m);
        }
        b.tvMapEmpty.setVisibility(points.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
        if (!fitted && points.size() > 1) {
            fitted = true;
            final BoundingBox box = BoundingBox.fromGeoPoints(points);
            b.requestMap.post(() -> b.requestMap.zoomToBoundingBox(box.increaseByScale(1.3f), false));
        } else if (!fitted && points.size() == 1) {
            fitted = true;
            b.requestMap.getController().setCenter(points.get(0));
            b.requestMap.getController().setZoom(15.0);
        }
        b.requestMap.invalidate();
    }

    private void showRequest(final CommunityRequest r) {
        boolean mine = r.ownerUid != null && r.ownerUid.equals(Session.uid(this));
        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle(r.title)
                .setMessage(getString(R.string.request_details, getString(RequestType.fromName(r.type).labelRes),
                        r.description, r.contact, r.ownerName))
                .setPositiveButton(R.string.action_navigate, (dialog, w) ->
                        startActivity(NavigationActivity.intentTo(this, r.lat, r.lon, r.title)))
                .setNegativeButton(android.R.string.cancel, null);
        if (!mine) d.setNeutralButton(R.string.chat_message_button, (dialog, w) -> ChatLauncher.start(this, r));
        d.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        b.requestMap.onResume();
        ThemeHelper.applyToMap(b.requestMap);
    }

    @Override
    protected void onPause() {
        b.requestMap.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        b.requestMap.onDetach();
        super.onDestroy();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
