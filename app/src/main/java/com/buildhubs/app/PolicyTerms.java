package com.buildhubs.app;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;

import java.io.IOException;

/**
 * Optional changes of the period and the premium printed on the regular insurance form (2.pdf, A4):
 * the "not before" date, the expiry date, the number of insurance days (calculated from the two dates), the date of
 * issue (the same as "not before"), the date in the bank stamp, the premium ("דמי ביטוח") and the amount in the stamp.
 *
 * Positions were measured on the form (top-down points, A4); the old text is covered by a white box that stays inside
 * its own ink, so no table line or neighbouring text is touched. The form is typed in Arial 10 pt; the replacement
 * font (Arimo, metric-compatible with Arial, digits and separators only) has the same widths.
 */
public final class PolicyTerms {

    private PolicyTerms() {}

    private static final float SIZE = 10f;
    private static final float PREMIUM_SIZE = 12f;

    /** One value on the form: where the old ink is (to cover), where the text starts or ends, and its baseline. */
    private static final class Spot {
        final float[] cover;       // x0, top, x1, bottom
        final float edge;          // left edge of the text, or its right edge when rightAligned
        final float baseline;
        final boolean rightAligned;
        final float size;
        final boolean bold;
        final float maxWidth;      // shrink the text to fit this width (0 = no limit)

        Spot(float[] cover, float edge, float baseline, boolean rightAligned, float size, boolean bold, float maxWidth) {
            this.cover = cover;
            this.edge = edge;
            this.baseline = baseline;
            this.rightAligned = rightAligned;
            this.size = size;
            this.bold = bold;
            this.maxWidth = maxWidth;
        }
    }

    // bank stamp (top left): the date line and the amount line
    private static final Spot STAMP_DATE = new Spot(new float[]{100.8f, 269.6f, 152.3f, 279.6f}, 101.6f, 278.4f, false, SIZE, true, 0f);
    private static final Spot STAMP_AMOUNT = new Spot(new float[]{85.8f, 238.4f, 127.2f, 248.0f}, 125.8f, 246.2f, true, SIZE, true, 39.5f);
    // "המועד בו הוחתמה תעודה זו בחותמת הבנק, אך לא לפני:" and "מועד פקיעת הביטוח:"
    private static final Spot START = new Spot(new float[]{220.6f, 339.3f, 272.4f, 349.0f}, 221.6f, 347.8f, false, SIZE, false, 0f);
    private static final Spot EXPIRY = new Spot(new float[]{89.2f, 339.3f, 141.2f, 349.0f}, 90.2f, 347.8f, false, SIZE, false, 0f);
    // the row "מספר ימי ביטוח: 365 / יום ושעת הוצאה: 08:29 20/04/26 / דמי ביטוח: 5,384.00"
    private static final Spot DAYS = new Spot(new float[]{221.2f, 535.6f, 239.0f, 545.4f}, 238.2f, 544.0f, true, SIZE, false, 0f);
    private static final Spot ISSUE_DATE = new Spot(new float[]{349.2f, 535.6f, 389.5f, 545.4f}, 350.0f, 544.0f, false, SIZE, false, 0f);
    private static final Spot PREMIUM = new Spot(new float[]{470.4f, 534.6f, 519.0f, 546.8f}, 517.9f, 544.3f, true, PREMIUM_SIZE, true, 47.7f);

    /**
     * @param start   "not before" date (dd/mm/yyyy), also written as the date of issue and in the bank stamp; empty = unchanged
     * @param expiry  expiry date (dd/mm/yyyy); empty = unchanged
     * @param premium amount in shekels (for example 5384 or 5,384.50), also written in the stamp; empty = unchanged
     * @param regular Arial-like font with digits and separators
     * @param bold    the same, bold
     */
    public static void apply(PDDocument doc, String start, String expiry, String premium,
                             PDType0Font regular, PDType0Font bold) throws IOException {
        String startDate = InsuranceDates.normalize(start);
        String expiryDate = InsuranceDates.normalize(expiry);
        String amount = InsuranceDates.formatAmount(premium);
        int days = InsuranceDates.insuranceDays(start, expiry);
        if (startDate == null && expiryDate == null && amount == null) return;

        PDPage page = doc.getPage(0);
        float pageW = page.getMediaBox().getWidth();
        float pageH = page.getMediaBox().getHeight();
        float k = Math.min(pageW / 595.276f, pageH / 841.89f);

        PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true);
        try {
            if (startDate != null) {
                write(cs, regular, bold, pageH, k, START, startDate);
                write(cs, regular, bold, pageH, k, STAMP_DATE, startDate);
                write(cs, regular, bold, pageH, k, ISSUE_DATE, shortYear(startDate));
            }
            if (expiryDate != null) {
                write(cs, regular, bold, pageH, k, EXPIRY, expiryDate);
            }
            if (days > 0) {
                write(cs, regular, bold, pageH, k, DAYS, String.valueOf(days));
            }
            if (amount != null) {
                write(cs, regular, bold, pageH, k, PREMIUM, amount);
                write(cs, regular, bold, pageH, k, STAMP_AMOUNT, amount);
            }
        } finally {
            cs.close();
        }
    }

    /** dd/mm/yyyy -> dd/mm/yy (the date of issue on the form has a two-digit year). */
    static String shortYear(String date) {
        return date.substring(0, 6) + date.substring(8);
    }

    private static void write(PDPageContentStream cs, PDType0Font regular, PDType0Font bold, float pageH, float k,
                              Spot s, String text) throws IOException {
        PDType0Font font = s.bold ? bold : regular;
        // white box over the old value
        cs.setNonStrokingColor(255, 255, 255);
        cs.addRect(s.cover[0] * k, pageH - s.cover[3] * k, (s.cover[2] - s.cover[0]) * k, (s.cover[3] - s.cover[1]) * k);
        cs.fill();

        float size = s.size * k;
        float width = font.getStringWidth(text) / 1000f * size;
        if (s.maxWidth > 0f) {
            while (width > s.maxWidth * k && size > 6f) {
                size -= 0.25f;
                width = font.getStringWidth(text) / 1000f * size;
            }
        }
        float x = s.rightAligned ? s.edge * k - width : s.edge * k;
        cs.setNonStrokingColor(0, 0, 0);
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, pageH - s.baseline * k);
        cs.showText(text);
        cs.endText();
    }
}
