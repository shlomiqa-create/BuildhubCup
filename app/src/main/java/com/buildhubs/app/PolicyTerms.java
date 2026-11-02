package com.buildhubs.app;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Optional changes of the period and the premium printed on the regular insurance form (2.pdf, A4):
 * the "not before" date, the expiry date, the number of insurance days (calculated from the two dates), the date of
 * issue and the date in the bank stamp (both the same as "not before"), the premium ("דמי ביטוח"), the amount in the
 * stamp and the amount and date in the sentence "העתק לתעודה מקורית ע"ס ... ששולמה ב...".
 *
 * The old values are NOT at fixed places: their length and order change from one form to the next ("1/01/2026" or
 * "01/11/2026", the time left or right of the date, "365:" with its colon...). So each value is found in the text layer
 * of the form itself, by the row it is on and by its shape; exactly the glyphs of the old value are covered by a white
 * box, and the new value is written at the same baseline, with the same size, anchored at the edge that touches the
 * neighbouring text (shrinking when it would run into the other text). Whatever is not found is reported and left alone.
 *
 * The form is typed in Arial; the replacement font (Arimo, metric-compatible with Arial, digits and separators only)
 * has the same widths.
 */
public final class PolicyTerms {

    private PolicyTerms() {}

    // ---- rows of the form: baseline (top-down points, A4) and the horizontal band to search ----
    private static final float STAMP_DATE_ROW = 278.4f;
    private static final float STAMP_AMOUNT_ROW = 246.2f;
    private static final float PERIOD_ROW = 347.8f;
    private static final float PAID_ROW = 514.2f;
    private static final float BOTTOM_ROW = 544.0f;
    private static final float PREMIUM_ROW = 544.3f;
    private static final float TOLERANCE = 3.0f;

    private static final Pattern DATE = Pattern.compile("\\d{1,2}/\\d{1,2}/(\\d{4}|\\d{2})");
    private static final Pattern TIME = Pattern.compile("\\d{1,2}:\\d{2}(:\\d{2})?");
    private static final Pattern AMOUNT_COMMA = Pattern.compile("\\d{1,3}(,\\d{3})*\\.\\d{2}");
    private static final Pattern AMOUNT_PLAIN = Pattern.compile("\\d+\\.\\d{2}");
    private static final Pattern NUMBER = Pattern.compile("\\d{1,5}");

    /** One glyph of the page text with its place (top-down points). */
    private static final class Glyph {
        final String ch;
        final float x0, x1, base, size;
        final boolean bold;

        Glyph(String ch, float x0, float x1, float base, float size, boolean bold) {
            this.ch = ch;
            this.x0 = x0;
            this.x1 = x1;
            this.base = base;
            this.size = size;
            this.bold = bold;
        }

        boolean isSpace() { return ch.trim().isEmpty(); }
    }

    /** A run of glyphs that make one value, left to right. */
    private static final class Token {
        final String text;
        final float x0, x1, base, size;
        final boolean bold;

        Token(String text, float x0, float x1, float base, float size, boolean bold) {
            this.text = text;
            this.x0 = x0;
            this.x1 = x1;
            this.base = base;
            this.size = size;
            this.bold = bold;
        }
    }

    /** Collects every glyph of the page with its position. */
    private static final class Collector extends PDFTextStripper {
        final List<Glyph> glyphs = new ArrayList<>();

        Collector() throws IOException {
            super();
        }

        @Override
        protected void processTextPosition(TextPosition t) {
            String unicode = t.getUnicode();
            if (unicode == null || unicode.isEmpty()) return;
            String font = t.getFont() == null || t.getFont().getName() == null ? "" : t.getFont().getName();
            glyphs.add(new Glyph(unicode, t.getXDirAdj(), t.getXDirAdj() + t.getWidthDirAdj(), t.getYDirAdj(),
                    t.getFontSizeInPt(), font.toLowerCase(Locale.ROOT).contains("bold")));
            super.processTextPosition(t);
        }
    }

