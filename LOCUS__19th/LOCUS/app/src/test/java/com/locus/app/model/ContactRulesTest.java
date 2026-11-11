package com.locus.app.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Contact requests: states, direct chat id, notes. */
public class ContactRulesTest {
    private static final long DAY = ContactRules.RETRY_AFTER_MS;

    @Test
    public void aContactWinsOverEverything() {
        assertEquals(ContactRules.CONTACT, ContactRules.state(true, "pending", 0, 5));
        assertEquals(ContactRules.CONTACT, ContactRules.state(true, null, 0, 5));
    }

    @Test
    public void noRequestMeansNone() {
        assertEquals(ContactRules.NONE, ContactRules.state(false, null, 0, 5));
        assertEquals(ContactRules.NONE, ContactRules.state(false, "weird", 0, 5));
    }

    @Test
    public void pendingStaysPending() {
        assertEquals(ContactRules.PENDING, ContactRules.state(false, "pending", 100, 100 + 10 * DAY));
    }

    @Test
    public void declinedBlocksForADayThenAllowsANewRequest() {
        assertEquals(ContactRules.DECLINED, ContactRules.state(false, "declined", 1000, 1000 + DAY - 1));
        assertEquals(ContactRules.NONE, ContactRules.state(false, "declined", 1000, 1000 + DAY));
    }

    @Test
    public void directChatIsTheSameForBothPeople() {
        assertEquals(ContactRules.directChatId("bob", "alice"), ContactRules.directChatId("alice", "bob"));
        assertEquals("dm_alice_bob", ContactRules.directChatId("bob", "alice"));
        assertTrue(ContactRules.isDirectChat("dm_alice_bob"));
        assertFalse(ContactRules.isDirectChat("req1_alice"));
        assertFalse(ContactRules.isDirectChat(null));
    }

    @Test
    public void notesAreTrimmedAndCut() {
        assertEquals("", ContactRules.note(null));
        assertEquals("hi", ContactRules.note("  hi  "));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) sb.append('x');
        assertEquals(ContactRules.MAX_NOTE, ContactRules.note(sb.toString()).length());
    }

    @Test
    public void onlyPendingRequestsNeedAnAnswer() {
        assertTrue(ContactRules.needsAnswer("pending"));
        assertFalse(ContactRules.needsAnswer("declined"));
        assertFalse(ContactRules.needsAnswer(null));
    }
}
