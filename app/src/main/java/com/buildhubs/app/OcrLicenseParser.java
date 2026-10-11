package com.buildhubs.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns OCR output (words with pixel boxes) of a Ministry of Transport vehicle license image
 * into the 8 fields. The image is mapped onto the A4 page (595 x 842 pt), shifted / un-tilted
 * using anchors (plate number, ID number or year), and then the same positional logic as
 * {@link LicenseParser} is used. Weak fields are re-read from tight crops by the caller's
 * {@link Refiner}.
 *
 * Pure Java: no Android / PDFBox dependency, so it can be tested on a desktop.
 */
public final class OcrLicenseParser {

    private OcrLicenseParser() {}

    /** One OCR word. Coordinates are image pixels, origin top-left. */
    public static final class Word {
        public String text;
        public float x0, y0, x1, y1;
        public int conf;

        public Word(String text, float x0, float y0, float x1, float y1, int conf) {
            this.text = text; this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1; this.conf = conf;
        }
    }

    /**
     * Re-reads a small image rectangle (pixels): one word, or for {@link #CHASSIS_CHARS} a single line.
     * @param whitelist the only characters allowed (digits / Latin capitals), or null for a line of Hebrew text
     */
    public interface Refiner {
        String read(float x0, float y0, float x1, float y1, String whitelist);
    }

