package com.locus.app.ui;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.locus.app.R;
import com.locus.app.data.db.LocusDatabase;
import com.locus.app.data.sqlite.LocusSqlHelper;
import com.locus.app.databinding.ActivityTripEditBinding;
import com.locus.app.model.Trip;
import com.locus.app.model.TripRules;
import com.locus.app.util.AppExecutors;
import com.locus.app.util.AppPrefs;
import com.locus.app.util.TripReminders;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Add or change a planned trip: where from (the current location or a place I set), where to, and when. The
 * calendar marks the day, and a reminder comes 6 hours before the start.
 */
public class TripEditActivity extends AppCompatActivity {
    public static final String EXTRA_TRIP_ID = "tripId";
    /** Pre-selected day (UTC milliseconds) when adding. */
    public static final String EXTRA_DAY = "day";
    /** Optional destination handed over from another screen. */
    public static final String EXTRA_DEST_NAME = "destName";
    public static final String EXTRA_DEST_LAT = "destLat";
    public static final String EXTRA_DEST_LON = "destLon";

    private ActivityTripEditBinding b;
    private LocusSqlHelper sql;
    private final TimeZone tz = TimeZone.getDefault();

    private Trip editing; // null when adding
    private boolean srcCurrent = true;
    private String srcName = "";
    private double srcLat;
    private double srcLon;
    private String destName = "";
    private double destLat;
    private double destLon;
    private int year;
    private int month0;
    private int day;
    private int hour;
    private int minute;
    private long originalStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityTripEditBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        sql = new LocusSqlHelper(this);

        // a sensible start: the chosen day at 09:00, or the next full hour when that is today
        Calendar c = Calendar.getInstance(tz);
        long dayMs = getIntent().getLongExtra(EXTRA_DAY, 0);
        Calendar pick = Calendar.getInstance(tz);
        if (dayMs > 0) pick.setTimeInMillis(dayMs);
        year = pick.get(Calendar.YEAR);
        month0 = pick.get(Calendar.MONTH);
        day = pick.get(Calendar.DAY_OF_MONTH);
        hour = 9;
        minute = 0;
        if (year == c.get(Calendar.YEAR) && month0 == c.get(Calendar.MONTH) && day == c.get(Calendar.DAY_OF_MONTH)
                && c.get(Calendar.HOUR_OF_DAY) >= 8) {
            hour = Math.min(23, c.get(Calendar.HOUR_OF_DAY) + 1);
        }
        double[] last = AppPrefs.lastLocation(this);
        srcLat = last[0];
        srcLon = last[1];

        String dn = getIntent().getStringExtra(EXTRA_DEST_NAME);
        if (dn != null) {
            destName = dn;
            destLat = getIntent().getDoubleExtra(EXTRA_DEST_LAT, 0);
            destLon = getIntent().getDoubleExtra(EXTRA_DEST_LON, 0);
        }

        String id = getIntent().getStringExtra(EXTRA_TRIP_ID);
        if (id != null) {
            b.toolbar.setTitle(R.string.trip_edit_title);
            AppExecutors.io().execute(() -> {
                final Trip t = LocusDatabase.get(this).tripDao().get(id);
                AppExecutors.main().post(() -> {
                    if (isDestroyed() || t == null) return;
                    load(t);
                });
            });
        }

