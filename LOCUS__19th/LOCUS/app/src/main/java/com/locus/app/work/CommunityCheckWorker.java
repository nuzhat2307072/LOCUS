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
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.Source;
import com.locus.app.data.db.LocusDatabase;
import com.locus.app.data.firebase.CommunityRepository;
import com.locus.app.data.firebase.CommunitySync;
import com.locus.app.data.firebase.Session;
import com.locus.app.model.CommunityRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Delivery while the app is closed: every 15 minutes (Android's minimum) the phone asks the shared board for
 * new posts, stores them and shows a notification. It works without any server of your own; the optional push
 * (see README) makes the alert instant instead of "within 15 minutes".
 */
public class CommunityCheckWorker extends Worker {
    private static final String TAG = "CommunityCheck";
    private static final String NAME = "locus_community_check";

    public CommunityCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void schedule(Context c) {
        Constraints online = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(CommunityCheckWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(online).build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context app = getApplicationContext();
        if (!Session.canSyncOnline(app)) return Result.success(); // nobody logged in / no cloud
        try {
            QuerySnapshot snap = Tasks.await(FirebaseFirestore.getInstance()
                    .collection(CommunityRepository.COLLECTION)
                    .orderBy("createdAt", Query.Direction.DESCENDING).limit(300)
                    .get(Source.SERVER), 40, TimeUnit.SECONDS);
            List<CommunityRequest> list = new ArrayList<>();
            for (DocumentSnapshot d : snap.getDocuments()) {
                CommunityRequest r = CommunityRequest.fromMap(d.getId(),
                        d.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE));
                if (r != null) list.add(r);
            }
            CommunitySync.announce(app, list, Session.uid(app));
            LocusDatabase.get(app).requestDao().replaceSynced(list);
            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "Check failed, will retry", e);
            return Result.retry();
        }
    }
}
