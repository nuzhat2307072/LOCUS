package com.locus.app.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Request chat: ids, message preparation, "seen", closing and notification rules. */
public class ChatRulesTest {
    @Test
    public void theSameHelperAlwaysGetsTheSameChatForARequest() {
        assertEquals("req1_uidA", ChatRules.chatId("req1", "uidA"));
        assertEquals(ChatRules.chatId("req1", "uidA"), ChatRules.chatId("req1", "uidA"));
        assertFalse(ChatRules.chatId("req1", "uidA").equals(ChatRules.chatId("req1", "uidB")));
        assertFalse(ChatRules.chatId("req1", "uidA").equals(ChatRules.chatId("req2", "uidA")));
    }

    @Test
    public void chatIdsContainNoCharacterThatDatabaseKeysForbid() {
        String id = ChatRules.chatId("a.b#c$d[e]f/g", "x.y");
        for (char c : ".$#[]/".toCharArray()) assertFalse(id.indexOf(c) >= 0);
    }

    @Test
    public void emptyMessagesAreNotSentAndLongOnesAreCut() {
        assertNull(ChatRules.prepare(null));
        assertNull(ChatRules.prepare("   \n "));
        assertEquals("hello", ChatRules.prepare("  hello  "));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1500; i++) sb.append('x');
        assertEquals(ChatRules.MAX_LENGTH, ChatRules.prepare(sb.toString()).length());
    }

    @Test
    public void previewIsOneShortLine() {
        assertEquals("a b", ChatRules.preview("a\nb"));
        String p = ChatRules.preview("0123456789012345678901234567890123456789012345678901234567890123456789");
        assertEquals(ChatRules.PREVIEW_LENGTH, p.length());
        assertTrue(p.endsWith("…"));
        assertEquals("", ChatRules.preview(null));
    }

    @Test
    public void aMessageIsSeenOnlyWhenTheOtherOpenedTheChatAfterIt() {
        assertFalse(ChatRules.isSeen(1000, 0));
        assertFalse(ChatRules.isSeen(1000, 999));
        assertTrue(ChatRules.isSeen(1000, 1000));
        assertTrue(ChatRules.isSeen(1000, 5000));
    }

    @Test
    public void theOtherPersonIsTheOneWhoIsNotMe() {
        assertEquals("helper", ChatRules.otherUid("owner", "helper", "owner"));
        assertEquals("owner", ChatRules.otherUid("owner", "helper", "helper"));
    }

    @Test
    public void aFulfilledRequestMakesTheChatReadOnly() {
        assertTrue(ChatRules.canWrite(false, false));
        assertFalse(ChatRules.canWrite(true, false));
        assertFalse(ChatRules.canWrite(false, true));
    }

    @Test
    public void notificationsOnlyForNewMessagesFromTheOtherPersonOutsideTheOpenChat() {
        assertTrue(ChatRules.shouldNotify("other", "me", 1, 200, 100, false));
        assertFalse(ChatRules.shouldNotify("me", "me", 1, 200, 100, false));    // my own message
        assertFalse(ChatRules.shouldNotify("other", "me", 0, 200, 100, false));  // already read
        assertFalse(ChatRules.shouldNotify("other", "me", 1, 100, 100, false));  // already announced
        assertFalse(ChatRules.shouldNotify("other", "me", 1, 200, 100, true));   // the chat is on screen
        assertFalse(ChatRules.shouldNotify(null, "me", 1, 200, 100, false));
    }
}
