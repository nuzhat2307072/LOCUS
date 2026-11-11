package com.locus.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.locus.app.R;
import com.locus.app.data.db.LocusDatabase;
import com.locus.app.databinding.ActivityTripCalendarBinding;
import com.locus.app.databinding.ItemCalDayBinding;
import com.locus.app.databinding.ItemTripBinding;
import com.locus.app.model.Trip;
import com.locus.app.model.TripRules;
import com.locus.app.util.AppExecutors;
import com.locus.app.util.AppPrefs;
import com.locus.app.util.TripReminders;

import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Month calendar: days with a planned trip are marked; tap a day to see its trips; the button adds a trip. */
public class TripCalendarActivity extends AppCompatActivity {
    /** Open on the day of this moment (UTC milliseconds). */
    public static final String EXTRA_DAY = "day";

    private ActivityTripCalendarBinding b;
    private final TimeZone tz = TimeZone.getDefault();
    private int year;
    private int month0;
    private int selectedDay;
    private int[] cells = new int[42];
    private final Map<Integer, Integer> tripsPerDay = new HashMap<>();
    private final List<Trip> dayTrips = new ArrayList<>();
    private DaysAdapter daysAdapter;
    private TripsAdapter tripsAdapter;
    private int loadToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityTripCalendarBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        Calendar now = Calendar.getInstance(tz);
        long focus = getIntent().getLongExtra(EXTRA_DAY, 0);
        if (focus > 0) now.setTimeInMillis(focus);
        year = now.get(Calendar.YEAR);
        month0 = now.get(Calendar.MONTH);
        selectedDay = now.get(Calendar.DAY_OF_MONTH);

        buildWeekdays();
        daysAdapter = new DaysAdapter();
        b.rvDays.setLayoutManager(new GridLayoutManager(this, 7));
        b.rvDays.setAdapter(daysAdapter);
        tripsAdapter = new TripsAdapter();
        b.rvTrips.setLayoutManager(new LinearLayoutManager(this));
        b.rvTrips.setAdapter(tripsAdapter);

        b.btnPrevMonth.setOnClickListener(v -> moveMonth(-1));
        b.btnNextMonth.setOnClickListener(v -> moveMonth(1));
        b.fabAddTrip.setOnClickListener(v -> startActivity(new Intent(this, TripEditActivity.class)
                .putExtra(TripEditActivity.EXTRA_DAY, TripRules.at(year, month0, selectedDay, 12, 0, tz))));
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh(); // back from adding or editing a trip
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void buildWeekdays() {
        int first = Calendar.getInstance().getFirstDayOfWeek();
        String[] names = new DateFormatSymbols(Locale.getDefault()).getShortWeekdays();
        b.llWeekdays.removeAllViews();
        for (int i = 0; i < 7; i++) {
            int dow = (first - 1 + i) % 7 + 1;
            TextView tv = new TextView(this);
            tv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setText(names[dow]);
            tv.setTextColor(ContextCompat.getColor(this, R.color.locus_text_secondary));
            tv.setTextSize(12);
            b.llWeekdays.addView(tv);
        }
    }

    private void moveMonth(int delta) {
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(year, month0, 1);
        c.add(Calendar.MONTH, delta);
        year = c.get(Calendar.YEAR);
        month0 = c.get(Calendar.MONTH);
        int max = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        selectedDay = Math.min(selectedDay, max);
        refresh();
    }

