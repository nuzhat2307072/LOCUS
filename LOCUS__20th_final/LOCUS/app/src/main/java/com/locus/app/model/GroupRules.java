package com.locus.app.model;

import java.util.Random;

/**
 * Family and friends groups: the join code and the roles. The person who starts a group is its admin; others join
 * with the code, and the admin approves them. Pure Java, tested on the JVM.
 */
public final class GroupRules {
    /** No 0/O and 1/I, so a code read out loud or typed from a screenshot is not mixed up. */
    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static final int CODE_LENGTH = 6;
    public static final int MAX_NAME = 40;
    public static final int MAX_MEMBERS = 30;
    public static final String ADMIN = "admin";
    public static final String MEMBER = "member";

    private GroupRules() {
    }

    public static String newCode(Random random) {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    /** What the person typed, as a code: upper case, without spaces or dashes. Never null. */
    public static String normalizeCode(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (c == ' ' || c == '-' || c == '\t' || c == '\n') continue;
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    public static boolean isValidCode(String code) {
        if (code == null || code.length() != CODE_LENGTH) return false;
        for (char c : code.toCharArray()) if (ALPHABET.indexOf(c) < 0) return false;
        return true;
    }

    /** "ABC-DEF" is easier to read and to say than "ABCDEF". */
    public static String pretty(String code) {
        if (code == null || code.length() != CODE_LENGTH) return code == null ? "" : code;
        return code.substring(0, 3) + "-" + code.substring(3);
    }

    /** The group name: trimmed, at most MAX_NAME characters; empty when nothing usable was typed. */
    public static String cleanName(String raw) {
        if (raw == null) return "";
        String t = raw.trim().replaceAll("\\s+", " ");
        return t.length() > MAX_NAME ? t.substring(0, MAX_NAME).trim() : t;
    }

    public static boolean isAdmin(String role) {
        return ADMIN.equals(role);
    }

    /** A person may join when the group has room. */
    public static boolean hasRoom(int members) {
        return members < MAX_MEMBERS;
    }

    /** The admin cannot just leave (the group would have no admin): they delete the group or remove it. */
    public static boolean canLeave(String role) {
        return !isAdmin(role);
    }
}
