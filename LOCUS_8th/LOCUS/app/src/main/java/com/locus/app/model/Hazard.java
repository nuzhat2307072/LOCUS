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

    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        m.put("description", description);
        m.put("lat", lat);
        m.put("lon", lon);
        m.put("radiusM", radiusM);
        m.put("createdAt", createdAt);
        m.put("reporterUid", reporterUid);
        return m;
    }

    @Nullable
    public static Hazard fromMap(String id, @Nullable Map<String, Object> m) {
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
        return h;
    }

    private static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0;
    }
}
