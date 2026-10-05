package com.buildhubs.app;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;

import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;
import java.util.Locale;

/**
 * Fills the Ministry of Transport power-of-attorney form (A4, 594.96 x 841.92).
 * Only these fields are written, everything else on the form is left untouched:
 *   - vehicle number (box at the top)
 *   - section 1, first row: family + first name, and ID number (one digit per box + check digit)
 *   - "approval valid until" date = today + 1 month
 * Coordinates are in top-down points measured from the form's real boxes.
 */
public final class PowerOfAttorneyFiller {

    private PowerOfAttorneyFiller() {}

    // vehicle number box
    private static final float[] VEHICLE_BOX = {248.4f, 102.0f, 359.0f, 121.4f};
    // section 1, row 1: name cell
    private static final float[] NAME_CELL = {218.5f, 453.0f, 358.2f, 474.6f};
    // section 1, row 1: ID digit boxes: 8 digit boxes + the bold check-digit box, as x edges
    private static final float[] ID_EDGES = {66.75f, 82.85f, 99.15f, 115.25f, 131.35f, 147.65f, 163.75f, 179.85f, 195.9f, 212.25f};
    private static final float ID_BOX_TOP = 453.0f, ID_BOX_BOTTOM = 466.7f;
    // validity date: digits of the existing "30/09/2026" sit on the underscore line
    private static final float DATE_X0 = 237.2f, DATE_X1 = 297.3f, DATE_TOP = 670.0f, DATE_BASELINE = 680.4f;

    /** @param familyFirstName family name then first name, as the form asks ("כהן אורן"). */
    public static void fill(PDDocument doc, String familyFirstName, String id, String vehicle,
                            InputStream fontStream) throws IOException {
        PDPage page = doc.getPage(0);
        float pageW = page.getMediaBox().getWidth();
        float pageH = page.getMediaBox().getHeight();
        float k = Math.min(pageW / 594.96f, pageH / 841.92f);
        PDType0Font font = PDType0Font.load(doc, fontStream, true);

        PDPageContentStream cs = new PDPageContentStream(doc, page,
                PDPageContentStream.AppendMode.APPEND, true, true);
        try {
            // 1. vehicle number, centred in its box
            if (notEmpty(vehicle)) {
                float[] r = scale(VEHICLE_BOX, k);
                white(cs, pageH, r[0] + 1f, r[1] + 1f, r[2] - 1f, r[3] - 1f);
                text(cs, font, vehicle.trim(), r[0], r[2], r[3], r[1], 12f * k, pageH, true, false);
            }

            // 2. name (family first), right aligned in the name cell of row 1
            if (notEmpty(familyFirstName)) {
                float[] r = scale(NAME_CELL, k);
                text(cs, font, PdfFiller.toVisual(familyFirstName.trim()), r[0] + 3f * k, r[2] - 3f * k,
                        r[3], r[1], 11f * k, pageH, false, true);
            }

            // 3. ID: one digit per box; the 9th digit goes in the check-digit box
            if (notEmpty(id)) {
                String d = id.replaceAll("\\D", "");
                if (d.length() > 9) d = d.substring(d.length() - 9);
                while (d.length() < 9) d = "0" + d;
                cs.setNonStrokingColor(0, 0, 0);
                for (int i = 0; i < 9; i++) {
                    float cx = (ID_EDGES[i] + ID_EDGES[i + 1]) / 2f * k;
                    float size = 10f * k;
                    String ch = d.substring(i, i + 1);
                    float w = font.getStringWidth(ch) / 1000f * size;
                    float base = (ID_BOX_TOP + ID_BOX_BOTTOM) / 2f * k + size * 0.36f;
                    show(cs, font, ch, cx - w / 2f, pageH - base, size);
                }
            }

            // 4. "valid until": today + 1 month, replacing only the old date digits
            String date = oneMonthAhead();
            float x0 = DATE_X0 * k, x1 = DATE_X1 * k;
            white(cs, pageH, x0 - 0.6f * k, DATE_TOP * k, x1 + 0.6f * k, (DATE_BASELINE + 0.8f) * k);
            float size = 12f * k;
            float w = font.getStringWidth(date) / 1000f * size;
            if (w > x1 - x0) { size = size * (x1 - x0) / w; w = x1 - x0; }
            show(cs, font, date, x0 + ((x1 - x0) - w) / 2f, pageH - DATE_BASELINE * k, size);
        } finally {
            cs.close();
        }
    }

    /** Today + 1 month as dd/MM/yyyy (05/10/2026 -> 05/11/2026; month ends are clamped by Calendar). */
    static String oneMonthAhead() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MONTH, 1);
        return String.format(Locale.US, "%02d/%02d/%04d",
                c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR));
    }

    private static boolean notEmpty(String s) { return s != null && !s.trim().isEmpty(); }

    private static float[] scale(float[] r, float k) {
        return new float[]{r[0] * k, r[1] * k, r[2] * k, r[3] * k};
    }

    /** white rectangle given top-down corners (x0, top, x1, bottom) */
    private static void white(PDPageContentStream cs, float pageH, float x0, float top, float x1, float bottom)
            throws IOException {
        cs.setNonStrokingColor(255, 255, 255);
        cs.addRect(x0, pageH - bottom, x1 - x0, bottom - top);
        cs.fill();
    }

    /** text vertically centred between top and bottom (top-down), centred or right aligned between x0..x1 */
    private static void text(PDPageContentStream cs, PDType0Font font, String s, float x0, float x1,
                             float bottom, float top, float size0, float pageH, boolean center, boolean right)
            throws IOException {
        float size = size0;
        float w = font.getStringWidth(s) / 1000f * size;
        while (w > (x1 - x0) && size > 4f) {
            size -= 0.25f;
            w = font.getStringWidth(s) / 1000f * size;
        }
        float x = right ? x1 - w : x0 + ((x1 - x0) - w) / 2f;
        float base = (top + bottom) / 2f + size * 0.36f;
        cs.setNonStrokingColor(0, 0, 0);
        show(cs, font, s, x, pageH - base, size);
    }

    private static void show(PDPageContentStream cs, PDType0Font font, String s, float x, float y, float size)
            throws IOException {
        cs.setNonStrokingColor(0, 0, 0);
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }
}
