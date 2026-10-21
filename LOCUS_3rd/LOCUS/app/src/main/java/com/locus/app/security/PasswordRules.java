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

    /** @return null when the password is acceptable, otherwise a message for the user. */
    public static String problem(String password, String email) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "Use at least " + MIN_LENGTH + " characters";
        }
        boolean letter = false;
        boolean digit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLetter(c)) letter = true;
            if (Character.isDigit(c)) digit = true;
        }
        if (!letter || !digit) return "Mix letters and numbers";
        String lower = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lower)) return "This password is too common";
        if (email != null) {
            String local = email.toLowerCase(Locale.ROOT).trim();
            int at = local.indexOf('@');
            if (at > 0) local = local.substring(0, at);
            if (local.length() >= 4 && lower.contains(local)) return "Do not use your e-mail in the password";
        }
        if (allSame(password)) return "Do not repeat one character";
        return null;
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
