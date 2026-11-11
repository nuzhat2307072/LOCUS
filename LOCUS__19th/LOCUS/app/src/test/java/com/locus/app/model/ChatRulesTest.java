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

    @Test
    public void theRequestOfAChatIsEverythingBeforeTheLastUnderscore() {
        assertEquals("req1", ChatRules.requestIdOf("req1_uidA"));
        assertEquals("a_b", ChatRules.requestIdOf("a_b_uidA"));
        assertEquals("plain", ChatRules.requestIdOf("plain"));
        assertEquals("", ChatRules.requestIdOf(null));
    }

    @Test
    public void messagesAreAlwaysPlacedByTimeThenId() {
        java.util.List<ChatMessage> list = new java.util.ArrayList<>();
        list.add(new ChatMessage("a", "x", "1", 100));
        list.add(new ChatMessage("c", "x", "3", 300));
        ChatMessage mid = new ChatMessage("b", "y", "2", 200);
        list.add(ChatRules.insertIndex(list, mid), mid);
        assertEquals("b", list.get(1).id);
        ChatMessage tie = new ChatMessage("bb", "y", "2b", 200);
        list.add(ChatRules.insertIndex(list, tie), tie);
        assertEquals("bb", list.get(2).id);
        ChatMessage old = new ChatMessage("z", "y", "0", 50);
        list.add(ChatRules.insertIndex(list, old), old);
        assertEquals("z", list.get(0).id);
        ChatMessage latest = new ChatMessage("d", "y", "4", 999);
        list.add(ChatRules.insertIndex(list, latest), latest);
        assertEquals("d", list.get(list.size() - 1).id);
    }

    @Test
    public void aMessageAnnouncedOnceIsNotAnnouncedAgain() {
        assertTrue(ChatRules.shouldNotify("other", "me", 2, 500, 0, false));    // never announced: show it
        assertFalse(ChatRules.shouldNotify("other", "me", 2, 500, 500, false)); // already announced
        assertFalse(ChatRules.shouldNotify("other", "me", 2, 500, 0, true));    // that chat is on screen
    }

    @Test
    public void unreadMessagesAreRemindedOnceTheyHaveWaitedAndNeverAtNight() {
        long now = 10 * 3600_000L;
        long old = now - ChatRules.REMIND_AFTER_MS - 1;
        assertTrue(ChatRules.shouldRemind(2, old, 0, now, 14));
        assertFalse(ChatRules.shouldRemind(0, old, 0, now, 14));                         // nothing unread
        assertFalse(ChatRules.shouldRemind(2, now - 60_000, 0, now, 14));                // just arrived
        assertFalse(ChatRules.shouldRemind(2, old, now - 60_000, now, 14));              // reminded a moment ago
        assertTrue(ChatRules.shouldRemind(2, old, now - ChatRules.REMIND_EVERY_MS, now, 14));
        assertFalse(ChatRules.shouldRemind(2, old, 0, now, 23));                         // night
        assertFalse(ChatRules.shouldRemind(2, old, 0, now, 3));
        assertTrue(ChatRules.isQuietHour(22));
        assertTrue(ChatRules.isQuietHour(6));
        assertFalse(ChatRules.isQuietHour(7));
        assertFalse(ChatRules.isQuietHour(21));
    }

    @Test
    public void theInboxShowsUnreadChatsFirstThenTheNewest() {
        assertTrue(ChatRules.compareForInbox(1, 100, 0, 900) < 0);  // unread before read, even if older
        assertTrue(ChatRules.compareForInbox(0, 900, 2, 100) > 0);
        assertTrue(ChatRules.compareForInbox(1, 500, 3, 100) < 0);  // both unread: newest first
        assertTrue(ChatRules.compareForInbox(0, 100, 0, 500) > 0);  // both read: newest first
        assertEquals(0, ChatRules.compareForInbox(0, 5, 0, 5));
    }
}
