package com.buildhubs.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns OCR output (words with pixel boxes) of a Ministry of Transport vehicle license image
 * (scan, screenshot or phone photo, possibly only part of the page) into the 8 fields.
 *
 * The image is related to the A4 layout (595 x 842 pt) by a small model
 *     X = s * x + tx            (pixels from points)
 *     Y = s * y + ty + k * X    (k = tilt)
 * which is fitted from the three dates printed side by side near the top of the license
 * (ownership / print / registration dates), or, when those are not readable, from the plate
 * and ID numbers. After that the same positional logic as {@link LicenseParser} is used.
 * Weak fields (plate, ID, chassis, engine, year) are re-read from tight crops by the caller's
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
     * @param whitelist the only characters allowed (digits / Latin capitals), or null for a Hebrew word
     */
    public interface Refiner {
        String read(float x0, float y0, float x1, float y1, String whitelist);
    }

    private static final float PAGE_W = 595f;
    /** Values never start right of this x (pt); the printed label column is further right. */
    private static final float LABEL_COLUMN_X = 556f;
    /** In the maker box the printed label "תוצר" sits right of this x (pt). */
    private static final float MAKER_MAX_X = 535f;
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList("מען", "דגם", "תוצר", "בעלים"));

    /** Test aid: how the last image was calibrated. */
    static String lastDebug = "";
    static String slotDebug = "";

    /** Second pass over whole Hebrew text lines. Off: it fixed some cases but added junk words in others. */
    static boolean HEBREW_REFINE = false;

    private static final String DIGITS = "0123456789";
    /** Characters allowed in a chassis number (no I, O, Q). Refiners read this crop as a line (PSM 7), all others as one word (PSM 8). */
    public static final String CHASSIS_CHARS = "ABCDEFGHJKLMNPRSTUVWXYZ0123456789";

    // The three dates of the second text row: ownership (x 192), print (x 332), registration (x 472); baseline 107
    private static final float[] DATE_X = {192f, 332f, 472f};
    private static final float DATE_BASELINE = 107f;
    private static final Pattern DATE = Pattern.compile("\\d{2}/\\d{2}/(\\d{2}|\\d{4})");

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

    /** How the A4 layout sits in the image: X = s*x + tx, Y = s*y + ty + k*X. */
    private static final class Frame {
        float s;   // pixels per point
        float tx, ty, k;

        float px(float x) { return s * x + tx; }
        float py(float x, float y) { return s * y + ty + k * px(x); }
        float ptX(float X) { return (X - tx) / s; }
        float ptY(float X, float Y) { return (Y - ty - k * X) / s; }
    }

    /** An OCR token in image pixels (X = left edge, Y = approximate baseline). */
    private static final class Tok {
        String text;
        float X, Y;
        float x0, y0, x1, y1; // word box (pixels)
        int conf;             // OCR confidence 0..100
    }

    /**
     * Estimated pixels per A4 point (about 4.17 at 300 dpi), or NaN when the layout cannot be located.
     * Lets the caller resize the image so that the text has a good size for OCR, then read it again.
     */
    public static float estimateScale(List<Word> words, int imgW, float hocrTilt) {
        if (words == null || words.isEmpty() || imgW <= 0) return Float.NaN;
        List<Tok> toks = new ArrayList<>();
        for (Word w : words) {
            String t = clean(w.text);
            if (t.isEmpty()) continue;
            Tok k = new Tok();
            k.text = t;
            k.X = w.x0;
            k.Y = w.y1 - 0.15f * (w.y1 - w.y0);
            toks.add(k);
        }
        Frame fr = calibrateBySlots(toks, imgW, hocrTilt);
        return fr == null ? Float.NaN : fr.s;
    }

    /**
     * @param words     OCR words of the image
     * @param imgW      image width in pixels
     * @param imgH      image height in pixels
     * @param hocrTilt  result of {@link #hocrTilt(String)} (NaN if unknown)
     * @param refiner   optional second pass for weak fields (may be null)
     * @return the fields, or null when the image does not look like a license page
     */
    public static VehicleParser.Fields parse(List<Word> words, int imgW, int imgH, float hocrTilt, Refiner refiner) {
        if (words == null || words.isEmpty() || imgW <= 0 || imgH <= 0) return null;

        // 1. tokens in image pixels
        List<Tok> toks = new ArrayList<>();
        for (Word w : words) {
            String t = clean(w.text);
            if (t.isEmpty() || isLabelWord(t)) continue;
            Tok k = new Tok();
            k.text = t;
            k.X = w.x0;
            k.Y = w.y1 - 0.15f * (w.y1 - w.y0);
            k.x0 = w.x0; k.y0 = w.y0; k.x1 = w.x1; k.y1 = w.y1;
            k.conf = w.conf;
            toks.add(k);
        }

        // 2. where is the A4 layout in this image?
        Frame fr = calibrateBySlots(toks, imgW, hocrTilt);
        String how = "slots";
        if (fr == null) { fr = calibrateByAnchors(toks, imgW, imgH, hocrTilt, refiner != null); how = "anchors"; }
        if (fr == null) { lastDebug = "no calibration"; return null; }
        lastDebug = how + slotDebug + " s=" + fr.s + " tx=" + fr.tx + " ty=" + fr.ty + " k=" + fr.k;

        // 3. tokens in A4 points
        List<LicenseParser.Item> page = new ArrayList<>();
        for (Tok t : toks) {
            float x = fr.ptX(t.X);
            if (x > LABEL_COLUMN_X) continue; // printed label column
            String text = t.text;
            // a short, unsure Hebrew word next to the label column is a garbled label ("פע" for "מען")
            if (HEB.matcher(text).find() && text.length() <= 2 && t.conf < 60 && x > 500f) continue;
            // A Hebrew word in the right half that OCR read as Latin letters ("Moy"): read that box again as Hebrew.
            if (refiner != null && text.matches("[A-Za-z]{3,}") && x >= 380f) {
                String heb = hebrewLine(refiner.read(t.x0 - 4f, t.y0 - 4f, t.x1 + 4f, t.y1 + 4f, null));
                if (heb.length() < 3 || heb.indexOf(' ') >= 0 || !HEB.matcher(heb).find()) continue;
                text = heb;
            }
            // stray OCR letters such as "yo", "b" (digits of any length are kept: house numbers)
            if (!HEB.matcher(text).find() && text.length() <= 2 && !text.matches("\\d{1,2}")) continue;
            page.add(new LicenseParser.Item(text, x, fr.ptY(t.X, t.Y)));
        }

        // 4. the plate number is the anchor of LicenseParser: read it again from a tight crop when missing
        LicenseParser.Item plate = findPlate(page);
        if (plate == null) {
            if (refiner == null) return null;
            String digits = read(refiner, fr, 470f, 61f, 545f, 76f, DIGITS);
            Matcher m = Pattern.compile("\\d{5,8}").matcher(digits);
            if (!m.find()) return null;
            page.add(new LicenseParser.Item(m.group(), 484f, 71f));
        }

        VehicleParser.Fields f = LicenseParser.extract(page, MAKER_MAX_X);
        if (f == null) return null;
        lastDebug += " | first-pass vin=" + f.vin + " id=" + f.id + " vehicle=" + f.vehicle;
        if (refiner != null) refine(f, fr, refiner);
        // a chassis number never contains I, O or Q: OCR confuses them with 1 and 0
        if (f.vin != null) f.vin = f.vin.replace('O', '0').replace('Q', '0').replace('I', '1');
        return f;
    }

    // ---- calibration ----

    // ---- calibration from recognisable tokens (RANSAC over token / slot pairs) ----

    private static final int T_DATE2 = 0, T_DATE4 = 1, T_PLATE = 2, T_ID = 3, T_YEAR = 4, T_ENGINE = 5;

    /** A printed value of the license with its place on the A4 layout (x = left edge, baseline y, points). */
    private static final class Slot {
        final float x, y;
        final int type;
        Slot(float x, float y, int type) { this.x = x; this.y = y; this.type = type; }
    }

    private static final Slot[] SLOTS = {
            new Slot(174f, 71f, T_DATE4),   // valid until
            new Slot(483f, 71f, T_PLATE),   // vehicle number
            new Slot(177f, 84f, T_ID),      // ID number
            new Slot(192f, 107f, T_DATE2),  // ownership date
            new Slot(332f, 107f, T_DATE2),  // print date
            new Slot(472f, 107f, T_DATE2),  // registration date
    };
    // Year / engine sit in other places in other license versions: only a second, weaker tier of slots.
    private static final Slot[] EXTRA_SLOTS = {
            new Slot(445f, 132f, T_YEAR),   // year (or month/year of first registration)
            new Slot(397f, 156f, T_ENGINE), // engine volume
    };

    private static boolean fits(int type, String t) {
        switch (type) {
            case T_DATE2: return t.matches("\\d{2}/\\d{2}/\\d{2}");
            case T_DATE4: return t.matches("\\d{2}/\\d{2}/(19|20)\\d{2}");
            case T_PLATE: return t.matches("\\d{5,8}");
            case T_ID: return t.matches("\\d{7,9}-?\\d");
            case T_YEAR: return t.matches("(19|20)\\d{2}") || t.matches("\\d{2}/(19|20)\\d{2}");
            default: return t.matches("\\d{3,5}");
        }
    }

    /**
     * Finds scale / shift / tilt from the tokens whose kind is recognisable (dates, plate, ID, year, engine).
     * Every pair of tokens that could be two of those values proposes a frame; the frame explaining the
     * most values wins, then it is refined from all values it explains.
     */
    private static Frame calibrateBySlots(List<Tok> toks, int imgW, float hocrTilt) {
        Frame fr = calibrateBySlots(toks, imgW, hocrTilt, SLOTS);
        if (fr != null) return fr;
        // header values not readable (partial photo): also use the weaker year / engine slots
        Slot[] all = new Slot[SLOTS.length + EXTRA_SLOTS.length];
        System.arraycopy(SLOTS, 0, all, 0, SLOTS.length);
        System.arraycopy(EXTRA_SLOTS, 0, all, SLOTS.length, EXTRA_SLOTS.length);
        return calibrateBySlots(toks, imgW, hocrTilt, all);
    }

    private static Frame calibrateBySlots(List<Tok> toks, int imgW, float hocrTilt, Slot[] slots) {
        float k0 = !Float.isNaN(hocrTilt) && Math.abs(hocrTilt) <= 0.08f ? hocrTilt : 0f;
        float sMin = imgW / 800f, sMax = imgW / 150f;

        // candidate tokens: the ones that fit at least one slot type
        List<Tok> cand = new ArrayList<>();
        for (Tok t : toks) {
            for (Slot sl : slots) {
                if (fits(sl.type, t.text)) { cand.add(t); break; }
            }
        }
        if (cand.size() < 3) return null;

        float bestScore = 0, bestResidual = Float.MAX_VALUE;
        Frame best = null;
        for (int a = 0; a < cand.size(); a++) {
            Tok A = cand.get(a);
            for (int sa = 0; sa < slots.length; sa++) {
                if (!fits(slots[sa].type, A.text)) continue;
                for (int b = 0; b < cand.size(); b++) {
                    if (b == a) continue;
                    Tok B = cand.get(b);
                    for (int sb = 0; sb < slots.length; sb++) {
                        if (sb == sa || !fits(slots[sb].type, B.text)) continue;
                        float dxe = slots[sa].x - slots[sb].x;
                        if (Math.abs(dxe) < 100f) continue; // too close to give a stable scale
                        float s = (A.X - B.X) / dxe;
                        if (s < sMin || s > sMax) continue;
                        Frame fr = new Frame();
                        fr.s = s;
                        fr.k = k0;
                        fr.tx = A.X - s * slots[sa].x;
                        fr.ty = A.Y - s * slots[sa].y - k0 * A.X;
                        float[] sc = score(fr, cand, null, slots);
                        if (sc[0] > bestScore || (sc[0] == bestScore && sc[1] < bestResidual)) {
                            bestScore = sc[0];
                            bestResidual = sc[1];
                            best = fr;
                        }
                    }
                }
            }
        }
        if (best == null || bestScore < 3) return null;

        // refine from every value the best frame explains
        List<float[]> pairs = new ArrayList<>(); // {xe, ye, X, Y}
        score(best, cand, pairs, slots);
        Frame refined = refit(best, pairs);
        StringBuilder dbg = new StringBuilder(" matched=" + (int) bestScore + ":");
        for (float[] p : pairs) {
            float ex = refined.px(p[0]), ey = refined.py(p[0], p[1]);
            dbg.append(String.format(" (%.0f,%.0f)->dx=%.1f dy=%.1f", p[0], p[1], (p[2] - ex) / refined.s, (p[3] - ey) / refined.s));
        }
        slotDebug = dbg.toString();
        return refined;
    }

    /** @return {matched slots, summed residual in points}; fills 'pairs' with {xe, ye, X, Y} when given */
    private static float[] score(Frame fr, List<Tok> cand, List<float[]> pairs, Slot[] slots) {
        boolean[] used = new boolean[cand.size()];
        float matched = 0, residual = 0;
        for (Slot sl : slots) {
            float eX = fr.px(sl.x), eY = fr.py(sl.x, sl.y);
            int bestIdx = -1;
            float bestD = Float.MAX_VALUE;
            for (int i = 0; i < cand.size(); i++) {
                if (used[i] || !fits(sl.type, cand.get(i).text)) continue;
                float dx = (cand.get(i).X - eX) / fr.s, dy = (cand.get(i).Y - eY) / fr.s;
                if (Math.abs(dx) > 10f || Math.abs(dy) > 8f) continue;
                float d = dx * dx + dy * dy;
                if (d < bestD) { bestD = d; bestIdx = i; }
            }
            if (bestIdx >= 0) {
                used[bestIdx] = true;
                matched++;
                residual += (float) Math.sqrt(bestD);
                if (pairs != null) pairs.add(new float[]{sl.x, sl.y, cand.get(bestIdx).X, cand.get(bestIdx).Y});
            }
        }
        return new float[]{matched, residual};
    }

    /** Least-squares refinement of s / tx and of tilt / ty from the matched values. */
    private static Frame refit(Frame start, List<float[]> pairs) {
        int n = pairs.size();
        float mx = 0, mX = 0;
        for (float[] p : pairs) { mx += p[0]; mX += p[2]; }
        mx /= n;
        mX /= n;
        float num = 0, den = 0;
        for (float[] p : pairs) { num += (p[0] - mx) * (p[2] - mX); den += (p[0] - mx) * (p[0] - mx); }
        Frame fr = new Frame();
        fr.s = den > 40f * 40f ? num / den : start.s; // enough horizontal spread to trust the fit
        fr.tx = mX - fr.s * mx;

        // tilt: slope of the vertical residuals against X when the matches spread widely
        float k = start.k;
        float mXs = 0;
        for (float[] p : pairs) mXs += p[2];
        mXs /= n;
        float[] r = new float[n];
        float mr = 0;
        for (int i = 0; i < n; i++) {
            float[] p = pairs.get(i);
            r[i] = p[3] - fr.s * p[1] - start.k * p[2];
            mr += r[i];
        }
        mr /= n;
        float n2 = 0, d2 = 0;
        for (int i = 0; i < n; i++) {
            float dX = pairs.get(i)[2] - mXs;
            n2 += dX * (r[i] - mr);
            d2 += dX * dX;
        }
        if (n >= 4 && d2 > (60f * fr.s) * (60f * fr.s)) {
            float extra = n2 / d2;
            if (Math.abs(start.k + extra) <= 0.08f) k = start.k + extra;
        }
        fr.k = k;
        float sum = 0;
        for (float[] p : pairs) sum += p[3] - fr.s * p[1] - k * p[2];
        fr.ty = sum / n;
        return fr;
    }

    /**
     * Fallback: use the plate number (top right) and the ID number (top left) when the dates were not readable.
     * Assumes the image width is the page width (a screenshot / flat scan).
     */
    private static Frame calibrateByAnchors(List<Tok> toks, int imgW, int imgH, float hocrTilt, boolean canRefine) {
        float ratio = imgH / (float) imgW;
        if (ratio < 1.25f || ratio > 1.60f) return null; // not an A4 portrait page: nothing to assume
        float s0 = imgW / PAGE_W;

        Tok plate = null, id = null, year = null;
        for (Tok t : toks) {
            float x = t.X / s0, y = t.Y / s0;
            if (t.text.matches("\\d{5,8}") && x > 380 && y < 160 && (plate == null || t.Y < plate.Y)) plate = t;
            if (id == null && t.text.matches("\\d{7,9}-\\d") && x > 100 && x < 360 && y < 150) id = t;
            if (year == null && t.text.matches("(19|20)\\d{2}") && x > 410 && x < 510 && y > 110 && y < 170) year = t;
        }
        Tok ref = plate;
        float expX = 484f, expY = 71f;
        if (ref == null) { ref = id; expX = 177f; expY = 84f; }
        if (ref == null) { ref = year; expX = 445f; expY = 132f; }

        Frame fr = new Frame();
        fr.s = s0;
        float tilt = !Float.isNaN(hocrTilt) && Math.abs(hocrTilt) <= 0.05f ? hocrTilt : 0f;
        if (tilt == 0f && plate != null && id != null) {
            float t2 = (((id.Y - plate.Y) / s0) - 13f) / ((id.X - plate.X) / s0);
            if (Math.abs(t2) <= 0.05f) tilt = t2;
        }
        fr.k = tilt;
        if (ref != null) {
            fr.tx = ref.X - s0 * expX;
            fr.ty = ref.Y - s0 * expY - tilt * ref.X;
        } else {
            if (!canRefine) return null; // no anchor at all: assume the page itself
            fr.tx = 0f;
            fr.ty = 0f;
        }
        return fr;
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

    // ---- token helpers ----

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
        // plate number: any single read can misread a digit of the large printed number, so the value
        // that at least two reads agree on wins (the first pass counts as one read)
        List<String> plateReads = new ArrayList<>();
        if (f.vehicle != null) plateReads.add(f.vehicle);
        float[][] plateCrops = {{470f, 61f, 545f, 76f}, {466f, 60f, 550f, 78f}, {474f, 62f, 540f, 75f}};
        for (float[] c : plateCrops) {
            Matcher pm = Pattern.compile("\\d{5,8}").matcher(read(r, fr, c[0], c[1], c[2], c[3], DIGITS));
            if (pm.find()) plateReads.add(pm.group());
        }
        String plate = majority(plateReads);
        if (plate != null) f.vehicle = plate;

        // ID (8 digits + check digit)
        // The ID has a check digit: of several reads of the same cell (slightly different crops) keep a valid one.
        if (f.id == null || !validId(f.id)) {
            float[][] crops = {{168f, 73f, 255f, 89f}, {160f, 72f, 262f, 90f}, {172f, 74f, 250f, 88f}, {165f, 70f, 258f, 92f},
                    {170f, 71f, 256f, 91f}, {164f, 75f, 252f, 87f}};
            List<String> reads = new ArrayList<>();
            if (f.id != null && f.id.matches("\\d{7,9}-\\d")) reads.add(f.id);
            String valid = null;
            for (float[] c : crops) {
                Matcher m = Pattern.compile("(\\d{7,9})\\s*-?\\s*(\\d)").matcher(read(r, fr, c[0], c[1], c[2], c[3], DIGITS + "-"));
                if (!m.find()) continue;
                String id = m.group(1) + "-" + m.group(2);
                reads.add(id);
                if (valid == null && validId(id)) valid = id;
            }
            if (valid == null) { // no valid read: repair a single misread digit of the most common 9-digit read
                List<String> nine = new ArrayList<>();
                for (String id : reads) if (id.replaceAll("\\D", "").length() == 9) nine.add(id);
                String repaired = null;
                for (String c : nine) {
                    repaired = repairId(c);
                    if (repaired != null) break;
                }
                valid = repaired != null ? repaired : majority(nine.isEmpty() ? reads : nine);
            }
            if (valid != null) f.id = valid;
        }

        // chassis: the first pass keeps a clean 17-character read (14 after dropping 3); otherwise read the line again
        if (f.vin == null || f.vin.length() != 14) {
            String chassis = LicenseParser.bestChassisWindow(
                    read(r, fr, 140f, 121f, 305f, 137f, CHASSIS_CHARS).replaceAll("[^A-Z0-9]", ""));
            if (chassis.length() == 17) f.vin = chassis.substring(3);
            else if (f.vin == null && chassis.length() >= 12) f.vin = chassis.substring(3);
        }

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

    /** The value read most often; the first one when all differ. */
    private static String majority(List<String> reads) {
        String best = null;
        int bestCount = 0;
        for (String a : reads) {
            int n = 0;
            for (String b : reads) if (a.equals(b)) n++;
            if (n > bestCount) { best = a; bestCount = n; }
        }
        return best;
    }

    /** Digits that OCR commonly confuses with each other. */
    private static final String[] CONFUSED = {"689", "7", "7", "85", "9", "683", "058", "12", "03569", "048"}; // indexed by digit 0..9

    /**
     * Repairs one misread digit using the check digit: returns the only variant of 'id' with a single
     * digit replaced by a commonly confused one that is a valid ID, or null when there is none / several.
     */
    private static String repairId(String id) {
        String d = id.replaceAll("\\D", "");
        if (d.length() < 8 || d.length() > 9) return null;
        String found = null;
        for (int i = 0; i < d.length(); i++) {
            String options = CONFUSED[d.charAt(i) - '0'];
            for (int j = 0; j < options.length(); j++) {
                String v = d.substring(0, i) + options.charAt(j) + d.substring(i + 1);
                if (!validId(v)) continue;
                if (found != null && !found.equals(v)) return null; // ambiguous
                found = v;
            }
        }
        return found == null ? null : found.substring(0, found.length() - 1) + "-" + found.charAt(found.length() - 1);
    }

    /** Israeli ID check digit: digits weighted 1,2,1,2..., two-digit products reduced, the total divisible by 10. */
    static boolean validId(String id) {
        if (id == null) return false;
        String d = id.replaceAll("\\D", "");
        if (d.length() < 7 || d.length() > 9) return false;
        while (d.length() < 9) d = "0" + d;
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            int v = (d.charAt(i) - '0') * (i % 2 == 0 ? 1 : 2);
            sum += v > 9 ? v - 9 : v;
        }
        return sum % 10 == 0;
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
        float midX = (x0 + x1) / 2f;
        String t = r.read(fr.px(x0), fr.py(midX, top), fr.px(x1), fr.py(midX, bottom), whitelist);
        return t == null ? "" : t.trim();
    }
}
