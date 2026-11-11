package com.locus.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.locus.app.R;
import com.locus.app.data.db.LocusDatabase;
import com.locus.app.data.firebase.HazardRepository;
import com.locus.app.data.firebase.Session;
import com.locus.app.model.Hazard;
import com.locus.app.service.AlertsService;
import com.locus.app.databinding.ActivityMainBinding;
import com.locus.app.receiver.SystemEventsReceiver;
import com.locus.app.service.LocationTrackingService;
import com.locus.app.util.ConnectivityMonitor;

/**
 * Dashboard. Also the place where the Activity lifecycle callbacks are overridden and logged
 * (see the "Lifecycle log" screen for the app-wide view).
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private ActivityMainBinding b;
    private SystemEventsReceiver systemReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        b = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);

        b.tvGreeting.setText(getString(R.string.greeting, Session.displayName(this)));
        String email = Session.email(this);
        if (email != null) b.tvAccount.setText(getString(R.string.signed_in_as, email));

        // Observe connectivity (LiveData) and show the state on the dashboard
        new ConnectivityMonitor(this).observe(this, online -> {
            b.tvConnectivity.setText(online ? R.string.online : R.string.offline);
            b.tvConnectivity.setTextColor(ContextCompat.getColor(this,
                    online ? R.color.locus_ok : R.color.locus_danger));
        });

        // Explicit intents to the feature screens
        b.cardExplore.setOnClickListener(v -> startActivity(new Intent(this, ExploreActivity.class)));
        b.tvExploreSearch.setOnClickListener(v -> startActivity(new Intent(this, ExploreActivity.class)
                .putExtra(ExploreActivity.EXTRA_FOCUS, true)));
        b.cardTrips.setOnClickListener(v -> startActivity(new Intent(this, TripCalendarActivity.class)));
        b.cardGroups.setOnClickListener(v -> startActivity(new Intent(this, GroupsActivity.class)));
        quickExplore(b.chipQHospital, "hospital");
        quickExplore(b.chipQPharmacy, "pharmacy");
        quickExplore(b.chipQFood, "restaurant");
        quickExplore(b.chipQAtm, "atm");
        quickExplore(b.chipQFuel, "fuel");
        quickExplore(b.chipQMosque, "mosque");
        b.cardNavigate.setOnClickListener(v -> startActivity(new Intent(this, NavigationActivity.class)));
        b.cardCommunity.setOnClickListener(v -> startActivity(new Intent(this, CommunityActivity.class)));
        b.cardSaved.setOnClickListener(v -> startActivity(new Intent(this, SavedPlacesActivity.class)));
        b.btnSos.setOnClickListener(v -> startActivity(new Intent(this, SosActivity.class)));
        b.cardShare.setOnClickListener(v -> startActivity(new Intent(this, ShareHubActivity.class)));
        b.cardEmergency.setOnClickListener(v -> startActivity(new Intent(this, EmergencyActivity.class)));
        b.cardMessages.setOnClickListener(v -> startActivity(new Intent(this, ChatsActivity.class)));
        b.cardHazards.setOnClickListener(v -> startActivity(new Intent(this, HazardsActivity.class)));
        b.cardOffline.setOnClickListener(v -> startActivity(new Intent(this, OfflineMapsActivity.class)));

        // Live count of hazards around you on the dashboard card (Room LiveData: updates by itself)
        LocusDatabase.get(this).hazardDao().observeAll().observe(this, list -> showHazardCount(list));

        // No permission pop-ups here: each feature asks for what it needs at the moment it needs it.

        systemReceiver = new SystemEventsReceiver(msg -> Snackbar.make(b.getRoot(), msg, Snackbar.LENGTH_LONG).show());
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart");
        ContextCompat.registerReceiver(this, systemReceiver, SystemEventsReceiver.filter(),
                ContextCompat.RECEIVER_EXPORTED); // system broadcasts come from outside the app
        getSharedPreferences(com.locus.app.data.firebase.ChatRepository.NOTIFIED_PREFS, MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(unreadListener);
        getSharedPreferences(com.locus.app.data.firebase.ContactRepository.PREFS_NAME, MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(unreadListener);
        getSharedPreferences(com.locus.app.data.firebase.GroupRepository.PREFS_NAME, MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(unreadListener);
        showUnread();
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause");
        super.onPause();
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "onStop");
        unregisterReceiver(systemReceiver);
        getSharedPreferences(com.locus.app.data.firebase.ChatRepository.NOTIFIED_PREFS, MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(unreadListener);
        getSharedPreferences(com.locus.app.data.firebase.ContactRepository.PREFS_NAME, MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(unreadListener);
        getSharedPreferences(com.locus.app.data.firebase.GroupRepository.PREFS_NAME, MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(unreadListener);
        super.onStop();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        Log.d(TAG, "onRestart");
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (id == R.id.action_logout) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.logout_title)
                    .setMessage(R.string.logout_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.menu_logout, (d, w) -> {
                        stopService(new Intent(this, LocationTrackingService.class));
                        stopService(new Intent(this, AlertsService.class));
                        com.locus.app.data.firebase.ChatRepository.forgetUnread(this);
                        Session.signOut(this); // forgets the account on this phone and locks the app
                        goToLogin();
                    })
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** The number of unread private messages on the Messages card; updates the moment a chat list change is read. */
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener unreadListener =
            (prefs, key) -> runOnUiThread(this::showUnread);

    private void showUnread() {
        int gp = com.locus.app.data.firebase.GroupRepository.pendingCount(this);
        b.tvGroupsLive.setText(gp == 0 ? getString(R.string.card_groups_sub) : getString(R.string.card_groups_requests, gp));
        b.tvGroupsLive.setTextColor(ContextCompat.getColor(this, gp == 0 ? R.color.locus_text_secondary : R.color.locus_danger));
        int n = com.locus.app.data.firebase.ChatRepository.unreadTotal(this);
        int req = com.locus.app.data.firebase.ContactRepository.pendingCount(this);
        String text = n == 0 && req == 0 ? getString(R.string.card_messages_none)
                : n == 0 ? getString(R.string.card_messages_requests, req)
                : req == 0 ? getString(R.string.card_messages_unread, n)
                : getString(R.string.card_messages_both, n, req);
        b.tvMessagesLive.setText(text);
        boolean quiet = n == 0 && req == 0;
        b.tvMessagesLive.setTextColor(ContextCompat.getColor(this,
                quiet ? R.color.locus_text_secondary : R.color.locus_danger));
        b.tvMessagesLive.setTypeface(null, quiet ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
    }

    private void showHazardCount(java.util.List<Hazard> list) {
        long now = System.currentTimeMillis();
        int near = 0;
        int verified = 0;
        if (list != null) {
            for (Hazard h : list) {
                if (h.isExpired(now)) continue;
                double d = HazardRepository.distanceToMe(this, h);
                if (d >= 0 && d > 10_000) continue; // unknown position: show everything
                near++;
                if (h.isVerified()) verified++;
            }
        }
        b.tvHazardsLive.setText(near == 0 ? getString(R.string.card_hazards_none)
                : getString(R.string.card_hazards_live, near, verified));
        b.tvHazardsLive.setTextColor(ContextCompat.getColor(this,
                near == 0 ? R.color.locus_text_secondary : R.color.locus_danger));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        showNextTrip();
        com.locus.app.util.TripReminders.rescheduleAll(this); // trip alarms are always in place
        com.locus.app.net.ExplorePrefetch.warm(this); // hospital / pharmacy / food around me, ready before Explore opens
        AlertsService.startIfWanted(this); // keeps chat / hazard / community alerts coming with the app closed
    }

    /** The Trip calendar card shows the next planned trip. */
    private void showNextTrip() {
        com.locus.app.util.AppExecutors.io().execute(() -> {
            final com.locus.app.model.Trip t = com.locus.app.data.db.LocusDatabase.get(this).tripDao().next(System.currentTimeMillis());
            com.locus.app.util.AppExecutors.main().post(() -> {
                if (isDestroyed()) return;
                if (t == null) {
                    b.tvTripsLive.setText(R.string.card_trips_none);
                } else {
                    java.util.Date d = new java.util.Date(t.startAt);
                    b.tvTripsLive.setText(getString(R.string.card_trips_next, t.destName,
                            android.text.format.DateFormat.getMediumDateFormat(this).format(d) + " "
                                    + android.text.format.DateFormat.getTimeFormat(this).format(d)));
                }
            });
        });
    }

    /** One tap on a category: Explore opens and searches it at once (the answer is often already prefetched). */
    private void quickExplore(android.view.View chip, final String query) {
        chip.setOnClickListener(v -> startActivity(new Intent(this, ExploreActivity.class)
                .putExtra(ExploreActivity.EXTRA_QUERY, query)));
    }

    private void goToLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}