    private static final float PAGE_W = 595f, PAGE_H = 842f;
    /** Values never start right of this x (pt); the printed label column is further right. */
    private static final float LABEL_COLUMN_X = 556f;
    /** In the maker box the printed label "תוצר" sits right of this x (pt). */
    private static final float MAKER_MAX_X = 535f;
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList("מען", "דגם", "תוצר", "בעלים"));

    /** Second pass over whole Hebrew text lines. Off: it fixed some cases but added junk words in others. */
    static boolean HEBREW_REFINE = false;

    private static final String DIGITS = "0123456789";
    /** Characters allowed in a chassis number (no I, O, Q). Refiners read this crop as a line (PSM 7), all others as one word (PSM 8). */
    public static final String CHASSIS_CHARS = "ABCDEFGHJKLMNPRSTUVWXYZ0123456789";

    private static final Pattern HEB = Pattern.compile("[\\u0590-\\u05FF]");
    private static final Pattern WORD_SPAN = Pattern.compile(
            "<span[^>]*class=['\"]ocrx_word['\"][^>]*title=['\"]([^'\"]*)['\"][^>]*>(.*?)</span>", Pattern.DOTALL);
    private static final Pattern LINE_SPAN = Pattern.compile(
            "<span[^>]*class=['\"]ocr_line['\"][^>]*title=['\"]([^'\"]*)['\"]");
    private static final Pattern BBOX = Pattern.compile("bbox\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)");
    private static final Pattern WCONF = Pattern.compile("x_wconf\\s+(\\d+)");
    private static final Pattern BASELINE = Pattern.compile("baseline\\s+(-?[0-9.]+(?:e-?\\d+)?)\\s+(-?[0-9.]+)");

    /** Parses Tesseract hOCR output into words. */
    public static List<Word> parseHocr(String hocr) {
        List<Word> out = new ArrayList<>();
        if (hocr == null) return out;
        Matcher m = WORD_SPAN.matcher(hocr);
        while (m.find()) {
            Matcher b = BBOX.matcher(m.group(1));
            if (!b.find()) continue;
            Matcher c = WCONF.matcher(m.group(1));
            int conf = c.find() ? Integer.parseInt(c.group(1)) : 0;
            String text = unescape(m.group(2).replaceAll("<[^>]+>", "")).trim();
            if (text.isEmpty()) continue;
            out.add(new Word(text, Float.parseFloat(b.group(1)), Float.parseFloat(b.group(2)),
                    Float.parseFloat(b.group(3)), Float.parseFloat(b.group(4)), conf));
        }
        return out;
    }

    /**
     * Median slope (dy/dx in image coordinates, y down) of the long text lines in the hOCR output,
     * or NaN when it cannot be estimated.
     */
    public static float hocrTilt(String hocr) {
        if (hocr == null) return Float.NaN;
        List<Float> slopes = new ArrayList<>();
        Matcher m = LINE_SPAN.matcher(hocr);
        while (m.find()) {
            Matcher b = BBOX.matcher(m.group(1));
            Matcher bl = BASELINE.matcher(m.group(1));
            if (!b.find() || !bl.find()) continue;
            float width = Float.parseFloat(b.group(3)) - Float.parseFloat(b.group(1));
            if (width < 300f) continue;
            try {
                slopes.add(Float.parseFloat(bl.group(1)));
            } catch (NumberFormatException ignored) { }
        }
        if (slopes.size() < 3) return Float.NaN;
        Collections.sort(slopes);
        return slopes.get(slopes.size() / 2);
    }

    private static String unescape(String s) {
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'");
        Matcher m = Pattern.compile("&#(\\d+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1)))));
        m.appendTail(sb);
        return sb.toString();
    }

    /** Where the A4 page sits inside the image: scale, shift and tilt. */
    private static final class Frame {
        float sx, sy;   // pt per pixel
        float dx, dy;   // offset of the page content (pt) relative to the A4 layout
        float tilt;     // dy/dx of the text lines
        float refX;     // x (pt, raw) of the anchor the tilt is measured from
    }

    /**
     * @param words     OCR words of the (already cropped to the paper) license image
     * @param imgW      image width in pixels
     * @param imgH      image height in pixels
     * @param hocrTilt  result of {@link #hocrTilt(String)} (NaN if unknown)
     * @param refiner   optional second pass for weak fields (may be null)
     * @return the fields, or null when the image does not look like a license page
     */
    public static VehicleParser.Fields parse(List<Word> words, int imgW, int imgH, float hocrTilt, Refiner refiner) {
        if (words == null || words.isEmpty() || imgW <= 0 || imgH <= 0) return null;
        float ratio = imgH / (float) imgW;
        if (ratio < 1.25f || ratio > 1.60f) return null; // not an A4 portrait page

        Frame fr = new Frame();
        fr.sx = PAGE_W / imgW;
        fr.sy = PAGE_H / imgH;

        // 1. raw items (page coordinates, not yet shifted)
        List<LicenseParser.Item> items = new ArrayList<>();
        for (Word w : words) {
            String t = clean(w.text);
            if (t.isEmpty() || isLabelWord(t)) continue;
            // A Hebrew word in the right half of the page that OCR read as Latin letters ("Moy"): read that box again as Hebrew.
            if (refiner != null && t.matches("[A-Za-z]{3,}") && w.x0 * fr.sx >= 380f) {
                String heb = hebrewLine(refiner.read(w.x0 - 4f, w.y0 - 4f, w.x1 + 4f, w.y1 + 4f, null));
                if (heb.length() < 3 || heb.indexOf(' ') >= 0 || !HEB.matcher(heb).find()) continue;
                t = heb;
            }
            // stray OCR letters such as "yo", "b" (digits of any length are kept: house numbers)
            if (!HEB.matcher(t).find() && t.length() <= 2 && !t.matches("\\d{1,2}")) continue;
            float h = w.y1 - w.y0;
            LicenseParser.Item it = new LicenseParser.Item();
            it.str = t;
            it.x = w.x0 * fr.sx;
            it.y = (w.y1 - 0.15f * h) * fr.sy; // approximate baseline
            items.add(it);
        }

        // 2. anchor: the plate number (top right); when OCR missed it, the ID number or the year
        LicenseParser.Item plate = findPlate(items);
        LicenseParser.Item ref = plate;
        float expX = 484f, expY = 71f;
        if (ref == null) { ref = findId(items); expX = 177f; expY = 84f; }
        if (ref == null) { ref = findYear(items); expX = 445f; expY = 132f; }
        if (ref != null) {
            fr.tilt = chooseTilt(items, plate != null ? plate : ref, hocrTilt);
            fr.refX = ref.x;
            fr.dx = ref.x - expX;
            fr.dy = ref.y - expY;
        } else {
            // No anchor was read: assume the image is the page itself (a screenshot / flat scan).
            if (refiner == null) return null;
            fr.tilt = !Float.isNaN(hocrTilt) && Math.abs(hocrTilt) <= 0.05f ? hocrTilt : 0f;
            fr.refX = 484f;
            fr.dx = 0f;
            fr.dy = 0f;
        }

        // 3. move every item into the A4 layout, then drop the label column
        List<LicenseParser.Item> page = new ArrayList<>();
        for (LicenseParser.Item i : items) {
            LicenseParser.Item n = new LicenseParser.Item();
            n.str = i.str;
            n.x = i.x - fr.dx;
            n.y = i.y - fr.tilt * (i.x - fr.refX);
            if (n.x > LABEL_COLUMN_X) continue;
            page.add(n);
        }

        if (plate == null) {
            // read the plate number again from a tight crop near its expected place
            if (refiner == null) return null;
            String digits = read(refiner, fr, 470f, 61f, 545f, 76f, DIGITS);
            Matcher m = Pattern.compile("\\d{5,8}").matcher(digits);
            if (!m.find()) return null;
            page.add(new LicenseParser.Item(m.group(), 484f, 71f));
        }

        VehicleParser.Fields f = LicenseParser.extract(page, MAKER_MAX_X);
        if (f == null) return null;
        if (refiner != null) refine(f, fr, refiner);
        return f;
    }

    private static LicenseParser.Item findId(List<LicenseParser.Item> items) {
        for (LicenseParser.Item i : items) {
            if (i.str.matches("\\d{7,9}-\\d") && i.x > 100 && i.x < 360 && i.y < 150) return i;
        }
        return null;
    }

    private static LicenseParser.Item findYear(List<LicenseParser.Item> items) {
        for (LicenseParser.Item i : items) {
            if (i.str.matches("(19|20)\\d{2}") && i.x > 410 && i.x < 510 && i.y > 110 && i.y < 170) return i;
        }
        return null;
    }

    private static LicenseParser.Item findPlate(List<LicenseParser.Item> items) {
        LicenseParser.Item plate = null;
        for (LicenseParser.Item i : items) {
            if (i.str.matches("\\d{5,8}") && i.x > 380 && i.y < 160) {
                if (plate == null || i.y < plate.y) plate = i;
            }
        }
        return plate;
    }

    private static float chooseTilt(List<LicenseParser.Item> items, LicenseParser.Item plate, float hocrTilt) {
        if (!Float.isNaN(hocrTilt) && Math.abs(hocrTilt) <= 0.05f) return hocrTilt;
        // fallback: the plate number (top right) and the ID number (top left) should sit 13 pt apart vertically
        for (LicenseParser.Item i : items) {
            if (i.str.matches("\\d{7,9}-\\d") && i.x > 100 && i.x < 360 && Math.abs(i.y - plate.y) < 40 && i.x != plate.x) {
                float tilt = ((i.y - plate.y) - 13f) / (i.x - plate.x);
                return Math.abs(tilt) <= 0.05f ? tilt : 0f;
            }
        }
        return 0f;
    }

    /** A printed label word, allowing one OCR mistake ("דנם" for "דגם"). */
    private static boolean isLabelWord(String t) {
        if (!HEB.matcher(t).find()) return false;
        for (String label : STOP_WORDS) {
            if (Math.abs(label.length() - t.length()) <= 1 && editDistance(label, t) <= 1) return true;
        }
        return false;
    }

    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            prev = cur;
        }
        return prev[b.length()];
    }

    /** Hebrew tokens are kept; Latin / digit tokens lose stray OCR symbols. */
    private static String clean(String s) {
        s = s.replaceAll("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]", "").trim();
        if (HEB.matcher(s).find()) return s.replaceAll("[|\\[\\]{}<>_=\"]", "").trim();
        return s.replaceAll("[^A-Za-z0-9/.\\-]", "").trim();
    }

    // ---- second pass ----

    private static void refine(VehicleParser.Fields f, Frame fr, Refiner r) {
        // ID (8 digits + check digit)
        if (f.id == null || !f.id.matches("\\d{7,9}-\\d")) {
            Matcher m = Pattern.compile("(\\d{7,9})\\s*-\\s*(\\d)")
                    .matcher(read(r, fr, 168f, 73f, 255f, 89f, DIGITS + "-"));
            if (m.find()) f.id = m.group(1) + "-" + m.group(2);
        }

        // chassis: prefer a clean 17-character read, the first pass only drops 3 characters
        String chassis = read(r, fr, 140f, 121f, 305f, 137f, CHASSIS_CHARS).replaceAll("[^A-Z0-9]", "");
        if (chassis.length() == 17) f.vin = chassis.substring(3);
        else if (f.vin == null && chassis.length() >= 12) f.vin = chassis.substring(3);

        // engine volume
        if (f.engine == null) {
            Matcher m = Pattern.compile("\\d{3,5}").matcher(read(r, fr, 385f, 145f, 440f, 161f, DIGITS));
            if (m.find()) f.engine = m.group();
        }

        // year of manufacture
        if (f.year == null) {
            Matcher m = Pattern.compile("(19|20)\\d{2}").matcher(read(r, fr, 438f, 121f, 476f, 137f, DIGITS));
            if (m.find()) f.year = m.group();
        }

        if (!HEBREW_REFINE) return;
        // Hebrew text lines: re-read when the first pass looks wrong (Latin letters) or has fewer words
        f.owner = betterText(f.owner, dropSingleLetters(hebrewLine(read(r, fr, 380f, 75f, 556f, 91f, null))));
        f.address = betterText(f.address, hebrewLine(read(r, fr, 262f, 85f, 556f, 101f, null)));
        f.make = betterText(f.make, dropSingleLetters(hebrewLine(read(r, fr, 440f, 326f, 535f, 342f, null))));
    }

    /** Keeps Hebrew letters, digits and single spaces only. */
    private static String hebrewLine(String s) {
        return s.replaceAll("[^\\u0590-\\u05FF0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Removes one-letter tokens (OCR junk) from a Hebrew line; used for names and makers, never for addresses. */
    private static String dropSingleLetters(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.split(" +")) {
            if (w.length() < 2 && !w.matches("\\d")) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
        }
        return sb.toString();
    }

    private static int words(String s) {
        return s == null || s.trim().isEmpty() ? 0 : s.trim().split(" +").length;
    }

    /** Chooses between the first-pass text and the focused re-read. */
    private static String betterText(String first, String second) {
        if (second == null || second.isEmpty() || !HEB.matcher(second).find()) return first;
        if (isLabelWord(second)) return first;
        if (first == null || first.isEmpty()) return second;
        boolean firstHasLatin = first.matches(".*[A-Za-z].*");
        if (firstHasLatin && !second.matches(".*[A-Za-z].*")) return second;
        if (words(second) > words(first) && words(second) <= words(first) + 2) return second;
        return first;
    }

    /** Reads the A4-layout rectangle (pt) through the refiner, mapping it back into image pixels. */
    private static String read(Refiner r, Frame fr, float x0, float top, float x1, float bottom, String whitelist) {
        float mid = (x0 + x1) / 2f;
        float shift = fr.tilt * (mid + fr.dx - fr.refX); // inverse of the deskew applied to the items
        String t = r.read((x0 + fr.dx) / fr.sx, (top + fr.dy + shift) / fr.sy,
                (x1 + fr.dx) / fr.sx, (bottom + fr.dy + shift) / fr.sy, whitelist);
        return t == null ? "" : t.trim();
    }
}
