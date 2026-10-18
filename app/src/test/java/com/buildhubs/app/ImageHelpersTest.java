package com.buildhubs.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pixel helpers (luminance arrays), on small synthetic images. */
public class ImageHelpersTest {

    @Test
    public void luminanceOfWhiteAndBlack() {
        assertEquals(255, ImagePrep.luminance(0xFFFFFFFF));
        assertEquals(0, ImagePrep.luminance(0xFF000000));
    }

    @Test
    public void dimPhotoIsBrightened() {
        int[] lum = new int[10000];
        for (int i = 0; i < lum.length; i++) lum[i] = i % 10 == 0 ? 40 : 170; // dark text on grey paper
        int[] out = ImagePrep.stretch(lum);
        int max = 0;
        for (int v : out) max = Math.max(max, v);
        assertTrue("paper should become (nearly) white", max >= 250);
        assertEquals(lum.length, out.length);
    }

    @Test
    public void whitePaperIsLeftUntouched() {
        int[] lum = new int[10000];
        for (int i = 0; i < lum.length; i++) lum[i] = i % 10 == 0 ? 10 : 252;
        assertArrayEquals(lum, ImagePrep.stretch(lum));
    }

    @Test
    public void stretchReturnsACopy() {
        int[] lum = {200, 200, 200, 200};
        int[] out = ImagePrep.stretch(lum);
        out[0] = 5;
        assertEquals(200, lum[0]);
        assertEquals(0, ImagePrep.stretch(new int[0]).length);
    }

    private static int[] sheetOnATable(int w, int h, int table, int paper, int x0, int y0, int x1, int y1) {
        int[] lum = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                lum[y * w + x] = (x >= x0 && x < x1 && y >= y0 && y < y1) ? paper : table;
            }
        }
        return lum;
    }

    @Test
    public void paperIsFoundOnADarkTable() {
        int[] lum = sheetOnATable(200, 300, 60, 240, 30, 20, 170, 280);
        int[] box = PaperCrop.findPaper(lum, 200, 300);
        assertNotNull(box);
        assertEquals(30, box[0], 2);
        assertEquals(20, box[1], 2);
        assertEquals(170, box[2], 2);
        assertEquals(280, box[3], 2);
    }

    @Test
    public void whiteBordersNeedNoCrop() {
        int[] lum = sheetOnATable(200, 300, 250, 250, 0, 0, 200, 300);
        assertNull(PaperCrop.findPaper(lum, 200, 300));
    }

    @Test
    public void tinyOrInvalidInputIsIgnored() {
        assertNull(PaperCrop.findPaper(null, 100, 100));
        assertNull(PaperCrop.findPaper(new int[10], 10, 1));
        // a small bright patch on a dark table is not the page
        int[] lum = sheetOnATable(200, 300, 40, 240, 90, 140, 110, 160);
        assertNull(PaperCrop.findPaper(lum, 200, 300));
    }
}
