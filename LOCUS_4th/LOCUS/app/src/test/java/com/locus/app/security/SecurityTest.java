package com.locus.app.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SecurityTest {
    @Test
    public void shortOrSimplePasswordsAreRejected() {
        assertNotNull(PasswordRules.problem("abc12", "a@b.com"));
        assertNotNull(PasswordRules.problem("abcdefgh", "a@b.com"));   // no digit
        assertNotNull(PasswordRules.problem("12345678", "a@b.com"));   // no letter / common
        assertNotNull(PasswordRules.problem("password1", "a@b.com"));  // common
    }

    @Test
    public void problemCodesMatchTheMessages() {
        assertEquals(PasswordRules.TOO_SHORT, PasswordRules.check("abc12", "a@b.com"));
        assertEquals(PasswordRules.NEEDS_MIX, PasswordRules.check("abcdefgh", "a@b.com"));
        assertEquals(PasswordRules.TOO_COMMON, PasswordRules.check("password1", "a@b.com"));
        assertEquals(PasswordRules.OK, PasswordRules.check("blue7horses", "nuzhat@example.com"));
    }

    @Test
    public void goodPasswordsPass() {
        assertNull(PasswordRules.problem("Tr4ffic-Light", "nuzhat@example.com"));
        assertNull(PasswordRules.problem("blue7horses", "nuzhat@example.com"));
    }

    @Test
    public void emailInsidePasswordIsRejected() {
        assertNotNull(PasswordRules.problem("nuzhat2026x", "nuzhat@example.com"));
    }

    @Test
    public void strengthGrowsWithQuality() {
        assertEquals(0, PasswordRules.strength(""));
        assertTrue(PasswordRules.strength("abc") <= 1);
        assertTrue(PasswordRules.strength("blue7horses") >= 2);
        assertEquals(4, PasswordRules.strength("Tr4ffic-Light!9"));
        assertTrue(PasswordRules.strength("password1") <= 1);
    }

    @Test
    public void hashingIsSaltedAndVerifies() {
        String h1 = PasswordHasher.hash("Tr4ffic-Light");
        String h2 = PasswordHasher.hash("Tr4ffic-Light");
        assertFalse("salt must make hashes differ", h1.equals(h2));
        assertFalse("password must not be stored", h1.contains("Tr4ffic"));
        assertTrue(PasswordHasher.verify("Tr4ffic-Light", h1));
        assertTrue(PasswordHasher.verify("Tr4ffic-Light", h2));
        assertFalse(PasswordHasher.verify("tr4ffic-light", h1));
        assertFalse(PasswordHasher.verify("", h1));
        assertFalse(PasswordHasher.verify("x", "garbage"));
        assertFalse(PasswordHasher.verify("x", null));
    }

    @Test
    public void lockoutStartsAfterFiveFailuresAndDoubles() {
        assertEquals(0, LockoutPolicy.lockSeconds(0));
        assertEquals(0, LockoutPolicy.lockSeconds(4));
        assertEquals(30, LockoutPolicy.lockSeconds(5));
        assertEquals(60, LockoutPolicy.lockSeconds(6));
        assertEquals(120, LockoutPolicy.lockSeconds(7));
        assertEquals(900, LockoutPolicy.lockSeconds(40)); // capped at 15 minutes
        assertEquals(5, LockoutPolicy.attemptsLeft(0));
        assertEquals(1, LockoutPolicy.attemptsLeft(4));
        assertEquals(0, LockoutPolicy.attemptsLeft(9));
    }
}
