package com.locus.app.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/** A Community Assistance request (blood, volunteer, disaster aid ...). Room entity + Firestore document. */
@Entity(tableName = "requests")
public class CommunityRequest implements Serializable {
    public static final String OPEN = "OPEN";
    public static final String DONE = "DONE";

    /** Every SOS post's title starts with this, so the board can show it first and in red. */
    public static final String SOS_MARK = "\uD83D\uDEA8 SOS";

    @PrimaryKey
    @NonNull
    public String id = "";
    public String type;          // RequestType name
    public String title;
    public String description;
    public String contact;
    public double lat;
    public double lon;
    public boolean hasLocation;
    public String ownerUid;
    public String ownerName;
    public String status = OPEN;
    public long createdAt;
    public boolean synced;       // true once the Firestore write succeeded

    public boolean isSos() {
        return title != null && title.startsWith(SOS_MARK);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        m.put("title", title);
        m.put("description", description);
        m.put("contact", contact);
        m.put("lat", lat);
        m.put("lon", lon);
        m.put("hasLocation", hasLocation);
        m.put("ownerUid", ownerUid);
        m.put("ownerName", ownerName);
        m.put("status", status);
        // the SERVER stamps the time: a phone with a wrong clock can neither hide nor jump the queue
        m.put("createdAt", com.google.firebase.firestore.FieldValue.serverTimestamp());
        return m;
    }

    @Nullable
    public static CommunityRequest fromMap(String id, @Nullable Map<String, Object> m) {
        if (m == null) return null;
        CommunityRequest r = new CommunityRequest();
        r.id = id;
        r.type = str(m.get("type"), RequestType.VOLUNTEER.name());
        r.title = str(m.get("title"), "");
        r.description = str(m.get("description"), "");
        r.contact = str(m.get("contact"), "");
        r.lat = num(m.get("lat"));
        r.lon = num(m.get("lon"));
        r.hasLocation = Boolean.TRUE.equals(m.get("hasLocation"));
        r.ownerUid = str(m.get("ownerUid"), "");
        r.ownerName = str(m.get("ownerName"), "");
        r.status = str(m.get("status"), OPEN);
        r.createdAt = millis(m.get("createdAt"));
        r.synced = true;
        return r;
    }

    private static long millis(Object o) {
        if (o instanceof com.google.firebase.Timestamp) return ((com.google.firebase.Timestamp) o).toDate().getTime();
        if (o instanceof java.util.Date) return ((java.util.Date) o).getTime();
        return (long) num(o); // older posts stored a plain number
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0;
    }
}
