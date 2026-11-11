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

import com.locus.app.data.firebase.ChatRepository;
import com.locus.app.data.firebase.Session;

import java.util.concurrent.TimeUnit;

/**
 * Safety net for private-message alerts: every 15 minutes (Android's minimum) the phone reads its chats list and
 * announces messages it has not announced yet. It covers the time when the live-alerts service is switched off or
 * was stopped by the phone maker's battery manager.
 */
public class ChatCheckWorker extends Worker {
    private static final String TAG = "ChatCheck";
    private static final String NAME = "locus_chat_check";

    public ChatCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void schedule(Context c) {
        Constraints online = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(ChatCheckWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(online).build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context app = getApplicationContext();
        try {
            com.locus.app.util.TripReminders.checkDue(app); // trip reminders need no account or internet
        } catch (RuntimeException e) {
            Log.w(TAG, "Trip reminder check failed", e);
        }
        if (!Session.canSyncOnline(app)) return Result.success();
        try {
            ChatRepository.checkBlocking(app);
            com.locus.app.data.firebase.ContactRepository.checkBlocking(app);
            com.locus.app.data.firebase.GroupRepository.checkBlocking(app);
            com.locus.app.data.firebase.GroupFeedRepository.checkBlocking(app);
            ChatRepository.remindIfUnread(app);
            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "Check failed, will retry", e);
            return Result.retry();
        }
    }
}
