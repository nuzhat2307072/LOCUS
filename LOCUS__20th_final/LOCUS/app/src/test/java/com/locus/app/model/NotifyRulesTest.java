package com.locus.app.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Notification switches and quiet hours, and how long a hazard lives. */
public class NotifyRulesTest {
    @Test
    public void quietHoursOffMeansNeverQuiet() {
        assertFalse(NotifyRules.inQuietHours(false, 22, 7, 23));
    }

    @Test
    public void quietHoursInTheSameDay() {
        assertTrue(NotifyRules.inQuietHours(true, 13, 15, 13));
        assertTrue(NotifyRules.inQuietHours(true, 13, 15, 14));
        assertFalse(NotifyRules.inQuietHours(true, 13, 15, 15));
        assertFalse(NotifyRules.inQuietHours(true, 13, 15, 12));
    }

    @Test
    public void quietHoursCanCrossMidnight() {
        assertTrue(NotifyRules.inQuietHours(true, 22, 7, 22));
        assertTrue(NotifyRules.inQuietHours(true, 22, 7, 23));
        assertTrue(NotifyRules.inQuietHours(true, 22, 7, 0));
        assertTrue(NotifyRules.inQuietHours(true, 22, 7, 6));
        assertFalse(NotifyRules.inQuietHours(true, 22, 7, 7));
        assertFalse(NotifyRules.inQuietHours(true, 22, 7, 12));
        assertFalse(NotifyRules.inQuietHours(true, 22, 7, 21));
    }

    @Test
    public void equalHoursMeanTheWholeDay() {
        for (int h = 0; h < 24; h++) assertTrue(NotifyRules.inQuietHours(true, 8, 8, h));
    }

    @Test
    public void sosAlwaysGetsThrough() {
        assertTrue(NotifyRules.allowed(false, true, 22, 7, 23, true, false));
    }

    @Test
    public void aSwitchedOffKindIsNeverShownEvenSilent() {
        assertFalse(NotifyRules.allowed(false, false, 22, 7, 12, false, false));
        assertFalse(NotifyRules.allowed(false, true, 22, 7, 12, false, true));
    }

    @Test
    public void quietHoursHoldBackSoundsButNotSilentSummaries() {
        assertFalse(NotifyRules.allowed(true, true, 22, 7, 23, false, false));
        assertTrue(NotifyRules.allowed(true, true, 22, 7, 23, false, true));
        assertTrue(NotifyRules.allowed(true, true, 22, 7, 12, false, false));
    }

    @Test
    public void verifiedHazardsLiveHalfAsLongAgain() {
        assertEquals(4 * 3600_000L, HazardTrust.lifetimeMillis(4, false));
        assertEquals(6 * 3600_000L, HazardTrust.lifetimeMillis(4, true));
    }
}
