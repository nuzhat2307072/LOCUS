package com.locus.app;

import android.app.Application;

import com.locus.app.util.LifecycleLogger;
import com.locus.app.util.NotificationHelper;
import com.locus.app.work.SyncWorker;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;

import java.io.File;

public class LocusApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // osmdroid: load config, identify the app to the tile server (required by OSM policy)
        // and keep tiles in app-private storage (no storage permission needed).
        IConfigurationProvider cfg = Configuration.getInstance();
        cfg.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE));
        cfg.setUserAgentValue(getPackageName());
        File base = new File(getFilesDir(), "osmdroid");
        cfg.setOsmdroidBasePath(base);
        cfg.setOsmdroidTileCache(new File(base, "tiles"));

        NotificationHelper.createChannels(this);
        registerActivityLifecycleCallbacks(new LifecycleLogger());
        SyncWorker.schedulePeriodic(this);
    }
}
