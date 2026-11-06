package com.locus.app.security;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * One-time reset code for an account stored on this phone ("Forgot password?"): a 6-digit number that is
 * e-mailed to the owner, valid for 10 minutes and for 5 wrong tries, then burnt. Pure Java, so it is unit-tested.
 * Only a hash is kept in memory.
 */
public final class ResetCode {
    public static final long VALID_MS = 10 * 60 * 1000L;
    public static final int MAX_TRIES = 5;

    public static final int OK = 0;
    public static final int WRONG = 1;
    public static final int EXPIRED = 2;

    private static final SecureRandom RANDOM = new SecureRandom();

    private byte[] hash;
    private long expiresAt;
    private int triesLeft;

    /** Creates a new code (replacing any earlier one) and returns it so that it can be e-mailed. */
    public synchronized String issue(long now) {
        String code = String.format(java.util.Locale.US, "%06d", RANDOM.nextInt(1_000_000));
        hash = digest(code);
        expiresAt = now + VALID_MS;
        triesLeft = MAX_TRIES;
        return code;
    }

    public synchronized boolean isActive(long now) {
        return hash != null && now <= expiresAt && triesLeft > 0;
    }

    public synchronized int triesLeft() {
        return triesLeft;
    }

    /** OK (and the code is burnt), WRONG (one try used) or EXPIRED (no code, too old, or too many wrong tries). */
    public synchronized int check(String entered, long now) {
        if (!isActive(now)) {
            hash = null;
            return EXPIRED;
        }
        boolean same = entered != null && MessageDigest.isEqual(hash, digest(entered.trim()));
        if (same) {
            hash = null;
            return OK;
        }
        triesLeft--;
        if (triesLeft <= 0) hash = null;
        return WRONG;
    }

    public synchronized void clear() {
        hash = null;
    }

    private static byte[] digest(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
