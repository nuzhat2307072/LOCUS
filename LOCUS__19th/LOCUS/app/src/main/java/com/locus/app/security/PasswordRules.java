package com.locus.app.security;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Password policy for new accounts (pure Java, unit-tested). */
public final class PasswordRules {
    public static final int MIN_LENGTH = 8;

    private static final Set<String> COMMON = new HashSet<>(Arrays.asList(
            "password", "password1", "12345678", "123456789", "1234567890", "qwertyuiop", "qwerty123",
            "iloveyou", "11111111", "abcd1234", "admin123", "letmein1", "welcome1", "locus123"));

    private PasswordRules() {
    }

    public static final int OK = 0;
    public static final int TOO_SHORT = 1;
    public static final int NEEDS_MIX = 2;
    public static final int TOO_COMMON = 3;
    public static final int HAS_EMAIL = 4;
    public static final int REPEATED = 5;

    /** @return null when the password is acceptable, otherwise an English message (the app shows {@link #check}'s code translated). */
    public static String problem(String password, String email) {
        switch (check(password, email)) {
            case TOO_SHORT:
                return "Use at least " + MIN_LENGTH + " characters";
            case NEEDS_MIX:
                return "Mix letters and numbers";
            case TOO_COMMON:
                return "This password is too common";
            case HAS_EMAIL:
                return "Do not use your e-mail in the password";
            case REPEATED:
                return "Do not repeat one character";
            default:
                return null;
        }
    }

    /** OK (0) or one of the problem codes - screens turn the code into a sentence in the app language. */
    public static int check(String password, String email) {
        if (password == null || password.length() < MIN_LENGTH) {
            return TOO_SHORT;
        }
        boolean letter = false;
        boolean digit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLetter(c)) letter = true;
            if (Character.isDigit(c)) digit = true;
        }
        if (!letter || !digit) return NEEDS_MIX;
        String lower = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lower)) return TOO_COMMON;
        if (email != null) {
            String local = email.toLowerCase(Locale.ROOT).trim();
            int at = local.indexOf('@');
            if (at > 0) local = local.substring(0, at);
            if (local.length() >= 4 && lower.contains(local)) return HAS_EMAIL;
        }
        if (allSame(password)) return REPEATED;
        return OK;
    }

    /** 0 (empty/very weak) .. 4 (strong), for the strength bar. */
    public static int strength(String password) {
        if (password == null || password.isEmpty()) return 0;
        int score = 0;
        if (password.length() >= MIN_LENGTH) score++;
        if (password.length() >= 12) score++;
        boolean lower = false, upper = false, digit = false, other = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLowerCase(c)) lower = true;
            else if (Character.isUpperCase(c)) upper = true;
            else if (Character.isDigit(c)) digit = true;
            else other = true;
        }
        int kinds = (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (other ? 1 : 0);
        if (kinds >= 2) score++;
        if (kinds >= 3) score++;
        if (COMMON.contains(password.toLowerCase(Locale.ROOT)) || allSame(password)) score = Math.min(score, 1);
        if (password.length() < MIN_LENGTH) score = Math.min(score, 1);
        return Math.min(4, score);
    }

    public static String label(int strength) {
        switch (strength) {
            case 0:
            case 1:
                return "Weak";
            case 2:
                return "Fair";
            case 3:
                return "Good";
            default:
                return "Strong";
        }
    }

    private static boolean allSame(String s) {
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) != s.charAt(0)) return false;
        }
        return s.length() > 0;
    }
}
