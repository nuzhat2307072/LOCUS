package com.locus.app.util;

import com.locus.app.model.Trip;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Trip history as a CSV text (opens in any spreadsheet). */
public final class TripCsv {
    private TripCsv() {
    }

    public static String field(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    public static String build(List<Trip> trips) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        StringBuilder sb = new StringBuilder("date,start,destination,mode,distance_km,duration_min,completed\n");
        for (Trip t : trips) {
            sb.append(f.format(new Date(t.startedAt))).append(',')
                    .append(field(t.startName)).append(',')
                    .append(field(t.destName)).append(',')
                    .append(t.mode).append(',')
                    .append(String.format(Locale.US, "%.2f", t.meters / 1000.0)).append(',')
                    .append(String.format(Locale.US, "%.1f", t.seconds / 60.0)).append(',')
                    .append(t.completed ? "yes" : "no").append('\n');
        }
        return sb.toString();
    }
}
