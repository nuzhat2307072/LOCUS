package com.locus.app.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.SecureRandom;

/** Hazard votes, live-share stop rules and offline tile maths. */
public class FeatureRulesTest {

    // ---- hazard confirmation votes ------------------------------------------------------------
    @Test
    public void confidenceFollowsTheVotes() {
        assertEquals(HazardTrust.Confidence.NEW, HazardTrust.confidence(0, 0));
        assertEquals(HazardTrust.Confidence.NEW, HazardTrust.confidence(1, 0));
        assertEquals(HazardTrust.Confidence.CONFIRMED, HazardTrust.confidence(2, 0));
        assertEquals(HazardTrust.Confidence.CONFIRMED, HazardTrust.confidence(3, 1));
        assertEquals(HazardTrust.Confidence.DISPUTED, HazardTrust.confidence(1, 1));
        assertEquals(HazardTrust.Confidence.DISPUTED, HazardTrust.confidence(0, 1));
        assertEquals(HazardTrust.Confidence.DISPUTED, HazardTrust.confidence(2, 2));
    }

    @Test
    public void twoClearedVotesRemoveTheHazardOnlyWhenTheyOutnumberStill() {
        assertFalse(HazardTrust.removedByVotes(0, 1));
        assertTrue(HazardTrust.removedByVotes(0, 2));
        assertFalse(HazardTrust.removedByVotes(2, 2));
        assertTrue(HazardTrust.removedByVotes(1, 3));
    }

    @Test
    public void aStillThereVoteRestartsTheExpiryClock() {
        long ttl = 2 * 3600_000L;
        assertEquals(1000 + ttl, HazardTrust.expiresAt(1000, 0, ttl));
        assertEquals(5000 + ttl, HazardTrust.expiresAt(1000, 5000, ttl));
    }

    @Test
    public void reporterPointsGoUpForConfirmationsAndDownForQuickClears() {
        assertEquals(1, HazardTrust.reputationDelta(null, "still", 0, false));
        assertEquals(0, HazardTrust.reputationDelta("still", "still", 0, false));
        assertEquals(-1, HazardTrust.reputationDelta("still", "cleared", HazardTrust.QUICK_CLEAR_MS * 2, false));
        assertEquals(-2, HazardTrust.reputationDelta(null, "cleared", 60_000, true));
        assertEquals(0, HazardTrust.reputationDelta(null, "cleared", HazardTrust.QUICK_CLEAR_MS * 2, true));
    }

    // ---- live location sharing ------------------------------------------------------------------
    @Test
    public void shareCodesAreSixSafeCharacters() {
        String a = ShareRules.newCode(new SecureRandom());
        assertEquals(ShareRules.CODE_LENGTH, a.length());
        assertTrue(a.matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}"));
        assertEquals("AB23CD", ShareRules.normalizeCode(" ab-23 cd "));
    }

    @Test
    public void sharingStopsForTimeArrivalOrLowBattery() {
        long now = 10_000;
        assertEquals(ShareRules.StopReason.NONE, ShareRules.stopReason(now, now + 1, false, -1, 80, false));
        assertEquals(ShareRules.StopReason.TIME_UP, ShareRules.stopReason(now, now, false, -1, 80, false));
        assertEquals(ShareRules.StopReason.ARRIVED, ShareRules.stopReason(now, 0, true, 30, 80, false));
        assertEquals(ShareRules.StopReason.NONE, ShareRules.stopReason(now, 0, true, 500, 80, false));
        assertEquals(ShareRules.StopReason.NONE, ShareRules.stopReason(now, 0, false, 10, 80, false)); // not "until I arrive"
        assertEquals(ShareRules.StopReason.LOW_BATTERY, ShareRules.stopReason(now, 0, false, -1, 10, false));
        assertEquals(ShareRules.StopReason.NONE, ShareRules.stopReason(now, 0, false, -1, 10, true));   // plugged in
    }

    // ---- offline maps -----------------------------------------------------------------------------
    @Test
    public void tileCountGrowsWithZoomAndWithArea() {
        double[] box = TileMath.boxAround(22.80, 89.54, 20);
        long z12 = TileMath.tilesAt(box[1], box[0], box[3], box[2], 12);
        long z14 = TileMath.tilesAt(box[1], box[0], box[3], box[2], 14);
        assertTrue(z14 > z12 * 8);
        assertEquals(TileMath.tilesAt(box[1], box[0], box[3], box[2], 10) + TileMath.tilesAt(box[1], box[0], box[3], box[2], 11),
                TileMath.count(box[1], box[0], box[3], box[2], 10, 11));
        assertEquals(20 * 1024L * 100, TileMath.estimateBytes(100));
    }

    @Test
    public void aWholeCountryAtHighZoomIsRefused() {
        assertTrue(TileMath.count(26.6, 20.7, 92.7, 88.0, 10, 16) > TileMath.MAX_TILES);
        double[] small = TileMath.boxAround(22.80, 89.54, 2);
        assertTrue(TileMath.count(small[1], small[0], small[3], small[2], 10, 14) < TileMath.MAX_TILES);
    }

    @Test
    public void aTileInsideTheBoxIsFoundAndOneOutsideIsNot() {
        double[] box = TileMath.boxAround(22.80, 89.54, 3);
        int x = TileMath.tileX(89.54, 14), y = TileMath.tileY(22.80, 14);
        assertTrue(TileMath.tileInBox(14, x, y, box[1], box[0], box[3], box[2]));
        assertFalse(TileMath.tileInBox(14, x + 500, y, box[1], box[0], box[3], box[2]));
    }
}
