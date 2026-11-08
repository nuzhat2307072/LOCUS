package com.locus.app.service;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.locus.app.data.firebase.CommunitySync;
import com.locus.app.data.firebase.Session;
import com.locus.app.model.CommunityRequest;

import java.util.Collections;
import java.util.Map;

/**
 * Instant alert for a new Community post, sent by the optional Cloud Function (functions/index.js) to the
 * "community" topic. It only announces the post; the post itself arrives through the shared board.
 */
public class CommunityMessagingService extends FirebaseMessagingService {
    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        Map<String, String> d = message.getData();
        String id = d.get("id");
        if (id == null || !Session.canSyncOnline(this)) return;
        CommunityRequest r = new CommunityRequest();
        r.id = id;
        r.type = d.get("type");
        r.title = d.get("title") == null ? "" : d.get("title");
        r.ownerUid = d.get("ownerUid") == null ? "" : d.get("ownerUid");
        r.status = CommunityRequest.OPEN;
        try {
            r.createdAt = Long.parseLong(String.valueOf(d.get("createdAt")));
        } catch (NumberFormatException e) {
            r.createdAt = System.currentTimeMillis();
        }
        CommunitySync.announce(this, Collections.singletonList(r), Session.uid(this));
    }
}
