package com.locus.app;

import android.app.Application;

import com.locus.app.data.firebase.CommunitySync;
import com.locus.app.security.AuthGate;
import com.locus.app.util.LifecycleLogger;
import com.locus.app.util.NotificationHelper;
import com.locus.app.work.CommunityCheckWorker;
import com.locus.app.work.SyncWorker;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;

import java.io.File;

public class LocusApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        com.locus.app.data.firebase.FirebaseConnector.init(this); // a google-services.json chosen on the phone

        // osmdroid: load config, identify the app to the tile server (required by OSM policy)
        // and keep tiles in app-private storage (no storage permission needed).
        IConfigurationProvider cfg = Configuration.getInstance();
        cfg.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE));
        cfg.setUserAgentValue(getPackageName());
        File base = new File(getFilesDir(), "osmdroid");
        cfg.setOsmdroidBasePath(base);
        cfg.setOsmdroidTileCache(new File(base, "tiles"));

        com.locus.app.util.ThemeHelper.apply(this); // light / dark / follow system, as saved
        NotificationHelper.createChannels(this);
        registerActivityLifecycleCallbacks(new LifecycleLogger());
        registerActivityLifecycleCallbacks(new AuthGate()); // locks the app whenever it is left
        registerActivityLifecycleCallbacks(new CommunitySync(this)); // shared Community board, live
        registerActivityLifecycleCallbacks(new com.locus.app.util.ShakeSos()); // shake x3 = SOS (if enabled)
        SyncWorker.schedulePeriodic(this);
        CommunityCheckWorker.schedule(this); // new posts reach this phone even when the app is closed
        com.locus.app.work.HazardCheckWorker.schedule(this); // so do hazard alerts
        com.locus.app.work.ChatCheckWorker.schedule(this); // and private messages
    }
}
