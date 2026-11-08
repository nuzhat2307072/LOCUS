package com.locus.app.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

/** One navigation, logged automatically when it ends (arrived, or ended by the person). */
@Entity(tableName = "trips")
public class Trip implements Serializable {
    @PrimaryKey
    @NonNull
    public String id = "";
    public long startedAt;
    public long endedAt;
    @NonNull
    public String startName = "";
    public double startLat;
    public double startLon;
    @NonNull
    public String destName = "";
    public double destLat;
    public double destLon;
    /** Distance really travelled, metres. */
    public double meters;
    /** Time really taken, seconds. */
    public long seconds;
    /** WALK, CYCLE or CAR. */
    @NonNull
    public String mode = "CAR";
    public boolean completed;
    /** The route that was followed, "lat,lon;lat,lon;..." (at most about 300 points). */
    @NonNull
    public String path = "";

    /** Keeps at most {@code max} evenly spaced points; always keeps the first and the last. */
    public static String encodePath(java.util.List<double[]> pts, int max) {
        if (pts == null || pts.isEmpty()) return "";
        int n = pts.size();
        int step = Math.max(1, (int) Math.ceil(n / (double) max));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i += step) append(sb, pts.get(i));
        if ((n - 1) % step != 0) append(sb, pts.get(n - 1));
        return sb.toString();
    }

    private static void append(StringBuilder sb, double[] p) {
        if (sb.length() > 0) sb.append(';');
        sb.append(String.format(java.util.Locale.US, "%.5f,%.5f", p[0], p[1]));
    }

    public static java.util.List<double[]> decodePath(String s) {
        java.util.List<double[]> out = new java.util.ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(";")) {
            String[] ll = part.split(",");
            if (ll.length != 2) continue;
            try {
                out.add(new double[]{Double.parseDouble(ll[0]), Double.parseDouble(ll[1])});
            } catch (NumberFormatException ignored) {
                // skip a damaged point
            }
        }
        return out;
    }
}
