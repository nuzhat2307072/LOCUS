package com.locus.app.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/** Family and friends groups: join codes, names, roles. */
public class GroupRulesTest {
    @Test
    public void newCodesAreSixSafeCharacters() {
        Random r = new Random(7);
        for (int i = 0; i < 500; i++) {
            String c = GroupRules.newCode(r);
            assertEquals(GroupRules.CODE_LENGTH, c.length());
            assertTrue(GroupRules.isValidCode(c));
            assertTrue(c.indexOf('O') < 0 && c.indexOf('0') < 0 && c.indexOf('I') < 0 && c.indexOf('1') < 0);
        }
    }

    @Test
    public void codesDoNotRepeatTooOften() {
        Random r = new Random(1);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) seen.add(GroupRules.newCode(r));
        assertTrue(seen.size() > 990);
    }

    @Test
    public void typedCodesAreCleanedUp() {
        assertEquals("ABC234", GroupRules.normalizeCode(" abc-234 "));
        assertEquals("ABC234", GroupRules.normalizeCode("a b c 2 3 4"));
        assertEquals("", GroupRules.normalizeCode(null));
    }

    @Test
    public void onlyRealCodesAreValid() {
        assertTrue(GroupRules.isValidCode("ABC234"));
        assertFalse(GroupRules.isValidCode("ABC23")); // short
        assertFalse(GroupRules.isValidCode("ABC2345")); // long
        assertFalse(GroupRules.isValidCode("ABC230")); // 0 is not used
        assertFalse(GroupRules.isValidCode("abc234")); // not cleaned yet
        assertFalse(GroupRules.isValidCode(null));
    }

    @Test
    public void prettyCodeHasADash() {
        assertEquals("ABC-234", GroupRules.pretty("ABC234"));
        assertEquals("", GroupRules.pretty(null));
        assertEquals("AB", GroupRules.pretty("AB"));
    }

    @Test
    public void namesAreTrimmedSqueezedAndCut() {
        assertEquals("My family", GroupRules.cleanName("  My   family "));
        assertEquals("", GroupRules.cleanName("   "));
        assertEquals("", GroupRules.cleanName(null));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append('x');
        assertEquals(GroupRules.MAX_NAME, GroupRules.cleanName(sb.toString()).length());
    }

    @Test
    public void rolesAndRoom() {
        assertTrue(GroupRules.isAdmin("admin"));
        assertFalse(GroupRules.isAdmin("member"));
        assertFalse(GroupRules.canLeave("admin")); // the admin deletes the group instead
        assertTrue(GroupRules.canLeave("member"));
        assertTrue(GroupRules.hasRoom(GroupRules.MAX_MEMBERS - 1));
        assertFalse(GroupRules.hasRoom(GroupRules.MAX_MEMBERS));
    }
}
