package com.buildhubs.app;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Positional parser for the Israeli Ministry of Transport vehicle license printout (A4).
 * In that document the labels are graphics, only the values are text, so fields are found by
 * their position relative to the vehicle-number anchor (same layout as the BuildHubs app).
 *
 * Output rules: owner = first name then family name (the license prints family first),
 * address kept in full (postal code included), chassis without its first 3 characters.
 */
public final class LicenseParser {

    private LicenseParser() {}

    private static final class Item {
        String str;
        float x, y; // x = left edge, y = baseline measured from the page top
    }

    private static final Pattern HEB = Pattern.compile("[\\u0590-\\u05FF]");

    /** Returns the fields, or null when the page does not look like a license (no anchor). */
    public static VehicleParser.Fields parse(PDDocument doc) throws IOException {
        List<Item> items = readItems(doc);
        return extract(items);
    }

    static VehicleParser.Fields extract(List<Item> items) {
        Item anchor = null;
        for (Item i : items) {
            if (i.str.matches("\\d{5,8}") && i.x > 420 && i.y < 140) {
                if (anchor == null || i.y < anchor.y) anchor = i;
            }
        }
        if (anchor == null) return null;
        float dy = anchor.y - 71f;

        VehicleParser.Fields f = new VehicleParser.Fields();
        f.vehicle = anchor.str;

        // owner: the license prints "family first"; output "first family"
        List<String> ow = new ArrayList<>();
        for (Item i : row(items, 83, 5, 400, 600, dy)) if (HEB.matcher(i.str).find()) ow.add(i.str);
        if (ow.size() > 1) {
            List<String> r = new ArrayList<>(ow.subList(1, ow.size()));
            r.add(ow.get(0));
            f.owner = join(r);
        } else {
            f.owner = join(ow);
        }

        // address: the whole line, including the postal code
        List<String> ad = new ArrayList<>();
        for (Item i : row(items, 94, 5, 280, 600, dy)) ad.add(i.str);
        f.address = join(ad); // postal code is kept

        // id
        for (Item i : row(items, 84, 5, 100, 330, dy)) {
            if (i.str.matches("\\d{5,9}-?\\d")) { f.id = i.str; break; }
        }

        // year of manufacture
        List<String> yr = new ArrayList<>();
        for (Item i : row(items, 132, 5, 380, 600, dy)) yr.add(i.str);
        Matcher ym = Pattern.compile("\\b(19|20)\\d{2}\\b").matcher(join(yr));
        if (ym.find()) f.year = ym.group();

        // chassis: the Latin/digit groups on the chassis row, minus the first 3 characters
        StringBuilder ch = new StringBuilder();
        List<Item> chRow = row(items, 132, 5, 60, 340, dy); // right-to-left
        for (int k = chRow.size() - 1; k >= 0; k--) { // the chassis is Latin text: read left-to-right
            String s = chRow.get(k).str;
            if (s.matches("[A-Za-z0-9]+")) ch.append(s);
        }
        if (ch.length() >= 6) f.vin = ch.substring(3).toUpperCase();

        // engine volume (may be empty, e.g. for trailers)
        List<String> vo = new ArrayList<>();
        for (Item i : row(items, 156, 5, 350, 446, dy)) vo.add(i.str);
        Matcher vm = Pattern.compile("\\d{2,5}").matcher(join(vo));
        if (vm.find()) f.engine = vm.group();

        // maker (lower box, right column)
        List<String> mk = new ArrayList<>();
        for (Item i : row(items, 337, 6, 400, 600, dy)) mk.add(i.str);
        f.make = join(mk);

        return f;
    }

    /** Words whose baseline is within tol of (y + dy) and whose left edge is in [xMin, xMax), right-to-left. */
    private static List<Item> row(List<Item> items, float y, float tol, float xMin, float xMax, float dy) {
        List<Item> out = new ArrayList<>();
        for (Item i : items) {
            if (Math.abs(i.y - (y + dy)) <= tol && i.x >= xMin && i.x < xMax) out.add(i);
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) { return Float.compare(b.x, a.x); }
        });
        return out;
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(p.trim());
        }
        return sb.toString();
    }

    // ---- reading positioned words from page 1 ----

    private static final class Glyph {
        String u;
        float x, y, w, size;
    }

    private static List<Item> readItems(PDDocument doc) throws IOException {
        final List<Glyph> glyphs = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void processTextPosition(TextPosition t) {
                String u = t.getUnicode();
                if (u == null || u.isEmpty()) return;
                Glyph g = new Glyph();
                g.u = u;
                g.x = t.getXDirAdj();
                g.y = t.getYDirAdj();
                g.w = t.getWidthDirAdj();
                g.size = t.getFontSizeInPt();
                glyphs.add(g);
            }
        };
        stripper.setStartPage(1);
        stripper.setEndPage(1);
        stripper.getText(doc);

        // group glyphs into rows by baseline, then into words by horizontal gaps
        Collections.sort(glyphs, new Comparator<Glyph>() {
            @Override public int compare(Glyph a, Glyph b) {
                int c = Float.compare(a.y, b.y);
                return c != 0 ? c : Float.compare(a.x, b.x);
            }
        });
        List<Item> items = new ArrayList<>();
        int i = 0;
        while (i < glyphs.size()) {
            int j = i;
            float rowY = glyphs.get(i).y;
            List<Glyph> line = new ArrayList<>();
            while (j < glyphs.size() && Math.abs(glyphs.get(j).y - rowY) <= 1.5f) line.add(glyphs.get(j++));
            i = j;
            Collections.sort(line, new Comparator<Glyph>() {
                @Override public int compare(Glyph a, Glyph b) { return Float.compare(a.x, b.x); }
            });
            StringBuilder sb = new StringBuilder();
            float startX = 0, endX = 0, y = rowY;
            for (Glyph g : line) {
                boolean space = g.u.trim().isEmpty();
                boolean gap = sb.length() > 0 && g.x - endX > 0.3f * g.size;
                if (space || gap) {
                    flush(items, sb, startX, y);
                    if (space) continue;
                }
                if (sb.length() == 0) startX = g.x;
                sb.append(g.u);
                endX = g.x + g.w;
            }
            flush(items, sb, startX, y);
        }
        return items;
    }

    /** Glyphs were collected left-to-right; Hebrew words therefore come out letter-reversed. */
    private static void flush(List<Item> items, StringBuilder sb, float x, float y) {
        if (sb.length() == 0) return;
        String s = sb.toString();
        sb.setLength(0);
        if (HEB.matcher(s).find()) s = new StringBuilder(s).reverse().toString();
        Item it = new Item();
        it.str = s;
        it.x = x;
        it.y = y;
        items.add(it);
    }
}
