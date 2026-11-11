package com.locus.app.model;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Trip calendar rules: the reminder time (6 hours before the start), which trips still need a reminder, and the
 * month grid. Pure Java, tested on the JVM.
 */
public final class TripRules {
    /** The reminder comes this long before the trip starts. */
    public static final long REMIND_BEFORE_MS = 6L * 3600_000L;
    /** A reminder is not shown for a trip that started more than this long ago (the phone was off). */
    public static final long LATE_GRACE_MS = 0;

    private TripRules() {
    }

    public static long reminderAt(long startAt) {
        return startAt - REMIND_BEFORE_MS;
    }

    /** The reminder is due now: its time has come, the trip has not started, and it was not shown before. */
    public static boolean reminderDue(long startAt, boolean reminded, long now) {
        return !reminded && now >= reminderAt(startAt) && now < startAt + LATE_GRACE_MS;
    }

    /** Whole minutes from now until the start (0 or less once it started). */
    public static long minutesUntil(long startAt, long now) {
        return Math.max(0, (startAt - now + 59_999L) / 60_000L);
    }

    /** When the trip is saved less than 6 hours before it starts, its reminder is shown at once. */
    public static boolean reminderIsImmediate(long startAt, long now) {
        return startAt > now && reminderAt(startAt) <= now;
    }

    // ---- month grid -----------------------------------------------------------------------------------

    /**
     * The cells of one month as a flat list of 6 rows x 7 columns: 0 for an empty cell, otherwise the day of the
     * month. {@code firstDayOfWeek} is a {@link Calendar} constant (SUNDAY, SATURDAY ...).
     */
    public static int[] monthGrid(int year, int month0, int firstDayOfWeek, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(year, month0, 1, 12, 0, 0);
        int days = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        int dow = c.get(Calendar.DAY_OF_WEEK); // 1 = Sunday .. 7 = Saturday
        int lead = ((dow - firstDayOfWeek) % 7 + 7) % 7;
        int[] cells = new int[42];
        for (int d = 1; d <= days; d++) cells[lead + d - 1] = d;
        return cells;
    }

    /** Start of the given day (local midnight) in UTC milliseconds. */
    public static long dayStart(int year, int month0, int day, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(year, month0, day, 0, 0, 0);
        return c.getTimeInMillis();
    }

    /** Start of the next day (handles 23 / 25 hour days). */
    public static long nextDayStart(int year, int month0, int day, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(year, month0, day, 0, 0, 0);
        c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    /** Day of month of a moment (in the given zone). */
    public static int dayOf(long millis, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(millis);
        return c.get(Calendar.DAY_OF_MONTH);
    }

    /** Moment of a date and time of day in the given zone. */
    public static long at(int year, int month0, int day, int hour, int minute, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(year, month0, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    /** A usable trip: a destination point and a start time in the future. */
    public static boolean isValid(String destName, double destLat, double destLon, long startAt, long now) {
        if (destName == null || destName.trim().isEmpty()) return false;
        if (destLat == 0 && destLon == 0) return false;
        return startAt > now;
    }
}
