package com.locus.app.ui;

import android.app.Activity;
import android.content.Context;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.locus.app.R;
import com.locus.app.data.sqlite.LocusSqlHelper;
import com.locus.app.databinding.ItemStartPlaceBinding;
import com.locus.app.databinding.SheetStartPickerBinding;
import com.locus.app.net.GeoRank;
import com.locus.app.net.GeoSearch;
import com.locus.app.net.HttpClient;
import com.locus.app.util.AppExecutors;
import com.locus.app.util.AppPrefs;
import com.locus.app.util.GeoUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * "From where?" sheet of Smart Navigation: use the current GPS location, or set any place (search it or choose
 * it on the map). Every place that is set is saved automatically and can be removed.
 */
final class StartPicker {
    interface Host {
        void onUseCurrent();

        void onUseSet(String name, double lat, double lon);

        void onPickOnMap();

        /** Where to look first when searching (the current start, or the GPS position). */
        double[] biasPoint();
    }

    private StartPicker() {
    }

    static final int MODE_NAVIGATION = 0;
    static final int MODE_TRIP_SOURCE = 1;
    static final int MODE_TRIP_DESTINATION = 2;

    static void show(Activity activity, LocusSqlHelper sql, Host host) {
        show(activity, sql, host, MODE_NAVIGATION);
    }

    /** The trip calendar uses the same sheet: a source (current or a set place) and a destination (search only). */
    static void show(Activity activity, LocusSqlHelper sql, Host host, int mode) {
        final SheetStartPickerBinding b = SheetStartPickerBinding.inflate(LayoutInflater.from(activity));
        final BottomSheetDialog dialog = new BottomSheetDialog(activity);
        dialog.setContentView(b.getRoot());
        dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);

        if (mode != MODE_NAVIGATION) b.btnPickOnMap.setVisibility(View.GONE); // no map pin in the calendar
        if (mode == MODE_TRIP_DESTINATION) {
            b.tvPickerTitle.setText(R.string.trip_pick_destination);
            b.rowCurrent.setVisibility(View.GONE);
        } else if (mode == MODE_TRIP_SOURCE) {
            b.tvPickerTitle.setText(R.string.trip_pick_source);
        }
        final boolean currentSelected = !AppPrefs.originIsSet(activity);
        b.ivCurrentCheck.setVisibility(currentSelected ? View.VISIBLE : View.INVISIBLE);
        b.rowCurrent.setOnClickListener(v -> {
            host.onUseCurrent();
            dialog.dismiss();
        });
        b.btnPickOnMap.setOnClickListener(v -> {
            host.onPickOnMap();
            dialog.dismiss();
        });

