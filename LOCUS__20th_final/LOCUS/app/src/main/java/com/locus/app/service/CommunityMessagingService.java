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
        if ("chat".equals(d.get("kind"))) { // a private message (functions/index.js)
            if (!Session.canSyncOnline(this) || !Session.uid(this).equals(d.get("toUid"))) return;
            com.locus.app.model.ChatSummary c = new com.locus.app.model.ChatSummary();
            c.chatId = String.valueOf(d.get("chatId"));
            c.otherUid = d.get("fromUid") == null ? "" : d.get("fromUid");
            c.otherName = d.get("fromName") == null ? "" : d.get("fromName");
            c.title = d.get("title") == null ? "" : d.get("title");
            c.last = d.get("text") == null ? "" : d.get("text");
            com.locus.app.data.firebase.ChatRepository.announce(this, c);
            return;
        }
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
