package com.locus.app.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/** Trip calendar: the 6-hour reminder and the month grid. */
public class TripRulesTest {
    private static final TimeZone DHAKA = TimeZone.getTimeZone("Asia/Dhaka");
    private static final long HOUR = 3600_000L;

    @Test
    public void reminderIsSixHoursBeforeTheStart() {
        assertEquals(1000 * HOUR - 6 * HOUR, TripRules.reminderAt(1000 * HOUR));
    }

    @Test
    public void reminderDueOnlyBetweenItsTimeAndTheStart() {
        long start = 100 * HOUR;
        assertFalse(TripRules.reminderDue(start, false, start - 6 * HOUR - 1));
        assertTrue(TripRules.reminderDue(start, false, start - 6 * HOUR));
        assertTrue(TripRules.reminderDue(start, false, start - 1));
        assertFalse(TripRules.reminderDue(start, false, start)); // started: too late
        assertFalse(TripRules.reminderDue(start, true, start - HOUR)); // already shown
    }

    @Test
    public void aTripSavedInsideSixHoursIsRemindedAtOnce() {
        long now = 50 * HOUR;
        assertTrue(TripRules.reminderIsImmediate(now + 2 * HOUR, now));
        assertFalse(TripRules.reminderIsImmediate(now + 7 * HOUR, now));
        assertFalse(TripRules.reminderIsImmediate(now - HOUR, now));
    }

    @Test
    public void minutesUntilRoundsUpAndNeverGoesNegative() {
        assertEquals(60, TripRules.minutesUntil(HOUR, 0));
        assertEquals(1, TripRules.minutesUntil(1, 0));
        assertEquals(0, TripRules.minutesUntil(0, HOUR));
    }

    @Test
    public void aTripNeedsADestinationAndAFutureStart() {
        assertTrue(TripRules.isValid("Khulna", 22.8, 89.5, 10, 5));
        assertFalse(TripRules.isValid("", 22.8, 89.5, 10, 5));
        assertFalse(TripRules.isValid(null, 22.8, 89.5, 10, 5));
        assertFalse(TripRules.isValid("Khulna", 0, 0, 10, 5));
        assertFalse(TripRules.isValid("Khulna", 22.8, 89.5, 5, 5));
    }

    @Test
    public void novemberTwentyTwentySixStartsOnSunday() {
        // 1 Nov 2026 is a Sunday; with weeks starting on Sunday the first cell is day 1
        int[] g = TripRules.monthGrid(2026, Calendar.NOVEMBER, Calendar.SUNDAY, DHAKA);
        assertEquals(42, g.length);
        assertEquals(1, g[0]);
        assertEquals(30, g[29]);
        assertEquals(0, g[30]);
    }

    @Test
    public void theGridShiftsWithTheFirstDayOfTheWeek() {
        // weeks starting on Saturday: Sunday 1 Nov is the second cell
        int[] g = TripRules.monthGrid(2026, Calendar.NOVEMBER, Calendar.SATURDAY, DHAKA);
        assertEquals(0, g[0]);
        assertEquals(1, g[1]);
        // weeks starting on Monday: six empty cells first
        int[] m = TripRules.monthGrid(2026, Calendar.NOVEMBER, Calendar.MONDAY, DHAKA);
        assertEquals(0, m[5]);
        assertEquals(1, m[6]);
    }

    @Test
    public void everyDayAppearsExactlyOnce() {
        for (int month = 0; month < 12; month++) {
            int[] g = TripRules.monthGrid(2028, month, Calendar.SUNDAY, DHAKA);
            int count = 0;
            int last = 0;
            for (int d : g) {
                if (d != 0) {
                    count++;
                    assertEquals(last + 1, d);
                    last = d;
                }
            }
            Calendar c = Calendar.getInstance(DHAKA);
            c.clear();
            c.set(2028, month, 1);
            assertEquals(c.getActualMaximum(Calendar.DAY_OF_MONTH), count);
        }
        // leap year
        int[] feb = TripRules.monthGrid(2028, Calendar.FEBRUARY, Calendar.SUNDAY, DHAKA);
        int max = 0;
        for (int d : feb) max = Math.max(max, d);
        assertEquals(29, max);
    }

    @Test
    public void dayBoundariesAndDayOfMoment() {
        long start = TripRules.dayStart(2026, Calendar.NOVEMBER, 11, DHAKA);
        long next = TripRules.nextDayStart(2026, Calendar.NOVEMBER, 11, DHAKA);
        assertEquals(24 * HOUR, next - start);
        long evening = TripRules.at(2026, Calendar.NOVEMBER, 11, 23, 30, DHAKA);
        assertTrue(evening >= start && evening < next);
        assertEquals(11, TripRules.dayOf(evening, DHAKA));
        assertEquals(12, TripRules.dayOf(next, DHAKA));
        // the end of December rolls into the next year
        assertEquals(TripRules.dayStart(2027, Calendar.JANUARY, 1, DHAKA), TripRules.nextDayStart(2026, Calendar.DECEMBER, 31, DHAKA));
        assertEquals(TripRules.dayStart(2027, Calendar.JANUARY, 1, DHAKA), TripRules.dayStart(2026, 12, 1, DHAKA));
    }
}