        // ---- search a place to start from
        final List<GeoRank.Hit> results = new ArrayList<>();
        final ArrayAdapter<String> resultAdapter = new ArrayAdapter<>(activity, R.layout.item_suggestion, R.id.tvText,
                new ArrayList<String>());
        b.listStartResults.setAdapter(resultAdapter);
        b.listStartResults.setOnItemClickListener((p, v, position, id) -> {
            GeoRank.Hit h = results.get(position);
            hideKeyboard(activity, b.etStartSearch);
            host.onUseSet(h.name, h.lat, h.lon);
            dialog.dismiss();
        });
        final Runnable search = () -> {
            String q = b.etStartSearch.getText() == null ? "" : b.etStartSearch.getText().toString().trim();
            if (q.length() < 2) return;
            hideKeyboard(activity, b.etStartSearch);
            b.progressStart.setVisibility(View.VISIBLE);
            b.tvStartMessage.setVisibility(View.GONE);
            final double[] bias = host.biasPoint();
            HttpClient.async(activity, () -> GeoSearch.search(q, bias[0], bias[1]), new HttpClient.Callback<List<GeoRank.Hit>>() {
                @Override
                public void onSuccess(List<GeoRank.Hit> hits) {
                    if (activity.isDestroyed()) return;
                    b.progressStart.setVisibility(View.GONE);
                    results.clear();
                    results.addAll(hits);
                    resultAdapter.clear();
                    for (GeoRank.Hit h : hits) {
                        resultAdapter.add(h.label() + "  -  " + GeoUtils.distanceText(activity, h.distanceM));
                    }
                    b.listStartResults.setVisibility(hits.isEmpty() ? View.GONE : View.VISIBLE);
                    if (hits.isEmpty()) {
                        b.tvStartMessage.setText(activity.getString(R.string.nothing_found_for, q));
                        b.tvStartMessage.setVisibility(View.VISIBLE);
                    }
                }

                @Override
                public void onError(String message) {
                    if (activity.isDestroyed()) return;
                    b.progressStart.setVisibility(View.GONE);
                    b.tvStartMessage.setText(message);
                    b.tvStartMessage.setVisibility(View.VISIBLE);
                }
            });
        };
        b.btnStartSearch.setOnClickListener(v -> search.run());
        b.etStartSearch.setOnEditorActionListener((tv, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                search.run();
                return true;
            }
            return false;
        });

        // ---- saved places (added automatically when a start place is set; removable)
        final List<LocusSqlHelper.StartPlace> saved = new ArrayList<>();
        final ArrayAdapter<LocusSqlHelper.StartPlace> savedAdapter =
                new ArrayAdapter<LocusSqlHelper.StartPlace>(activity, R.layout.item_start_place, saved) {
                    @NonNull
                    @Override
                    public View getView(int position, @Nullable View convert, @NonNull ViewGroup parent) {
                        ItemStartPlaceBinding row = convert == null
                                ? ItemStartPlaceBinding.inflate(LayoutInflater.from(getContext()), parent, false)
                                : ItemStartPlaceBinding.bind(convert);
                        final LocusSqlHelper.StartPlace sp = getItem(position);
                        row.tvName.setText(sp.name);
                        // The whole row sets this place as the start. (A ListView item click is swallowed by the
                        // focusable delete button inside the row, so the row handles its own tap.)
                        row.getRoot().setOnClickListener(v -> {
                            host.onUseSet(sp.name, sp.lat, sp.lon);
                            dialog.dismiss();
                        });
                        row.btnDelete.setOnClickListener(v -> {
                            AppExecutors.io().execute(() -> sql.deleteStartPlace(sp.id));
                            remove(sp);
                            refreshSavedViews(b, saved);
                            if (AppPrefs.originIsSet(activity) && sameSpot(AppPrefs.originPoint(activity), sp)) {
                                host.onUseCurrent(); // the active start was removed: go back to the GPS location
                                dialog.dismiss();
                            }
                        });
                        return row.getRoot();
                    }
                };
        b.listSaved.setAdapter(savedAdapter);
        b.btnClearSaved.setOnClickListener(v -> {
            AppExecutors.io().execute(sql::clearStartPlaces);
            saved.clear();
            savedAdapter.notifyDataSetChanged();
            refreshSavedViews(b, saved);
        });
        AppExecutors.io().execute(() -> {
            final List<LocusSqlHelper.StartPlace> list = sql.startPlaces(30);
            AppExecutors.main().post(() -> {
                if (activity.isDestroyed()) return;
                saved.clear();
                saved.addAll(list);
                savedAdapter.notifyDataSetChanged();
                refreshSavedViews(b, saved);
            });
        });
        dialog.show();
    }

    private static boolean sameSpot(double[] point, LocusSqlHelper.StartPlace sp) {
        return GeoUtils.distance(point[0], point[1], sp.lat, sp.lon) < 15;
    }

    private static void refreshSavedViews(SheetStartPickerBinding b, List<LocusSqlHelper.StartPlace> saved) {
        boolean empty = saved.isEmpty();
        b.tvSavedEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        b.listSaved.setVisibility(empty ? View.GONE : View.VISIBLE);
        b.btnClearSaved.setVisibility(empty ? View.GONE : View.VISIBLE);
        // the sheet scrolls as a whole: give the list the height of its rows
        ViewGroup.LayoutParams lp = b.listSaved.getLayoutParams();
        int rowPx = (int) (56 * b.getRoot().getResources().getDisplayMetrics().density);
        lp.height = Math.min(saved.size(), 6) * rowPx;
        b.listSaved.setLayoutParams(lp);
    }

    private static void hideKeyboard(Context c, View v) {
        InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }
}
