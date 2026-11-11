package com.locus.app.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/** A reported road hazard / closure. Room entity + Realtime Database node. */
@Entity(tableName = "hazards")
public class Hazard implements Serializable {
    @PrimaryKey
    @NonNull
    public String id = "";
    public String type;          // HazardType name
    public String description;
    public double lat;
    public double lon;
    public int radiusM;
    public long createdAt;
    public String reporterUid;
    public boolean synced;
    /** Last time somebody said "Still there" (0 = never); restarts the expiry clock. */
    public long lastConfirmedAt;
    public int stillVotes;
    public int clearedVotes;
    /** "Still there" / "Cleared" votes from other people who were within {@link HazardTrust#COUNT_RADIUS_M} when they voted. */
    public int nearStill;
    public int nearCleared;
    /** This user's own vote: "still", "cleared" or null. */
    public String myVote;

    public long expiresAt() {
        return HazardTrust.expiresAt(createdAt, lastConfirmedAt, HazardType.fromName(type).ttlHours * 3600_000L);
    }

    public boolean isExpired(long now) {
        return now >= expiresAt();
    }

    public HazardTrust.Confidence confidence() {
        return HazardTrust.confidence(stillVotes, clearedVotes);
    }

    /** Confirmed by at least two different people who were actually near the place. */
    public boolean isVerified() {
        return HazardTrust.verified(nearStill, nearCleared);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        m.put("description", description);
        m.put("lat", lat);
        m.put("lon", lon);
        m.put("radiusM", radiusM);
        m.put("createdAt", createdAt);
        m.put("reporterUid", reporterUid);
        m.put("lastConfirmedAt", lastConfirmedAt);
        return m;
    }

    @Nullable
    public static Hazard fromMap(String id, @Nullable Map<String, Object> m) {
        return fromMap(id, m, null);
    }

    /** @param myUid used to find this user's own vote among the votes. */
    @Nullable
    public static Hazard fromMap(String id, @Nullable Map<String, Object> m, @Nullable String myUid) {
        if (m == null) return null;
        Hazard h = new Hazard();
        h.id = id;
        h.type = m.get("type") == null ? HazardType.ACCIDENT.name() : String.valueOf(m.get("type"));
        h.description = m.get("description") == null ? "" : String.valueOf(m.get("description"));
        h.lat = num(m.get("lat"));
        h.lon = num(m.get("lon"));
        h.radiusM = (int) num(m.get("radiusM"));
        if (h.radiusM <= 0) h.radiusM = HazardType.fromName(h.type).defaultRadius;
        h.createdAt = (long) num(m.get("createdAt"));
        h.reporterUid = m.get("reporterUid") == null ? "" : String.valueOf(m.get("reporterUid"));
        h.synced = true;
        h.lastConfirmedAt = (long) num(m.get("lastConfirmedAt"));
        Object votes = m.get("votes");
        Object dists = m.get("voteDist");
        if (votes instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) votes).entrySet()) {
                String uid = String.valueOf(e.getKey());
                String v = String.valueOf(e.getValue());
                boolean reporter = uid.equals(h.reporterUid);
                double d = -1;
                if (dists instanceof Map && ((Map<?, ?>) dists).get(e.getKey()) instanceof Number) {
                    d = ((Number) ((Map<?, ?>) dists).get(e.getKey())).doubleValue();
                }
                boolean near = !reporter && HazardTrust.countsAsNearby(d);
                if ("still".equals(v)) {
                    h.stillVotes++;
                    if (near) h.nearStill++;
                } else if ("cleared".equals(v)) {
                    h.clearedVotes++;
                    if (near) h.nearCleared++;
                }
                if (myUid != null && myUid.equals(uid)) h.myVote = v;
            }
        }
        return h;
    }

    private static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0;
    }
}
