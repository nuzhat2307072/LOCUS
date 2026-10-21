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
import com.locus.app.data.firebase.Session;
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
        b.cardNavigate.setOnClickListener(v -> startActivity(new Intent(this, NavigationActivity.class)));
        b.cardCommunity.setOnClickListener(v -> startActivity(new Intent(this, CommunityActivity.class)));
        b.cardSaved.setOnClickListener(v -> startActivity(new Intent(this, SavedPlacesActivity.class)));

        // No permission pop-ups here: each feature asks for what it needs at the moment it needs it.

        systemReceiver = new SystemEventsReceiver(msg -> Snackbar.make(b.getRoot(), msg, Snackbar.LENGTH_LONG).show());
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart");
        ContextCompat.registerReceiver(this, systemReceiver, SystemEventsReceiver.filter(),
                ContextCompat.RECEIVER_EXPORTED); // system broadcasts come from outside the app
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
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
        if (id == R.id.action_lifecycle) {
            startActivity(new Intent(this, LifecycleLogActivity.class));
            return true;
        } else if (id == R.id.action_network_lab) {
            startActivity(new Intent(this, NetworkLabActivity.class));
            return true;
        } else if (id == R.id.action_lock) {
            Session.lock();
            goToLogin();
            return true;
        } else if (id == R.id.action_logout) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.logout_title)
                    .setMessage(R.string.logout_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.menu_logout, (d, w) -> {
                        stopService(new Intent(this, LocationTrackingService.class));
                        Session.signOut(this); // forgets the account on this phone and locks the app
                        goToLogin();
                    })
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void goToLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}
