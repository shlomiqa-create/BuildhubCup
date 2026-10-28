package com.buildhubs.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** OCR result parsing (words with pixel boxes) on made-up licenses placed in different ways in the image. */
public class OcrLicenseParserTest {

    private static void assertAllFields(VehicleParser.Fields f) {
        assertNotNull(f);
        assertEquals(FakeLicense.OWNER, f.owner);
        assertEquals(FakeLicense.ID, f.id);
        assertEquals(FakeLicense.PLATE, f.vehicle);
        assertEquals(FakeLicense.ADDRESS, f.address);
        assertEquals(FakeLicense.YEAR, f.year);
        assertEquals(FakeLicense.ENGINE, f.engine);
        assertEquals(FakeLicense.MAKE, f.make);
        assertEquals(FakeLicense.VIN_WITHOUT_FIRST_3, f.vin);
        assertEquals(FakeLicense.WEIGHT, f.weight);
        assertEquals(FakeLicense.MODEL, f.model);
    }

    /** A refiner that finds nothing (the first pass has to be enough). */
    private static final OcrLicenseParser.Refiner NOTHING = new OcrLicenseParser.Refiner() {
        @Override public String read(float x0, float y0, float x1, float y1, String whitelist) { return ""; }
    };

    @Test
    public void fullPageAt300Dpi() {
        List<OcrLicenseParser.Word> words = FakeLicense.words(4.17f, 0f, 0f, 0f);
        assertAllFields(OcrLicenseParser.parse(words, 2480, 3508, Float.NaN, NOTHING));
    }

    @Test
    public void pageShiftedScaledAndTilted() {
        List<OcrLicenseParser.Word> words = FakeLicense.words(3.2f, -150f, 220f, 0.03f);
        assertAllFields(OcrLicenseParser.parse(words, 2000, 3000, Float.NaN, NOTHING));
    }

    @Test
    public void tiltFromTheTextLinesIsUsed() {
        List<OcrLicenseParser.Word> words = FakeLicense.words(4.0f, 30f, 10f, -0.04f);
        assertAllFields(OcrLicenseParser.parse(words, 2500, 3600, -0.04f, NOTHING));
    }

    @Test
    public void partOfThePageInASmallImage() {
        // a phone photo of the top of the page: small image, not an A4 shape
        List<OcrLicenseParser.Word> words = FakeLicense.words(1.8f, -300f, 40f, 0f);
        VehicleParser.Fields f = OcrLicenseParser.parse(words, 923, 2000, Float.NaN, NOTHING);
        assertNotNull(f);
        assertEquals(FakeLicense.PLATE, f.vehicle);
        assertEquals(FakeLicense.ID, f.id);
        assertEquals(FakeLicense.OWNER, f.owner);
    }

    @Test
    public void estimatedScaleMatchesTheImage() {
        List<OcrLicenseParser.Word> words = FakeLicense.words(3.7f, 20f, 15f, 0f);
        float s = OcrLicenseParser.estimateScale(words, 2200, Float.NaN);
        assertEquals(3.7f, s, 0.1f);
    }

    @Test
    public void missingPlateIsReadAgainFromTheImage() {
        List<OcrLicenseParser.Word> words = FakeLicense.words(4.17f, 0f, 0f, 0f, FakeLicense.PLATE);
        final int[] calls = {0};
        OcrLicenseParser.Refiner refiner = new OcrLicenseParser.Refiner() {
            @Override public String read(float x0, float y0, float x1, float y1, String whitelist) {
                return calls[0]++ == 0 ? FakeLicense.PLATE : ""; // the first crop asked for is the plate
            }
        };
        VehicleParser.Fields f = OcrLicenseParser.parse(words, 2480, 3508, Float.NaN, refiner);
        assertNotNull(f);
        assertEquals(FakeLicense.PLATE, f.vehicle);
        assertEquals(FakeLicense.ID, f.id);
    }

    @Test
    public void notALicenseIsRejected() {
        List<OcrLicenseParser.Word> words = new java.util.ArrayList<>();
        words.add(new OcrLicenseParser.Word("שלום", 100, 100, 200, 140, 90));
        words.add(new OcrLicenseParser.Word("עולם", 300, 100, 400, 140, 90));
        words.add(new OcrLicenseParser.Word("12345", 100, 300, 200, 340, 90));
        assertNull(OcrLicenseParser.parse(words, 1000, 3000, Float.NaN, NOTHING));
        assertNull(OcrLicenseParser.parse(null, 1000, 1400, Float.NaN, NOTHING));
        assertNull(OcrLicenseParser.parse(words, 0, 0, Float.NaN, NOTHING));
    }

    @Test
    public void chassisHasNoIOrQ() {
        List<OcrLicenseParser.Word> words = FakeLicense.words(4.17f, 0f, 0f, 0f);
        // the first-pass chassis tokens are replaced by one containing the letters O and I
        for (OcrLicenseParser.Word w : words) {
            if (w.text.equals("KB20U")) w.text = "KB2OU";
            if (w.text.equals("123")) w.text = "I23";
        }
        VehicleParser.Fields f = OcrLicenseParser.parse(words, 2480, 3508, Float.NaN, NOTHING);
        assertNotNull(f);
        assertEquals("KB20U123456789", f.vin);
    }

