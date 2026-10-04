package com.buildhubs.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java (no Android deps) parser for Israeli vehicle PDFs.
 * Tolerates PDFBox returning Hebrew in logical order, swapped word order,
 * label-after-value, label/value on separate lines, or fully visual (reversed) order.
 */
public final class VehicleParser {

    private VehicleParser() {}

    public static class Fields {
        public String owner, id, vehicle, address, year, engine, make, vin;

        public int count() {
            int n = 0;
            for (String s : new String[]{owner, id, vehicle, address, year, engine, make, vin})
                if (s != null && !s.isEmpty()) n++;
            return n;
        }

        public List<String> missing() {
            List<String> m = new ArrayList<>();
            if (empty(owner)) m.add("בעלים");
            if (empty(id)) m.add("תעודת זהות");
            if (empty(vehicle)) m.add("מספר רכב");
            if (empty(address)) m.add("מען");
            if (empty(year)) m.add("שנת ייצור");
            if (empty(engine)) m.add("נפח");
            if (empty(make)) m.add("תוצר");
            if (empty(vin)) m.add("מספר שילדה");
            return m;
        }

        private static boolean empty(String s) { return s == null || s.isEmpty(); }
    }

    // ---- field kinds, in resolution order (unambiguous first) ----
    private static final int VIN = 0, VEHICLE = 1, ID = 2, YEAR = 3, ENGINE = 4, MAKE = 5, OWNER = 6, ADDRESS = 7;

    private static final String[][] LABELS = {
            /* VIN     */ {"מספר שילדה", "שילדה מספר", "מס' שילדה", "שילדה", "VIN"},
            /* VEHICLE */ {"מספר רכב", "מספר הרכב", "רכב מספר", "מס' רכב", "מספר רישוי", "רישוי מספר", "מס' רישוי", "רישוי"},
            /* ID      */ {"תעודת זהות", "זהות תעודת", "מספר זהות", "ת.ז.", "ת.ז", "ת\"ז", "ת״ז", "מס' זהות / ח\"פ", "מס' זהות", "ח\"פ"},
            /* YEAR    */ {"שנת ייצור", "ייצור שנת", "שנת יצור", "יצור שנת"},
            /* ENGINE  */ {"נפח מנוע", "מנוע נפח", "נפח"},
            /* MAKE    */ {"תוצר", "יצרן / דגם", "יצרן", "דגם"},
            /* OWNER   */ {"בעלים", "שם הבעלים", "הבעלים", "בעל הרכב", "שם בעלים"},
            /* ADDRESS */ {"מען", "כתובת"},
    };

    private static final Pattern RE_VIN = Pattern.compile("(?<![A-Za-z0-9])[A-HJ-NPR-Za-hj-npr-z0-9]{17}(?![A-Za-z0-9])");
    private static final Pattern RE_VIN_LOOSE = Pattern.compile("(?<![A-Za-z0-9])[A-Za-z0-9]{10,25}(?![A-Za-z0-9])");
    private static final Pattern RE_VEHICLE = Pattern.compile("(?<!\\d)\\d{7,8}(?!\\d)");
    private static final Pattern RE_ID = Pattern.compile("(?<!\\d)\\d{8,9}(?:\\s*-\\s*\\d)?(?!\\d)");
    private static final Pattern RE_YEAR = Pattern.compile("(?<!\\d)(?:19|20)\\d{2}(?!\\d)");
    private static final Pattern RE_ENGINE = Pattern.compile("(?<!\\d)\\d{3,5}(?!\\d)");

    // ------------------------------------------------------------------
    public static String normalize(String s) {
        if (s == null) return "";
        s = s.replaceAll("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\u061C\\uFEFF]", "");
        s = s.replace("’", "'").replace("׳", "'").replace("״", "\"").replace("”", "\"");
        s = s.replace("\u00A0", " ").replace("\t", " ").replace("\u2011", "-").replace("\u2013", "-").replace("\u05BE", "-");
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder();
        for (String line : s.split("\n")) {
            String t = line.replaceAll("[ ]+", " ").trim();
            if (!t.isEmpty()) out.append(t).append('\n');
        }
        return out.toString();
    }