    /** Re-reads the month's trips (marks) and the selected day's trips. */
    private void refresh() {
        final int token = ++loadToken;
        cells = TripRules.monthGrid(year, month0, Calendar.getInstance().getFirstDayOfWeek(), tz);
        b.tvMonth.setText(new SimpleDateFormat("LLLL yyyy", Locale.getDefault()).format(
                new Date(TripRules.dayStart(year, month0, 15, tz))));
        final long from = TripRules.dayStart(year, month0, 1, tz);
        final long to = TripRules.dayStart(year, month0 + 1, 1, tz);
        final long dayFrom = TripRules.dayStart(year, month0, selectedDay, tz);
        final long dayTo = TripRules.nextDayStart(year, month0, selectedDay, tz);
        AppExecutors.io().execute(() -> {
            List<Trip> month = LocusDatabase.get(this).tripDao().between(from, to);
            final Map<Integer, Integer> counts = new HashMap<>();
            final List<Trip> day = new ArrayList<>();
            for (Trip t : month) {
                int d = TripRules.dayOf(t.startAt, tz);
                counts.put(d, counts.containsKey(d) ? counts.get(d) + 1 : 1);
                if (t.startAt >= dayFrom && t.startAt < dayTo) day.add(t);
            }
            AppExecutors.main().post(() -> {
                if (isDestroyed() || token != loadToken) return;
                tripsPerDay.clear();
                tripsPerDay.putAll(counts);
                dayTrips.clear();
                dayTrips.addAll(day);
                daysAdapter.notifyDataSetChanged();
                tripsAdapter.notifyDataSetChanged();
                b.tvDayTitle.setText(getString(R.string.trip_day_title,
                        DateFormat.getMediumDateFormat(this).format(new Date(dayFrom))));
                b.tvNoTrips.setVisibility(dayTrips.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private boolean isToday(int day) {
        Calendar c = Calendar.getInstance(tz);
        return c.get(Calendar.YEAR) == year && c.get(Calendar.MONTH) == month0 && c.get(Calendar.DAY_OF_MONTH) == day;
    }

    private void navigate(Trip t) {
        // the start place of the calendar trip becomes the start of Smart Navigation (or the live GPS position)
        if (t.srcCurrent) AppPrefs.useCurrentOrigin(this);
        else AppPrefs.setOrigin(this, t.srcName, t.srcLat, t.srcLon);
        startActivity(NavigationActivity.intentTo(this, t.destLat, t.destLon, t.destName));
    }

    private void confirmDelete(final Trip t) {
        new AlertDialog.Builder(this)
                .setMessage(R.string.trip_delete_confirm)
                .setPositiveButton(R.string.trip_delete, (d, w) -> AppExecutors.io().execute(() -> {
                    TripReminders.cancel(this, t.id);
                    LocusDatabase.get(this).tripDao().delete(t.id);
                    AppExecutors.main().post(() -> {
                        if (isDestroyed()) return;
                        Toast.makeText(this, R.string.trip_deleted, Toast.LENGTH_SHORT).show();
                        refresh();
                    });
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private final class DaysAdapter extends RecyclerView.Adapter<DaysAdapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemCalDayBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            final int day = cells[position];
            ItemCalDayBinding v = h.v;
            if (day == 0) {
                v.tvDay.setText("");
                v.tvDay.setBackground(null);
                v.dotTrip.setVisibility(View.INVISIBLE);
                v.getRoot().setOnClickListener(null);
                v.getRoot().setClickable(false);
                return;
            }
            v.getRoot().setClickable(true);
            v.tvDay.setText(String.valueOf(day));
            boolean selected = day == selectedDay;
            v.tvDay.setBackgroundResource(selected ? R.drawable.bg_cal_selected : isToday(day) ? R.drawable.bg_cal_today : 0);
            if (!selected && !isToday(day)) v.tvDay.setBackground(null);
            v.tvDay.setTextColor(ContextCompat.getColor(TripCalendarActivity.this,
                    selected ? R.color.white : R.color.locus_text));
            v.tvDay.setTypeface(null, tripsPerDay.containsKey(day) ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            v.dotTrip.setVisibility(tripsPerDay.containsKey(day) ? View.VISIBLE : View.INVISIBLE);
            v.getRoot().setOnClickListener(x -> {
                selectedDay = day;
                refresh();
            });
        }

        @Override
        public int getItemCount() {
            return 42;
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ItemCalDayBinding v;

            Holder(ItemCalDayBinding v) {
                super(v.getRoot());
                this.v = v;
            }
        }
    }

    private final class TripsAdapter extends RecyclerView.Adapter<TripsAdapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemTripBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            final Trip t = dayTrips.get(position);
            ItemTripBinding v = h.v;
            v.tvTripTime.setText(DateFormat.getTimeFormat(TripCalendarActivity.this).format(new Date(t.startAt)));
            v.tvTripTitle.setText(t.title.isEmpty() ? getString(R.string.trip_default_title, t.destName) : t.title);
            String from = t.srcCurrent || t.srcName.isEmpty() ? getString(R.string.trip_current_location) : t.srcName;
            v.tvTripRoute.setText(getString(R.string.trip_route, from, t.destName));
            long remindAt = TripRules.reminderAt(t.startAt);
            v.tvTripRemind.setText(t.reminded ? getString(R.string.trip_reminded)
                    : t.startAt < System.currentTimeMillis() ? getString(R.string.trip_past)
                    : getString(R.string.trip_reminder_line, DateFormat.getMediumDateFormat(TripCalendarActivity.this)
                    .format(new Date(remindAt)) + " " + DateFormat.getTimeFormat(TripCalendarActivity.this).format(new Date(remindAt))));
            v.btnTripNavigate.setOnClickListener(x -> navigate(t));
            v.btnTripDelete.setOnClickListener(x -> confirmDelete(t));
            v.getRoot().setOnClickListener(x -> startActivity(new Intent(TripCalendarActivity.this, TripEditActivity.class)
                    .putExtra(TripEditActivity.EXTRA_TRIP_ID, t.id)));
        }

        @Override
        public int getItemCount() {
            return dayTrips.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ItemTripBinding v;

            Holder(ItemTripBinding v) {
                super(v.getRoot());
                this.v = v;
            }
        }
    }
}
