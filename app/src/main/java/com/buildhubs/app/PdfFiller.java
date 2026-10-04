package com.buildhubs.app;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode;

import java.io.IOException;
import java.io.InputStream;

/**
 * Writes the extracted fields onto the insurance form (2.pdf, A4 595.28 x 841.89).
 * Rects are in "top-down" points (x0, y0, x1, y1) measured from the top-left of the page,
 * taken from the form's actual cell borders. Converted to PDF space (origin bottom-left) in code.
 */
public final class PdfFiller {

    private PdfFiller() {}

    private static final int RIGHT = 0, CENTER = 1;

    // cell interiors on the form
    private static final float[] OWNER   = {366, 247, 567, 261};
    private static final float[] ID      = {244, 247, 351, 262};
    private static final float[] VEHICLE = {312, 221, 425, 235};
    private static final float[] ADDRESS = {269, 272, 567, 286};
    private static final float[] YEAR    = { 71, 306, 136, 320};
    private static final float[] ENGINE  = {184, 306, 255, 320};
    private static final float[] MAKE    = {255, 306, 326, 320};
    private static final float[] VIN     = {468, 306, 567, 320};
    private static final float[] BIG     = { 33, 364, 441, 388}; // inside the thick box: ת"ז <id> <owner> בלבד

    public static void fill(PDDocument doc, String owner, String id, String vehicle, String address,
                            String year, String engine, String make, String vin, InputStream fontStream) throws IOException {
        PDPage page = doc.getPage(0);
        float pageH = page.getMediaBox().getHeight();
        PDType0Font font = PDType0Font.load(doc, fontStream, true);

        PDPageContentStream cs = new PDPageContentStream(doc, page,
                PDPageContentStream.AppendMode.APPEND, true, true);
        try {
            put(cs, font, pageH, OWNER, owner, RIGHT, 10f, false);
            put(cs, font, pageH, ID, id, CENTER, 10f, false);
            put(cs, font, pageH, VEHICLE, vehicle, CENTER, 10f, false);
            put(cs, font, pageH, ADDRESS, address, RIGHT, 10f, false);
            put(cs, font, pageH, YEAR, year, CENTER, 10f, false);
            put(cs, font, pageH, ENGINE, engine, CENTER, 10f, false);
            put(cs, font, pageH, MAKE, make, CENTER, 10f, false);
            put(cs, font, pageH, VIN, vin, CENTER, 10f, false);

            if (notEmpty(owner) || notEmpty(id)) {
                StringBuilder big = new StringBuilder("ת\"ז");
                if (notEmpty(id)) big.append(' ').append(id);
                if (notEmpty(owner)) big.append(' ').append(owner);
                big.append(" בלבד");
                put(cs, font, pageH, BIG, big.toString(), CENTER, 24f, true);
            }
        } finally {
            cs.close();
        }
    }

    private static boolean notEmpty(String s) { return s != null && !s.trim().isEmpty(); }

    private static void put(PDPageContentStream cs, PDType0Font font, float pageH, float[] r,
                            String text, int align, float maxSize, boolean bold) throws IOException {
        if (!notEmpty(text)) return;
        float x0 = r[0], x1 = r[2], h = r[3] - r[1];
        // white cover, slightly inset so the table borders stay visible
        cs.setNonStrokingColor(255, 255, 255);
        cs.addRect(x0 + 0.8f, pageH - r[3] + 0.8f, (x1 - x0) - 1.6f, h - 1.6f);
        cs.fill();

        String visual = toVisual(text.trim());
        float avail = (x1 - x0) - 6f;
        float size = maxSize;
        float w = font.getStringWidth(visual) / 1000f * size;
        while (w > avail && size > 4f) {
            size -= 0.25f;
            w = font.getStringWidth(visual) / 1000f * size;
        }
        float x = (align == RIGHT) ? x1 - 3f - w : x0 + ((x1 - x0) - w) / 2f;
        float baseline = pageH - r[3] + (h - size * 0.72f) / 2f;

        cs.setNonStrokingColor(0, 0, 0);
        if (bold) {
            cs.setStrokingColor(0, 0, 0);
            cs.setLineWidth(0.6f);
            cs.setRenderingMode(RenderingMode.FILL_STROKE);
        }
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, baseline);
        cs.showText(visual);
        cs.endText();
        if (bold) cs.setRenderingMode(RenderingMode.FILL);
    }

    /**
     * Logical -> visual order for drawing right-to-left text with a left-to-right PDF writer.
     * Word order is reversed and letters of Hebrew words are reversed; numbers / Latin words
     * (ID, VIN, house numbers) keep their characters. The text is NOT otherwise altered, so
     * "שליו חכם" in the source is shown as "שליו חכם" (not flipped) and nothing is dropped.
     */
    static String toVisual(String s) {
        if (!hasHebrew(s)) return s;
        String[] w = s.split("\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = w.length - 1; i >= 0; i--) {
            String t = hasHebrew(w[i]) ? new StringBuilder(w[i]).reverse().toString() : w[i];
            if (sb.length() > 0) sb.append(' ');
            sb.append(t);
        }
        return sb.toString();
    }

    private static boolean hasHebrew(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0590 && c <= 0x05FF) return true;
        }
        return false;
    }
}