    /** Parse text from two PDFTextStripper passes (sortByPosition true/false); merge the best of both. */
    public static Fields parseBest(String... texts) {
        Fields best = null;
        List<Fields> all = new ArrayList<>();
        for (String t : texts) {
            if (t == null) continue;
            Fields f = parse(t);
            all.add(f);
            if (best == null || f.count() > best.count()) best = f;
        }
        if (best == null) return new Fields();
        for (Fields f : all) {
            if (best.owner == null) best.owner = f.owner;
            if (best.id == null) best.id = f.id;
            if (best.vehicle == null) best.vehicle = f.vehicle;
            if (best.address == null) best.address = f.address;
            if (best.year == null) best.year = f.year;
            if (best.engine == null) best.engine = f.engine;
            if (best.make == null) best.make = f.make;
            if (best.vin == null) best.vin = f.vin;
        }
        return best;
    }

    // ------------------------------------------------------------------
    private static final class Hit {
        int kind, start, end;
        boolean reversed;
    }

    private static final class Seg {
        int line, idx;
        String text;
        Seg(int line, int idx, String text) { this.line = line; this.idx = idx; this.text = text; }
    }

    public static Fields parse(String raw) {
        String norm = normalize(raw);
        String[] lines = norm.isEmpty() ? new String[0] : norm.split("\n");
        int n = lines.length;

        List<List<Hit>> hits = new ArrayList<>();
        List<List<Seg>> segs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<Hit> h = findHits(lines[i]);
            hits.add(h);
            List<Seg> sl = new ArrayList<>();
            int pos = 0;
            for (int k = 0; k <= h.size(); k++) {
                int end = k < h.size() ? h.get(k).start : lines[i].length();
                sl.add(new Seg(i, k, clean(lines[i].substring(pos, Math.max(pos, end)))));
                if (k < h.size()) pos = h.get(k).end;
            }
            segs.add(sl);
        }

        Fields f = new Fields();
        Set<String> used = new HashSet<>();

        // VIN needs no label: it is a unique 17-char token.
        Matcher vm = RE_VIN.matcher(norm.replace('\n', ' '));
        while (vm.find()) {
            String c = vm.group();
            if (c.matches(".*\\d.*") && c.matches(".*[A-Za-z].*")) { f.vin = c.toUpperCase(); break; }
        }

        if (f.vin != null) {
            for (List<Seg> sl : segs)
                for (Seg sg : sl)
                    if (sg.text.toUpperCase().contains(f.vin)) used.add(sg.line + ":" + sg.idx);
        }