        b.btnTripFrom.setOnClickListener(v -> StartPicker.show(this, sql, new StartPicker.Host() {
            @Override
            public void onUseCurrent() {
                srcCurrent = true;
                srcName = "";
                show();
            }

            @Override
            public void onUseSet(String name, double lat, double lon) {
                srcCurrent = false;
                srcName = name;
                srcLat = lat;
                srcLon = lon;
                show();
            }

            @Override
            public void onPickOnMap() {
                // not offered in the calendar
            }

            @Override
            public double[] biasPoint() {
                return AppPrefs.lastLocation(TripEditActivity.this);
            }
        }, StartPicker.MODE_TRIP_SOURCE));
        b.btnTripTo.setOnClickListener(v -> StartPicker.show(this, sql, new StartPicker.Host() {
            @Override
            public void onUseCurrent() {
                // not offered for the destination
            }

            @Override
            public void onUseSet(String name, double lat, double lon) {
                destName = name;
                destLat = lat;
                destLon = lon;
                show();
            }

            @Override
            public void onPickOnMap() {
                // not offered in the calendar
            }

            @Override
            public double[] biasPoint() {
                return srcCurrent ? AppPrefs.lastLocation(TripEditActivity.this) : new double[]{srcLat, srcLon};
            }
        }, StartPicker.MODE_TRIP_DESTINATION));
        b.btnTripDate.setOnClickListener(v -> {
            DatePickerDialog d = new DatePickerDialog(this, (view, y, m, dd) -> {
                year = y;
                month0 = m;
                day = dd;
                show();
            }, year, month0, day);
            d.getDatePicker().setMinDate(System.currentTimeMillis() - 1000);
            d.show();
        });
        b.btnTripTime.setOnClickListener(v -> new TimePickerDialog(this, (view, h, m) -> {
            hour = h;
            minute = m;
            show();
        }, hour, minute, DateFormat.is24HourFormat(this)).show());
        b.btnTripSave.setOnClickListener(v -> save());
        show();
    }

    private void load(Trip t) {
        editing = t;
        b.etTripTitle.setText(t.title);
        b.etTripNote.setText(t.note);
        srcCurrent = t.srcCurrent;
        srcName = t.srcName;
        srcLat = t.srcLat;
        srcLon = t.srcLon;
        destName = t.destName;
        destLat = t.destLat;
        destLon = t.destLon;
        originalStart = t.startAt;
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(t.startAt);
        year = c.get(Calendar.YEAR);
        month0 = c.get(Calendar.MONTH);
        day = c.get(Calendar.DAY_OF_MONTH);
        hour = c.get(Calendar.HOUR_OF_DAY);
        minute = c.get(Calendar.MINUTE);
        show();
    }

    /** Shows the current choices on the buttons and what the reminder will be. */
    private void show() {
        b.btnTripFrom.setText(srcCurrent || srcName.isEmpty() ? getString(R.string.trip_current_location) : srcName);
        b.btnTripTo.setText(destName.isEmpty() ? getString(R.string.trip_pick_dest_prompt) : destName);
        long start = startMillis();
        b.btnTripDate.setText(DateFormat.getMediumDateFormat(this).format(new Date(start)));
        b.btnTripTime.setText(DateFormat.getTimeFormat(this).format(new Date(start)));
        long now = System.currentTimeMillis();
        if (start <= now) {
            b.tvTripReminderInfo.setText(R.string.trip_in_past);
        } else if (TripRules.reminderIsImmediate(start, now)) {
            b.tvTripReminderInfo.setText(R.string.trip_reminder_now);
        } else {
            long r = TripRules.reminderAt(start);
            b.tvTripReminderInfo.setText(getString(R.string.trip_reminder_line,
                    DateFormat.getMediumDateFormat(this).format(new Date(r)) + " " + DateFormat.getTimeFormat(this).format(new Date(r))));
        }
    }

    private long startMillis() {
        return TripRules.at(year, month0, day, hour, minute, tz);
    }

    private void save() {
        final long start = startMillis();
        final long now = System.currentTimeMillis();
        if (!TripRules.isValid(destName, destLat, destLon, start, now)) {
            Toast.makeText(this, R.string.trip_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        final Trip t = editing != null ? editing : new Trip();
        if (editing == null) {
            t.id = UUID.randomUUID().toString();
            t.createdAt = now;
        }
        t.title = b.etTripTitle.getText().toString().trim();
        t.note = b.etTripNote.getText().toString().trim();
        t.srcCurrent = srcCurrent;
        t.srcName = srcCurrent ? "" : srcName;
        t.srcLat = srcLat;
        t.srcLon = srcLon;
        t.destName = destName;
        t.destLat = destLat;
        t.destLon = destLon;
        if (editing == null || start != originalStart) t.reminded = false; // a new time: remind again
        t.startAt = start;
        final boolean immediate = TripRules.reminderIsImmediate(start, now);
        AppExecutors.io().execute(() -> {
            LocusDatabase.get(this).tripDao().insert(t);
            TripReminders.schedule(this, t);
            AppExecutors.main().post(() -> {
                Toast.makeText(this, immediate ? R.string.trip_saved_now : R.string.trip_saved, Toast.LENGTH_LONG).show();
                finish();
            });
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        sql.close();
        super.onDestroy();
    }
}
