package com.buildhubs.app;

/**
 * Pixel preparation before OCR (pure Java on luminance arrays, so it can be tested on a desktop).
 */
public final class ImagePrep {

    private ImagePrep() {}

    /** Luminance (0..255) of an ARGB pixel. */
    public static int luminance(int argb) {
        int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        return (int) (0.299f * r + 0.587f * g + 0.114f * b + 0.5f);
    }

    /**
     * Brightens a dim photo: the paper (the brightest 1%) becomes white and the darkest 0.2% black.
     * A document that already has white paper (brightest 1% at 225 or more) is returned unchanged,
     * so scans and screenshots are not touched. Always returns a new array.
     */
    public static int[] stretch(int[] lum) {
        int[] out = lum.clone();
        if (lum.length == 0) return out;
        int[] hist = new int[256];
        for (int v : lum) hist[Math.max(0, Math.min(255, v))]++;

        int cutHi = Math.max(1, lum.length / 100);
        int hi = 255, acc = 0;
        while (hi > 0 && (acc += hist[hi]) < cutHi) hi--;
        if (hi >= 225) return out; // paper is already white

        int cutLo = Math.max(1, lum.length / 500);
        int lo = 0;
        acc = 0;
        while (lo < 255 && (acc += hist[lo]) < cutLo) lo++;
        if (hi - lo < 40) return out; // nearly flat image: nothing sensible to stretch

        float scale = 255f / (hi - lo);
        for (int i = 0; i < lum.length; i++) {
            int v = Math.round((lum[i] - lo) * scale);
            out[i] = v < 0 ? 0 : (v > 255 ? 255 : v);
        }
        return out;
    }
}