        for (int kind = 0; kind <= ADDRESS; kind++) {
            if (kind == VIN && f.vin != null) continue;
            for (int i = 0; i < n; i++) {
                List<Hit> h = hits.get(i);
                for (int k = 0; k < h.size(); k++) {
                    Hit hit = h.get(k);
                    if (hit.kind != kind) continue;
                    // segment k = text before this label, k+1 = text after it
                    List<Seg> cands = new ArrayList<>();
                    cands.add(segs.get(i).get(k + 1));
                    cands.add(segs.get(i).get(k));
                    if (i + 1 < n) cands.add(segs.get(i + 1).get(0));
                    if (i > 0) { List<Seg> p = segs.get(i - 1); cands.add(p.get(p.size() - 1)); }

                    for (Seg c : cands) {
                        String key = c.line + ":" + c.idx;
                        if (used.contains(key) || c.text.isEmpty()) continue;
                        String v = extract(kind, c.text, hit.reversed);
                        if (v == null) continue;
                        used.add(key);
                        assign(f, kind, v);
                        break;
                    }
                    if (get(f, kind) != null) break;
                }
                if (get(f, kind) != null) break;
            }
        }
        return f;
    }

    // ------------------------------------------------------------------
    private static List<Hit> findHits(String line) {
        List<Hit> hits = new ArrayList<>();
        boolean[] taken = new boolean[line.length() + 1];
        // longer labels first so "מספר רכב" wins over anything shorter
        List<Object[]> variants = new ArrayList<>(); // {kind, text, reversed}
        for (int kind = 0; kind < LABELS.length; kind++) {
            for (String l : LABELS[kind]) {
                variants.add(new Object[]{kind, l, false});
                String rev = new StringBuilder(l).reverse().toString();
                if (!rev.equals(l) && containsHebrew(l)) variants.add(new Object[]{kind, rev, true});
            }
        }
        Collections.sort(variants, (a, b) -> ((String) b[1]).length() - ((String) a[1]).length());
        for (Object[] v : variants) {
            String lab = (String) v[1];
            int from = 0, p;
            while ((p = line.indexOf(lab, from)) >= 0) {
                int e = p + lab.length();
                from = e;
                if (!boundary(line, p, e, lab)) continue;
                boolean free = true;
                for (int x = p; x < e; x++) if (taken[x]) { free = false; break; }
                if (!free) continue;
                for (int x = p; x < e; x++) taken[x] = true;
                Hit h = new Hit();
                h.kind = (Integer) v[0]; h.start = p; h.end = e; h.reversed = (Boolean) v[2];
                hits.add(h);
            }
        }
        Collections.sort(hits, (a, b) -> a.start - b.start);
        return hits;
    }

    /** Label must not be glued to other Hebrew/Latin letters (avoids "נפח" inside a longer word). */
    private static boolean boundary(String s, int a, int b, String lab) {
        boolean wordLab = Character.isLetter(lab.charAt(0));
        if (!wordLab) return true;
        // multi-word / punctuated labels may be glued to the value before them (unsorted PDFBox text)
        boolean strong = lab.indexOf(' ') >= 0 || lab.indexOf(39) >= 0 || lab.indexOf('.') >= 0 || lab.equals("כתובת") || lab.equals("תוצר");
        if (!strong && a > 0 && Character.isLetter(s.charAt(a - 1))) return false;
        if (b < s.length() && Character.isLetter(s.charAt(b))) return false;
        return true;
    }

    private static boolean containsHebrew(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) >= 0x0590 && s.charAt(i) <= 0x05FF) return true;
        return false;
    }

    private static String clean(String s) {
        return s.replaceAll("^[\\s:;,.\\-|/]+|[\\s:;,|/]+$", "").trim();
    }

    private static String extract(int kind, String text, boolean visual) {
        Matcher m;
        switch (kind) {
            case VEHICLE:
                m = RE_VEHICLE.matcher(text); return m.find() ? m.group() : null;
            case ID:
                m = RE_ID.matcher(text); return m.find() ? m.group().replaceAll("\\s+", "") : null;
            case YEAR:
                m = RE_YEAR.matcher(text); return m.find() ? m.group() : null;
            case ENGINE:
                m = RE_ENGINE.matcher(text); return m.find() ? m.group() : null;
            case VIN:
                m = RE_VIN.matcher(text);
                if (m.find()) return m.group().toUpperCase();
                m = RE_VIN_LOOSE.matcher(text);
                while (m.find()) {
                    String c = m.group();
                    if (c.matches(".*\\d.*") && c.matches(".*[A-Za-z].*")) return c.toUpperCase();
                }
                return null;
            default:
                // text fields: need at least one letter; reject pure numbers
                if (!text.matches(".*\\p{L}.*")) return null;
                return visual ? unvisual(text) : text;
        }
    }

    /** Visual-order text -> logical: reverse word order, reverse letters of Hebrew words only. */
    private static String unvisual(String s) {
        String[] w = s.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = w.length - 1; i >= 0; i--) {
            String t = containsHebrew(w[i]) ? new StringBuilder(w[i]).reverse().toString() : w[i];
            if (sb.length() > 0) sb.append(' ');
            sb.append(t);
        }
        return sb.toString();
    }

    private static void assign(Fields f, int kind, String v) {
        switch (kind) {
            case VIN: f.vin = v; break;
            case VEHICLE: f.vehicle = v; break;
            case ID: f.id = v; break;
            case YEAR: f.year = v; break;
            case ENGINE: f.engine = v; break;
            case MAKE: f.make = v; break;
            case OWNER: f.owner = v; break;
            default: f.address = v;
        }
    }

    private static String get(Fields f, int kind) {
        switch (kind) {
            case VIN: return f.vin;
            case VEHICLE: return f.vehicle;
            case ID: return f.id;
            case YEAR: return f.year;
            case ENGINE: return f.engine;
            case MAKE: return f.make;
            case OWNER: return f.owner;
            default: return f.address;
        }
    }
}
