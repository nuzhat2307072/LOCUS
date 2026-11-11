package com.locus.app.model;

/** Size rules for photos sent in a chat (unit-tested). Photos travel inside the message, so they are kept small. */
public final class ImageRules {
    /** Longest side of a sent photo, in pixels. */
    public static final int MAX_SIDE = 720;
    /** The most characters of base64 text a photo may have (the database rule allows 160000). */
    public static final int MAX_BASE64 = 150_000;

    private ImageRules() {
    }

    /** Largest power of two by which the picture can be shrunk while its longest side stays >= MAX_SIDE. */
    public static int sampleSize(int width, int height) {
        int longest = Math.max(width, height);
        int sample = 1;
        while (longest / (sample * 2) >= MAX_SIDE) sample *= 2;
        return sample;
    }

    /** Pixel size after scaling so that the longest side is at most MAX_SIDE; never enlarges. */
    public static int[] fit(int width, int height) {
        int longest = Math.max(width, height);
        if (longest <= MAX_SIDE || longest == 0) return new int[]{width, height};
        double k = (double) MAX_SIDE / longest;
        return new int[]{Math.max(1, (int) Math.round(width * k)), Math.max(1, (int) Math.round(height * k))};
    }

    /** Base64 text length of this many bytes. */
    public static int base64Length(int bytes) {
        return (bytes + 2) / 3 * 4;
    }

    public static boolean fits(int bytes) {
        return base64Length(bytes) <= MAX_BASE64;
    }
}
