package com.locus.app.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.util.HashMap;
import java.util.Map;

/** A comment under a Community Assistance post. Room entity + Cloud Firestore document (collection "comments"). */
@Entity(tableName = "comments", indices = {@Index("requestId")})
public class Comment {
    public static final int MAX_LENGTH = 500;

    @PrimaryKey
    @NonNull
    public String id = "";
    public String requestId;     // the post it belongs to
    public String ownerUid;
    public String ownerName;
    public String text;
    /** Id of the comment this one answers; empty for a comment straight under the post. */
    public String parentId = "";
    public String replyToName = "";
    public long createdAt;
    public boolean synced;       // true once the Firestore write succeeded

    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("requestId", requestId);
        m.put("ownerUid", ownerUid);
        m.put("ownerName", ownerName);
        m.put("text", text);
        m.put("parentId", parentId == null ? "" : parentId);
        m.put("replyToName", replyToName == null ? "" : replyToName);
        // the SERVER stamps the time, so the order is the same on every phone
        m.put("createdAt", com.google.firebase.firestore.FieldValue.serverTimestamp());
        return m;
    }

    @Nullable
    public static Comment fromMap(String id, @Nullable Map<String, Object> m) {
        if (m == null || m.get("requestId") == null) return null;
        Comment c = new Comment();
        c.id = id;
        c.requestId = String.valueOf(m.get("requestId"));
        c.ownerUid = m.get("ownerUid") == null ? "" : String.valueOf(m.get("ownerUid"));
        c.ownerName = m.get("ownerName") == null ? "" : String.valueOf(m.get("ownerName"));
        c.text = m.get("text") == null ? "" : String.valueOf(m.get("text"));
        c.parentId = m.get("parentId") == null ? "" : String.valueOf(m.get("parentId"));
        c.replyToName = m.get("replyToName") == null ? "" : String.valueOf(m.get("replyToName"));
        Object t = m.get("createdAt");
        if (t instanceof com.google.firebase.Timestamp) c.createdAt = ((com.google.firebase.Timestamp) t).toDate().getTime();
        else if (t instanceof java.util.Date) c.createdAt = ((java.util.Date) t).getTime();
        else if (t instanceof Number) c.createdAt = ((Number) t).longValue();
        c.synced = true;
        return c;
    }
}
