package com.locus.app.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Password reset by e-mail: cleaning, cooldown, language, error meaning. */
public class ResetRulesTest {
    @Test
    public void emailIsTrimmedAndLowerCased() {
        assertEquals("nuzhat@mail.com", ResetRules.normalize("  Nuzhat@Mail.COM "));
        assertEquals("", ResetRules.normalize(null));
    }

    @Test
    public void aNewLinkMayBeAskedForAfterOneMinute() {
        long t = 1_000_000;
        assertEquals(0, ResetRules.cooldownLeftMs(0, t)); // never sent
        assertEquals(ResetRules.COOLDOWN_MS, ResetRules.cooldownLeftMs(t, t));
        assertEquals(30_000, ResetRules.cooldownLeftMs(t, t + 30_000));
        assertEquals(0, ResetRules.cooldownLeftMs(t, t + ResetRules.COOLDOWN_MS));
        assertEquals(0, ResetRules.cooldownLeftMs(t, t + 10 * ResetRules.COOLDOWN_MS));
    }

    @Test
    public void aClockSetBackDoesNotLockThePersonOutForLong() {
        // the "last sent" time is in the future: wait at most one cooldown
        assertEquals(ResetRules.COOLDOWN_MS, ResetRules.cooldownLeftMs(5_000_000, 1_000));
    }

    @Test
    public void countdownSecondsRoundUp() {
        assertEquals(60, ResetRules.secondsLeft(1000, 1000));
        assertEquals(1, ResetRules.secondsLeft(1000, 1000 + 59_001));
        assertEquals(0, ResetRules.secondsLeft(1000, 1000 + 60_000));
    }

    @Test
    public void emailLanguageIsEnglishBanglaOrHindi() {
        assertEquals("bn", ResetRules.emailLanguage("bn"));
        assertEquals("hi", ResetRules.emailLanguage("HI"));
        assertEquals("en", ResetRules.emailLanguage("en"));
        assertEquals("en", ResetRules.emailLanguage("fr"));
        assertEquals("en", ResetRules.emailLanguage(null));
    }

    @Test
    public void firebaseErrorsGetTheirOwnMeaning() {
        assertEquals(ResetRules.Problem.NETWORK, ResetRules.classify("ERROR_X", true));
        assertEquals(ResetRules.Problem.NO_ACCOUNT, ResetRules.classify("ERROR_USER_NOT_FOUND", false));
        assertEquals(ResetRules.Problem.INVALID_EMAIL, ResetRules.classify("ERROR_INVALID_EMAIL", false));
        assertEquals(ResetRules.Problem.TOO_MANY, ResetRules.classify("ERROR_TOO_MANY_REQUESTS", false));
        assertEquals(ResetRules.Problem.DISABLED, ResetRules.classify("ERROR_OPERATION_NOT_ALLOWED", false));
        assertEquals(ResetRules.Problem.OTHER, ResetRules.classify("ERROR_SOMETHING", false));
        assertEquals(ResetRules.Problem.OTHER, ResetRules.classify(null, false));
    }

    @Test
    public void whatIsKnownAboutTheAccount() {
        assertEquals(ResetRules.Knowledge.CLOUD, ResetRules.knowledge(true, true));
        assertEquals(ResetRules.Knowledge.CLOUD, ResetRules.knowledge(true, false));
        assertEquals(ResetRules.Knowledge.LOCAL_ONLY, ResetRules.knowledge(false, true));
        assertEquals(ResetRules.Knowledge.UNKNOWN, ResetRules.knowledge(false, false));
    }
}
