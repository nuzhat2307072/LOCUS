package com.locus.app.work;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.FirebaseDatabase;
import com.locus.app.data.firebase.HazardRepository;
import com.locus.app.data.firebase.Session;
import com.locus.app.model.Hazard;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Hazard alerts while the app is closed: every 15 minutes (Android's minimum) the phone reads the shared hazard
 * list, alerts for new hazards near it and for hazards that were cleared.
 */
public class HazardCheckWorker extends Worker {
    private static final String TAG = "HazardCheck";
    private static final String NAME = "locus_hazard_check";

    public HazardCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void schedule(Context c) {
        Constraints online = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(HazardCheckWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(online).build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    @SuppressWarnings("unchecked")
    @NonNull
    @Override
    public Result doWork() {
        Context app = getApplicationContext();
        if (!Session.canSyncOnline(app)) return Result.success();
        try {
            DataSnapshot all = Tasks.await(FirebaseDatabase.getInstance().getReference(HazardRepository.NODE).get(),
                    40, TimeUnit.SECONDS);
            Map<String, Hazard> found = new HashMap<>();
            for (DataSnapshot child : all.getChildren()) {
                Object raw = child.getValue();
                if (child.getKey() == null || !(raw instanceof Map)) continue;
                Hazard h = Hazard.fromMap(child.getKey(), (Map<String, Object>) raw);
                if (h != null) found.put(h.id, h);
            }
            HazardRepository.syncAll(app, found);
            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "Check failed, will retry", e);
            return Result.retry();
        }
    }
}