    // ---- ID check digit ----

    @Test
    public void idCheckDigit() {
        assertTrue(OcrLicenseParser.validId("12345678-2"));
        assertTrue(OcrLicenseParser.validId("123456782"));
        assertTrue(OcrLicenseParser.validId("0000001-8")); // short old IDs are padded with zeros
        assertFalse(OcrLicenseParser.validId("12345678-3"));
        assertFalse(OcrLicenseParser.validId(null));
        assertFalse(OcrLicenseParser.validId("123"));
    }

    @Test
    public void oneMisreadDigitIsRepaired() {
        assertEquals("94580730-3", invokeRepair("94560730-3")); // 8 read as 6
        assertEquals("93036426-0", invokeRepair("93036420-0")); // 6 read as 0
    }

    @Test
    public void anAmbiguousRepairIsNotGuessed() {
        // several single-digit changes give a valid ID: no guess
        assertNull(invokeRepair("12345078-2"));
        assertNull(invokeRepair("123"));
    }

    private static String invokeRepair(String id) {
        try {
            java.lang.reflect.Method m = OcrLicenseParser.class.getDeclaredMethod("repairId", String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, id);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ---- choosing between attempts ----

    private static VehicleParser.Fields full() {
        VehicleParser.Fields f = new VehicleParser.Fields();
        f.owner = FakeLicense.OWNER;
        f.id = FakeLicense.ID;
        f.vehicle = FakeLicense.PLATE;
        f.address = FakeLicense.ADDRESS;
        f.year = FakeLicense.YEAR;
        f.engine = FakeLicense.ENGINE;
        f.make = FakeLicense.MAKE;
        f.vin = FakeLicense.VIN_WITHOUT_FIRST_3;
        return f;
    }

    @Test
    public void qualityRatesCompleteResultsHigher() {
        VehicleParser.Fields good = full();
        VehicleParser.Fields weak = full();
        weak.owner = "כהן א";          // cut-off name
        weak.make = "כינוי מסחרי";     // a label row read by mistake
        weak.id = "12345678-3";        // wrong check digit
        assertTrue(OcrLicenseParser.isGood(good));
        assertFalse(OcrLicenseParser.isGood(weak));
        assertTrue(OcrLicenseParser.quality(good) > OcrLicenseParser.quality(weak));
        assertEquals(-1, OcrLicenseParser.quality(null));
    }

    @Test
    public void mergeFillsGapsOfTheBetterResult() {
        VehicleParser.Fields best = full();
        best.engine = null;
        best.make = "מסחרי";                   // junk
        VehicleParser.Fields other = full();
        other.owner = "xx";                     // worse result
        other.id = null;
        VehicleParser.Fields m = OcrLicenseParser.merge(best, other);
        assertEquals(FakeLicense.ENGINE, m.engine);
        assertEquals(FakeLicense.MAKE, m.make);
        assertEquals(FakeLicense.OWNER, m.owner);
        assertEquals(FakeLicense.ID, m.id);
        assertEquals(FakeLicense.OWNER, OcrLicenseParser.merge(full(), null).owner);
        assertEquals(FakeLicense.OWNER, OcrLicenseParser.merge(null, full()).owner);
    }

    // ---- hOCR ----

    @Test
    public void hocrWordsAreParsed() {
        String hocr = "<span class='ocr_line' id='line_1_1' title=\"bbox 10 20 500 60; baseline 0.01 -5\">"
                + "<span class='ocrx_word' id='word_1_1' title='bbox 10 20 100 60; x_wconf 93'>שלום</span> "
                + "<span class='ocrx_word' id='word_1_2' title='bbox 120 20 300 60; x_wconf 71'><strong>12/34</strong> &amp; </span>"
                + "</span>";
        List<OcrLicenseParser.Word> words = OcrLicenseParser.parseHocr(hocr);
        assertEquals(2, words.size());
        assertEquals("שלום", words.get(0).text);
        assertEquals(10f, words.get(0).x0, 0.01f);
        assertEquals(93, words.get(0).conf);
        assertEquals("12/34 &", words.get(1).text);
        assertTrue(Float.isNaN(OcrLicenseParser.hocrTilt(hocr))); // fewer than three long lines
        assertTrue(OcrLicenseParser.parseHocr(null).isEmpty());
    }

    @Test
    public void hocrTiltIsTheMedianLineSlope() {
        StringBuilder sb = new StringBuilder();
        double[] slopes = {0.01, 0.02, 0.03, 0.5};
        for (double s : slopes) {
            sb.append("<span class='ocr_line' title=\"bbox 0 0 800 40; baseline ").append(s).append(" 0\"></span>");
        }
        sb.append("<span class='ocr_line' title=\"bbox 0 0 100 40; baseline 9 0\"></span>"); // too short: ignored
        assertEquals(0.03f, OcrLicenseParser.hocrTilt(sb.toString()), 0.0001f);
    }
}
