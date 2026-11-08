package com.locus.app.model;

import java.util.Calendar;
import java.util.TimeZone;

/** Weekly / monthly totals of the trip history, by travel mode (plain Java, unit-tested). */
public final class TripStats {
    public static final int WEEKS = 8;
    public static final int MONTHS = 6;
    /** Mode order used in every array: walk, cycle, car. */
    public static final String[] MODES = {"WALK", "CYCLE", "CAR"};

    public static final class Result {
        /** Start of each bucket (oldest first). */
        public final long[] periodStart;
        /** [mode][bucket] in kilometres. */
        public final double[][] km;
        /** [mode][bucket] in minutes. */
        public final double[][] minutes;

        Result(int n) {
            periodStart = new long[n];
            km = new double[MODES.length][n];
            minutes = new double[MODES.length][n];
        }
    }

    private TripStats() {
    }

    public static int modeIndex(String mode) {
        for (int i = 0; i < MODES.length; i++) if (MODES[i].equals(mode)) return i;
        return 2;
    }

    private static Calendar startOfWeek(long t, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        int back = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7; // Monday = 0
        c.add(Calendar.DAY_OF_YEAR, -back);
        return c;
    }

    private static Calendar startOfMonth(long t, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(t);
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    /**
     * @param weekly true = last 8 weeks (Monday first), false = last 6 months
     */
    public static Result buckets(long[] startedAt, double[] meters, long[] seconds, String[] mode,
                                 boolean weekly, long now, TimeZone tz) {
        int n = weekly ? WEEKS : MONTHS;
        Result r = new Result(n);
        Calendar cur = weekly ? startOfWeek(now, tz) : startOfMonth(now, tz);
        for (int i = n - 1; i >= 0; i--) {
            r.periodStart[i] = cur.getTimeInMillis();
            cur.add(weekly ? Calendar.WEEK_OF_YEAR : Calendar.MONTH, -1);
        }
        for (int k = 0; k < startedAt.length; k++) {
            int idx = -1;
            for (int i = n - 1; i >= 0; i--) {
                if (startedAt[k] >= r.periodStart[i]) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0 || startedAt[k] > now + 24L * 3600 * 1000 * 31) continue; // older than the chart
            int m = modeIndex(mode[k]);
            r.km[m][idx] += meters[k] / 1000.0;
            r.minutes[m][idx] += seconds[k] / 60.0;
        }
        return r;
    }
}
