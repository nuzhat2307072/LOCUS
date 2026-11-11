package com.locus.app.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** Group stream posts: what each kind needs, voting, who may delete. */
public class GroupPostRulesTest {
    private static final long NOW = 1_000_000;

    @Test
    public void aMessageNeedsText() {
        assertTrue(GroupPostRules.validNew("text", "", "hello", false, 0, NOW));
        assertFalse(GroupPostRules.validNew("text", "", "   ", false, 0, NOW));
    }

    @Test
    public void aPhotoNeedsAPhoto() {
        assertTrue(GroupPostRules.validNew("photo", "", "", true, 0, NOW));
        assertFalse(GroupPostRules.validNew("photo", "", "caption", false, 0, NOW));
    }

    @Test
    public void tripsAndPartiesNeedATitleAndAFutureTime() {
        assertTrue(GroupPostRules.validNew("trip", "Cox's Bazar", "", false, NOW + 1, NOW));
        assertTrue(GroupPostRules.validNew("party", "Eid party", "", false, NOW + 1, NOW));
        assertFalse(GroupPostRules.validNew("trip", "", "", false, NOW + 1, NOW));
        assertFalse(GroupPostRules.validNew("trip", "Trip", "", false, NOW, NOW));
        assertFalse(GroupPostRules.validNew("party", "Party", "", false, 0, NOW));
    }

    @Test
    public void aHelpRequestNeedsATitleOnly() {
        assertTrue(GroupPostRules.validNew("help", "Need blood O+", "", false, 0, NOW));
        assertFalse(GroupPostRules.validNew("help", " ", "details", false, 0, NOW));
    }

    @Test
    public void unknownKindsAreRefused() {
        assertFalse(GroupPostRules.validNew("call", "x", "x", false, NOW + 1, NOW));
        assertFalse(GroupPostRules.validNew(null, "x", "x", false, 0, NOW));
        assertFalse(GroupPostRules.isKnownType("call"));
    }

    @Test
    public void onlyPlansAndHelpAreVotedOn() {
        assertTrue(GroupPostRules.canVote("trip"));
        assertTrue(GroupPostRules.canVote("party"));
        assertTrue(GroupPostRules.canVote("help"));
        assertFalse(GroupPostRules.canVote("text"));
        assertFalse(GroupPostRules.canVote("emergency"));
    }

    @Test
    public void tallyCountsEachAnswerAndIgnoresJunk() {
        int[] c = GroupPostRules.tally(Arrays.asList("yes", "yes", "maybe", "no", "banana", null));
        assertEquals(2, c[0]);
        assertEquals(1, c[1]);
        assertEquals(1, c[2]);
        int[] none = GroupPostRules.tally(Collections.<String>emptyList());
        assertEquals(0, none[0] + none[1] + none[2]);
        assertEquals(0, GroupPostRules.tally(null)[0]);
    }

    @Test
    public void tappingMyCurrentVoteTakesItBack() {
        assertEquals("yes", GroupPostRules.nextVote(null, "yes"));
        assertEquals("no", GroupPostRules.nextVote("yes", "no"));
        assertNull(GroupPostRules.nextVote("yes", "yes"));
    }

    @Test
    public void authorOrAdminMayDelete() {
        assertTrue(GroupPostRules.canDelete("a", "a", false));
        assertTrue(GroupPostRules.canDelete("admin", "a", true));
        assertFalse(GroupPostRules.canDelete("b", "a", false));
        assertFalse(GroupPostRules.canDelete("", "", false));
        assertFalse(GroupPostRules.canDelete(null, "a", true));
    }

    @Test
    public void validVotesAndHelpKinds() {
        assertTrue(GroupPostRules.isVote("maybe"));
        assertFalse(GroupPostRules.isVote("sure"));
        assertTrue(GroupPostRules.isHelpKind("blood"));
        assertFalse(GroupPostRules.isHelpKind("pizza"));
    }

    @Test
    public void textIsCleanedAndPreviewsAreShort() {
        assertEquals("hi", GroupPostRules.clean("  hi ", 10));
        assertEquals("", GroupPostRules.clean(null, 10));
        assertEquals("abc", GroupPostRules.clean("abcdef", 3));
        assertEquals("Trip - Bring food", GroupPostRules.preview("Trip", "Bring food", 50));
        assertEquals("Only title", GroupPostRules.preview("Only title", "", 50));
        assertEquals("one two", GroupPostRules.preview("", "one\ntwo", 50));
        String p = GroupPostRules.preview("", "abcdefghijklmnop", 8);
        assertEquals(8, p.length());
        assertTrue(p.endsWith("…"));
    }
}
