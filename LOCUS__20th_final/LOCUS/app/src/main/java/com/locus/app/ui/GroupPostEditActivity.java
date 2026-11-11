package com.locus.app.ui;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.locus.app.R;
import com.locus.app.data.firebase.GroupFeedRepository;
import com.locus.app.data.sqlite.LocusSqlHelper;
import com.locus.app.databinding.ActivityGroupPostEditBinding;
import com.locus.app.model.GroupPost;
import com.locus.app.model.GroupPostRules;
import com.locus.app.model.TripRules;
import com.locus.app.util.AppPrefs;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/** Write a trip plan, a party plan or a help request (donation, volunteer, blood, other) for the group. */
public class GroupPostEditActivity extends AppCompatActivity {
    public static final String EXTRA_GROUP_ID = "groupId";
    public static final String EXTRA_KIND = "kind";

    private ActivityGroupPostEditBinding b;
    private LocusSqlHelper sql;
    private final TimeZone tz = TimeZone.getDefault();
    private String gid = "";
    private String kind = GroupPostRules.TRIP;
    private int year;
    private int month0;
    private int day;
    private int hour = 18;
    private int minute;
    private String place = "";
    private double lat;
    private double lon;
    private boolean hasLocation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityGroupPostEditBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        sql = new LocusSqlHelper(this);
        String g = getIntent().getStringExtra(EXTRA_GROUP_ID);
        if (g == null) {
            finish();
            return;
        }
        gid = g;
        String k = getIntent().getStringExtra(EXTRA_KIND);
        if (k != null) kind = k;

        boolean plan = GroupPostRules.isPlan(kind);
        boolean help = GroupPostRules.HELP.equals(kind);
        b.toolbar.setTitle(GroupPostRules.TRIP.equals(kind) ? R.string.group_new_trip
                : GroupPostRules.PARTY.equals(kind) ? R.string.group_new_party : R.string.group_new_help);
        b.llPostWhen.setVisibility(plan ? View.VISIBLE : View.GONE);
        b.btnPostPlace.setVisibility(plan || help ? View.VISIBLE : View.GONE);
        b.rgHelpKind.setVisibility(help ? View.VISIBLE : View.GONE);

        Calendar c = Calendar.getInstance(tz);
        c.add(Calendar.DAY_OF_MONTH, 1);
        year = c.get(Calendar.YEAR);
        month0 = c.get(Calendar.MONTH);
        day = c.get(Calendar.DAY_OF_MONTH);

        b.btnPostDate.setOnClickListener(v -> {
            DatePickerDialog d = new DatePickerDialog(this, (view, y, m, dd) -> {
                year = y;
                month0 = m;
                day = dd;
                show();
            }, year, month0, day);
            d.getDatePicker().setMinDate(System.currentTimeMillis() - 1000);
            d.show();
        });
        b.btnPostTime.setOnClickListener(v -> new TimePickerDialog(this, (view, h, m) -> {
            hour = h;
            minute = m;
            show();
        }, hour, minute, DateFormat.is24HourFormat(this)).show());
        b.btnPostPlace.setOnClickListener(v -> StartPicker.show(this, sql, new StartPicker.Host() {
            @Override
            public void onUseCurrent() {
                // not offered for a place
            }

            @Override
            public void onUseSet(String name, double la, double lo) {
                place = name;
                lat = la;
                lon = lo;
                hasLocation = true;
                show();
            }

            @Override
            public void onPickOnMap() {
                // not offered here
            }

            @Override
            public double[] biasPoint() {
                return AppPrefs.lastLocation(GroupPostEditActivity.this);
            }
        }, StartPicker.MODE_TRIP_DESTINATION));
        b.btnPostSend.setOnClickListener(v -> send());
        show();
    }

    private long whenMillis() {
        return TripRules.at(year, month0, day, hour, minute, tz);
    }

    private void show() {
        Date d = new Date(whenMillis());
        b.btnPostDate.setText(DateFormat.getMediumDateFormat(this).format(d));
        b.btnPostTime.setText(DateFormat.getTimeFormat(this).format(d));
        b.btnPostPlace.setText(place.isEmpty() ? getString(R.string.group_post_place) : place);
    }

    private String helpKind() {
        int id = b.rgHelpKind.getCheckedRadioButtonId();
        if (id == R.id.rbVolunteer) return GroupPostRules.HELP_VOLUNTEER;
        if (id == R.id.rbBlood) return GroupPostRules.HELP_BLOOD;
        if (id == R.id.rbOther) return GroupPostRules.HELP_OTHER;
        return GroupPostRules.HELP_DONATION;
    }

    private void send() {
        GroupPost p = new GroupPost();
        p.type = kind;
        p.title = b.etPostTitle.getText().toString();
        p.text = b.etPostDetails.getText().toString();
        p.place = place;
        p.hasLocation = hasLocation;
        p.lat = lat;
        p.lon = lon;
        if (GroupPostRules.isPlan(kind)) p.when = whenMillis();
        if (GroupPostRules.HELP.equals(kind)) p.kind = helpKind();
        if (!GroupPostRules.validNew(kind, p.title, p.text, false, p.when, System.currentTimeMillis())) {
            Toast.makeText(this, GroupPostRules.isPlan(kind) ? R.string.group_post_invalid_plan : R.string.group_post_invalid,
                    Toast.LENGTH_LONG).show();
            return;
        }
        b.btnPostSend.setEnabled(false);
        GroupFeedRepository.post(this, gid, p, null, error -> runOnUiThread(() -> {
            if (isDestroyed()) return;
            if (error != null) {
                b.btnPostSend.setEnabled(true);
                Toast.makeText(this, "rules".equals(error) ? R.string.chat_rules : R.string.group_failed, Toast.LENGTH_LONG).show();
                return;
            }
            finish();
        }));
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
