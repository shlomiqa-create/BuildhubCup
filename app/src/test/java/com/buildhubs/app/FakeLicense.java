package com.buildhubs.app;

import java.util.ArrayList;
import java.util.List;

/**
 * A made-up vehicle license: invented values placed where the Ministry of Transport printout has them
 * (A4 layout, points). No real person's data is used anywhere in the tests.
 */
final class FakeLicense {

    static final String OWNER = "ישראלי ישראל";          // as printed: family name first
    static final String ID = "12345678-2";               // valid check digit
    static final String PLATE = "1234567";
    static final String ADDRESS = "הרצל 1 דירה 2 חולון 5800000";
    static final String YEAR = "2015";
    static final String ENGINE = "1598";
    static final String MAKE = "טויוטה";
    static final String WEIGHT = "500";
    static final String MODEL = "KLZ1000 ABS";
    static final String CHASSIS = "JTDKB20U123456789";    // 17 characters
    static final String VIN_WITHOUT_FIRST_3 = "KB20U123456789";

    /** A value of the page: text, left edge x and baseline y in A4 points. */
    static final class Tok {
        final String text;
        final float x, y;
        Tok(String text, float x, float y) { this.text = text; this.x = x; this.y = y; }
    }

    static List<Tok> tokens() {
        List<Tok> t = new ArrayList<>();
        t.add(new Tok("01/01/2030", 174, 71));
        t.add(new Tok(PLATE, 483, 71));
        t.add(new Tok(ID, 177, 84));
        t.add(new Tok("ישראלי", 532, 83));
        t.add(new Tok("ישראל", 508, 83));
        t.add(new Tok("הרצל", 520, 94));
        t.add(new Tok("1", 500, 94));
        t.add(new Tok("דירה", 470, 94));
        t.add(new Tok("2", 455, 94));
        t.add(new Tok("חולון", 420, 94));
        t.add(new Tok("5800000", 340, 94));
        t.add(new Tok("02/02/22", 192, 107));
        t.add(new Tok("03/03/26", 332, 107));
        t.add(new Tok("04/04/15", 472, 107));
        t.add(new Tok(YEAR, 445, 132));
        t.add(new Tok("JTD", 151, 132));
        t.add(new Tok("KB20U", 176, 132));
        t.add(new Tok("123", 216, 132));
        t.add(new Tok("456789", 243, 132));
        t.add(new Tok(ENGINE, 397, 156));
        t.add(new Tok(MAKE, 482, 337));
        t.add(new Tok(WEIGHT, 505, 193));
        t.add(new Tok("KLZ1000", 290, 337));
        t.add(new Tok("ABS", 340, 337));
        return t;
    }

    /** The tokens as LicenseParser items (points). */
    static List<LicenseParser.Item> items() {
        List<LicenseParser.Item> out = new ArrayList<>();
        for (Tok t : tokens()) out.add(new LicenseParser.Item(t.text, t.x, t.y));
        return out;
    }

    /**
     * The tokens as OCR words of an image: X = s*x + tx, Y = s*y + ty + k*X (the model the parser fits).
     * Printed label words and OCR noise are added to make it realistic. Tokens whose text is in 'drop' are left out.
     */
    static List<OcrLicenseParser.Word> words(float s, float tx, float ty, float k, String... drop) {
        List<OcrLicenseParser.Word> out = new ArrayList<>();
        for (Tok t : tokens()) {
            boolean skip = false;
            for (String d : drop) if (d.equals(t.text)) skip = true;
            if (!skip) out.add(word(t.text, t.x, t.y, s, tx, ty, k));
        }
        // printed labels sit right of the values; garbage letters appear here and there
        out.add(word("בעלים", 566, 83, s, tx, ty, k));
        out.add(word("מען", 568, 94, s, tx, ty, k));
        out.add(word("yo", 300, 94, s, tx, ty, k));
        return out;
    }

    private static OcrLicenseParser.Word word(String text, float x, float y, float s, float tx, float ty, float k) {
        float X = s * x + tx;
        float Y = s * y + ty + k * X;
        float h = 10f * s;                       // text height in pixels
        float y1 = Y + 0.15f * h;                // the parser estimates the baseline as y1 - 0.15 * h
        float width = Math.max(1, text.length()) * 0.6f * h;
        return new OcrLicenseParser.Word(text, X, y1 - h, X + width, y1, 90);
    }
}
