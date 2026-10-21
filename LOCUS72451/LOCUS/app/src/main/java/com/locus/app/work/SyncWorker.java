package com.locus.app.work;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.firestore.FirebaseFirestore;
import com.locus.app.data.db.LocusDatabase;
import com.locus.app.data.firebase.CommunityRepository;
import com.locus.app.data.firebase.HazardRepository;
import com.locus.app.data.firebase.Session;
import com.locus.app.model.CommunityRequest;
import com.locus.app.model.Hazard;

import java.util.concurrent.TimeUnit;

/**
 * Background task (WorkManager): pushes data that was created offline (synced = false) to Firebase
 * as soon as the device is online again. Runs every 15 minutes and on demand after a failed push.
 */
public class SyncWorker extends Worker {
    private static final String TAG = "SyncWorker";
    private static final String PERIODIC = "locus_sync_periodic";
    private static final String NOW = "locus_sync_now";

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    private static Constraints online() {
        return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    }

    public static void schedulePeriodic(Context c) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(SyncWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(online())
                .build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    public static void enqueueNow(Context c) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(online())
                .build();
        WorkManager.getInstance(c).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context app = getApplicationContext();
        if (!Session.canSyncOnline(app)) return Result.success(); // guest / Firebase not configured
        LocusDatabase db = LocusDatabase.get(app);
        try {
            for (CommunityRequest r : db.requestDao().getUnsynced()) {
                Tasks.await(FirebaseFirestore.getInstance().collection(CommunityRepository.COLLECTION)
                        .document(r.id).set(r.toMap()), 20, TimeUnit.SECONDS);
                db.requestDao().markSynced(r.id);
            }
            for (Hazard h : db.hazardDao().getUnsynced()) {
                Tasks.await(FirebaseDatabase.getInstance().getReference(HazardRepository.NODE)
                        .child(h.id).setValue(h.toMap()), 20, TimeUnit.SECONDS);
                db.hazardDao().markSynced(h.id);
            }
            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "Sync failed, will retry", e);
            return Result.retry();
        }
    }
}
