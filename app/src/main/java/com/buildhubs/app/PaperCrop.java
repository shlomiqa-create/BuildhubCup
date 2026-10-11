package com.buildhubs.app;

import java.util.Arrays;

/**
 * Finds the sheet of paper inside a photo / scan so that the page can be mapped onto A4.
 * Pure Java on a luminance array (0..255), usable on Android (Bitmap pixels) and on a desktop.
 */
public final class PaperCrop {

    private PaperCrop() {}

    /**
     * @return {x0, y0, x1, y1} of the paper (x1/y1 exclusive), or null when no crop is needed
     *         (the border is already white) or no clear paper area was found.
     */
    public static int[] findPaper(int[] lum, int w, int h) {
        if (lum == null || w < 50 || h < 50 || lum.length < w * h) return null;

        int border = Math.max(2, Math.min(w, h) / 100);
        int[] samples = new int[2 * border * (w + h)];
        int n = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (x < border || y < border || x >= w - border || y >= h - border) samples[n++] = lum[y * w + x];
            }
        }
        Arrays.sort(samples, 0, n);
        int borderLum = samples[n / 2];
        if (borderLum >= 235) return null; // white margins: the image already is the page

        int threshold = Math.min(250, Math.max(190, borderLum + 25));
        int[] rowCount = new int[h];
        int[] colCount = new int[w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (lum[y * w + x] >= threshold) { rowCount[y]++; colCount[x]++; }
            }
        }
        int y0 = first(rowCount, w * 4 / 10), y1 = last(rowCount, w * 4 / 10);
        int x0 = first(colCount, h * 4 / 10), x1 = last(colCount, h * 4 / 10);
        if (y0 < 0 || x0 < 0 || x1 <= x0 || y1 <= y0) return null;
        long area = (long) (x1 - x0 + 1) * (y1 - y0 + 1);
        if (area < (long) w * h * 4 / 10) return null; // too small to be the page
        return new int[]{x0, y0, x1 + 1, y1 + 1};
    }

    private static int first(int[] counts, int min) {
        for (int i = 0; i < counts.length; i++) if (counts[i] >= min) return i;
        return -1;
    }

    private static int last(int[] counts, int min) {
        for (int i = counts.length - 1; i >= 0; i--) if (counts[i] >= min) return i;
        return -1;
    }
}