    /**
     * @param start   "not before" date (dd/mm/yyyy); empty = unchanged
     * @param expiry  expiry date (dd/mm/yyyy); empty = unchanged
     * @param premium amount in shekels (for example 5384 or 5,384.50); empty = unchanged
     * @param regular Arial-like font with digits and separators
     * @param bold    the same, bold
     * @return what could not be found on the form (empty when everything was written)
     */
    public static List<String> apply(PDDocument doc, String start, String expiry, String premium,
                                     PDType0Font regular, PDType0Font bold) throws IOException {
        List<String> missing = new ArrayList<>();
        String startDate = InsuranceDates.normalize(start);
        String expiryDate = InsuranceDates.normalize(expiry);
        String amount = InsuranceDates.formatAmount(premium);
        int days = InsuranceDates.insuranceDays(start, expiry);
        if (startDate == null && expiryDate == null && amount == null) return missing;

        PDPage page = doc.getPage(0);
        float pageW = page.getMediaBox().getWidth();
        float pageH = page.getMediaBox().getHeight();
        float k = Math.min(pageW / 595.276f, pageH / 841.89f);

        Collector collector = new Collector();
        collector.setStartPage(1);
        collector.setEndPage(1);
        collector.getText(doc);
        List<Glyph> glyphs = collector.glyphs;

        PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true);
        try {
            Writer w = new Writer(cs, regular, bold, pageH, glyphs);

            if (startDate != null || expiryDate != null) {
                List<Token> dates = find(glyphs, PERIOD_ROW * k, 40f * k, 300f * k, DATE, k);
                // RTL line: "...אך לא לפני: <start>  מועד פקיעת הביטוח: <expiry> בחצות": the start is the right one
                Token startToken = null, expiryToken = null;
                if (dates.size() >= 2) {
                    startToken = dates.get(dates.size() - 1);
                    expiryToken = dates.get(dates.size() - 2);
                } else if (dates.size() == 1) {
                    if (dates.get(0).x1 > 190f * k) startToken = dates.get(0); else expiryToken = dates.get(0);
                }
                if (startDate != null) {
                    if (startToken == null) missing.add("אך לא לפני"); else w.replace(startToken, startDate);
                }
                if (expiryDate != null) {
                    if (expiryToken == null) missing.add("מועד פקיעת הביטוח"); else w.replace(expiryToken, expiryDate);
                }
            }

            if (startDate != null) {
                // bank stamp: "<time> <date>", the date is right aligned, the time stays but follows it
                List<Token> stamp = find(glyphs, STAMP_DATE_ROW * k, 20f * k, 160f * k, null, k);
                Token stampDate = pick(stamp, DATE), stampTime = pick(stamp, TIME);
                if (stampDate == null) {
                    missing.add("התאריך בחותמת הבנק");
                } else {
                    w.replaceWithTime(stampDate, stampTime, startDate);
                }
                // date of issue: the time next to it is not touched
                List<Token> issue = find(glyphs, BOTTOM_ROW * k, 290f * k, 430f * k, null, k);
                Token issueDate = pick(issue, DATE);
                if (issueDate == null) {
                    missing.add("יום ושעת הוצאה");
                } else {
                    String text = issueDate.text.matches(".*/\\d{4}$") ? startDate : PolicyTerms.shortYear(startDate);
                    w.replace(issueDate, text);
                }
                // "...ששולמה ב-<date>"
                Token paidDate = pick(find(glyphs, PAID_ROW * k, 150f * k, 450f * k, null, k), DATE);
                if (paidDate != null) w.replace(paidDate, startDate);
            }

            if (days > 0) {
                List<Token> row = find(glyphs, BOTTOM_ROW * k, 200f * k, 262f * k, null, k);
                Token number = pick(row, NUMBER);
                if (number == null) missing.add("מספר ימי ביטוח"); else w.replace(number, String.valueOf(days));
            }

            if (amount != null) {
                Token big = pick(find(glyphs, PREMIUM_ROW * k, 440f * k, 530f * k, null, k), AMOUNT_COMMA);
                if (big == null) missing.add("דמי ביטוח"); else w.replace(big, amount);
                Token stampAmount = pick(find(glyphs, STAMP_AMOUNT_ROW * k, 20f * k, 160f * k, null, k), AMOUNT_COMMA);
                if (stampAmount == null) missing.add("הסכום בחותמת הבנק"); else w.replace(stampAmount, amount);
                Token paidAmount = pick(find(glyphs, PAID_ROW * k, 150f * k, 450f * k, null, k), AMOUNT_PLAIN);
                if (paidAmount != null) w.replace(paidAmount, amount.replace(",", ""));
            }
        } finally {
            cs.close();
        }
        return missing;
    }

    /** dd/mm/yyyy -> dd/mm/yy (the date of issue is sometimes printed with a two-digit year). */
    static String shortYear(String date) {
        return date.substring(0, 6) + date.substring(8);
    }

    // ---- finding the old values ----

    private static boolean isTokenChar(String ch) {
        return ch.length() == 1 && "0123456789/:.,".indexOf(ch.charAt(0)) >= 0;
    }

    /**
     * The values on one row: runs of digits and separators (left to right) whose baseline is within the tolerance of
     * 'base'. A colon that closes a number ("365:") is the label's colon, not part of the number.
     * @param shape keep only the runs matching this pattern (null = all)
     */
    private static List<Token> find(List<Glyph> glyphs, float base, float xMin, float xMax, Pattern shape, float k) {
        List<Glyph> row = new ArrayList<>();
        for (Glyph g : glyphs) {
            if (Math.abs(g.base - base) <= TOLERANCE * k && g.x1 >= xMin && g.x0 <= xMax && !g.isSpace()) row.add(g);
        }
        Collections.sort(row, new Comparator<Glyph>() {
            @Override public int compare(Glyph a, Glyph b) { return Float.compare(a.x0, b.x0); }
        });
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < row.size()) {
            if (!isTokenChar(row.get(i).ch)) { i++; continue; }
            int j = i;
            StringBuilder text = new StringBuilder(row.get(i).ch);
            while (j + 1 < row.size() && isTokenChar(row.get(j + 1).ch) && row.get(j + 1).x0 - row.get(j).x1 < 1.3f * k) {
                j++;
                text.append(row.get(j).ch);
            }
            int last = j;
            String s = text.toString();
            // "365:" - the colon belongs to the label
            if (s.length() > 1 && s.endsWith(":") && s.substring(0, s.length() - 1).matches("[0-9,./]+")) {
                s = s.substring(0, s.length() - 1);
                last = j - 1;
            }
            // the value's own start/end: leading or trailing punctuation is not part of it
            Glyph a = row.get(i), b = row.get(last);
            if (shape == null || shape.matcher(s).matches()) {
                out.add(new Token(s, a.x0, b.x1, a.base, a.size, a.bold));
            }
            i = j + 1;
        }
        return out;
    }

    private static Token pick(List<Token> tokens, Pattern shape) {
        for (Token t : tokens) if (shape.matcher(t.text).matches()) return t;
        return null;
    }

    // ---- writing ----

    private static final class Writer {
        final PDPageContentStream cs;
        final PDType0Font regular, bold;
        final float pageH;
        final List<Glyph> glyphs;

        Writer(PDPageContentStream cs, PDType0Font regular, PDType0Font bold, float pageH, List<Glyph> glyphs) {
            this.cs = cs;
            this.regular = regular;
            this.bold = bold;
            this.pageH = pageH;
            this.glyphs = glyphs;
        }

        /** The nearest glyph (not a space) on the left of x, on the same line; its right edge, or -1. */
        private float leftNeighbour(Token t, float x0, float x1) {
            float best = -1f;
            for (Glyph g : glyphs) {
                if (g.isSpace() || Math.abs(g.base - t.base) > 4f || g.x1 > t.x0 + 0.5f) continue;
                if (g.x1 > x0 - 40f && g.x1 > best && !(g.x0 >= x0 - 0.1f && g.x1 <= x1 + 0.1f)) best = g.x1;
            }
            return best;
        }

        private void cover(float x0, float x1, float base, float size, boolean withDescender) throws IOException {
            float top = base - 0.78f * size;
            float bottom = base + (withDescender ? 0.20f : 0.12f) * size;
            cs.setNonStrokingColor(255, 255, 255);
            cs.addRect(x0 - 0.3f, pageH - bottom, (x1 - x0) + 0.6f, bottom - top);
            cs.fill();
        }

        private void text(PDType0Font font, String s, float x, float base, float size) throws IOException {
            cs.setNonStrokingColor(0, 0, 0);
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(x, pageH - base);
            cs.showText(s);
            cs.endText();
        }

        private float width(PDType0Font font, String s, float size) throws IOException {
            return font.getStringWidth(s) / 1000f * size;
        }

        /**
         * Replaces one value. The lines of the form are right to left, so the value keeps its RIGHT edge (the side of
         * its label) and grows to the left; it shrinks when it would run into the text on its left.
         */
        void replace(Token t, String value) throws IOException {
            PDType0Font font = t.bold ? bold : regular;
            float size = t.size;
            float left = leftNeighbour(t, t.x0, t.x1);
            float limit = left < 0 ? Float.MAX_VALUE : t.x1 - left - 1.5f;
            float w = width(font, value, size);
            if (w > limit && limit > 0f) {
                size = Math.max(size * 0.7f, size * limit / w);
                w = width(font, value, size);
            }
            cover(t.x0, t.x1, t.base, t.size, value.indexOf(',') >= 0 || value.indexOf('.') >= 0);
            text(font, value, t.x1 - w, t.base, size);
        }

        /**
         * The bank stamp line "<time> <date>": the date keeps its right edge, the time is written again to the left
         * of it (same gap as before) because the new date can be wider than the old one.
         */
        void replaceWithTime(Token date, Token time, String value) throws IOException {
            PDType0Font font = date.bold ? bold : regular;
            float size = date.size;
            float w = width(font, value, size);
            float dateX = date.x1 - w;
            // both old values are covered BEFORE anything is written: the new date reaches over the old time
            cover(date.x0, date.x1, date.base, date.size, false);
            boolean moveTime = time != null && time.x1 <= date.x0;
            if (moveTime) cover(time.x0, time.x1, time.base, time.size, false);
            text(font, value, dateX, date.base, size);
            if (moveTime) {
                float gap = date.x0 - time.x1;
                PDType0Font timeFont = time.bold ? bold : regular;
                float tw = width(timeFont, time.text, time.size);
                text(timeFont, time.text, dateX - gap - tw, time.base, time.size);
            }
        }
    }
}
