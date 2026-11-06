package com.locus.app;

import android.app.Application;

import com.locus.app.data.firebase.CommunitySync;
import com.locus.app.security.AuthGate;
import com.locus.app.util.LifecycleLogger;
import com.locus.app.util.NotificationHelper;
import com.locus.app.work.CommunityCheckWorker;
import com.locus.app.work.SyncWorker;

import org.osmdroid.config.Configuration;

import java.io.File;

public class LocusApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // osmdroid: load config, identify the app to the tile server (required by OSM policy)
        // and keep tiles in app-private storage (no storage permission needed).
        Configuration cfg = Configuration.getInstance();
        cfg.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE));
        cfg.setUserAgentValue(getPackageName());
        File base = new File(getFilesDir(), "osmdroid");
        cfg.setOsmdroidBasePath(base);
        cfg.setOsmdroidTileCache(new File(base, "tiles"));

        NotificationHelper.createChannels(this);
        registerActivityLifecycleCallbacks(new LifecycleLogger());
        registerActivityLifecycleCallbacks(new AuthGate()); // locks the app whenever it is left
        registerActivityLifecycleCallbacks(new CommunitySync(this)); // shared Community board, live
        SyncWorker.schedulePeriodic(this);
        CommunityCheckWorker.schedule(this); // new posts reach this phone even when the app is closed
    }
}
