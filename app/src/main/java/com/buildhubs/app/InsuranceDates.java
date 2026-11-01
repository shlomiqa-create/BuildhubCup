package com.buildhubs.app;

import java.util.Locale;

/**
 * Dates of an insurance period, typed as dd/mm/yyyy: parsing, formatting and the number of insurance days
 * (both the first and the last day count, so 01/11/2026 - 31/10/2027 is 365 days).
 * No java.time: the app supports Android 7 (API 24).
 */
public final class InsuranceDates {

    private InsuranceDates() {}

    /** @return {year, month, day}, or null when the text is not a real date. Accepts / . - as separators. */
    public static int[] parse(String text) {
        if (text == null) return null;
        String[] parts = text.trim().replace('.', '/').replace('-', '/').split("/");
        if (parts.length != 3) return null;
        try {
            int d = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            int y = Integer.parseInt(parts[2].trim());
            if (y < 100) y += 2000;
            if (y < 1900 || y > 2200 || m < 1 || m > 12 || d < 1 || d > daysInMonth(y, m)) return null;
            return new int[]{y, m, d};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The date as dd/mm/yyyy (the form's format), or null when the text is not a real date. */
    public static String normalize(String text) {
        int[] a = parse(text);
        return a == null ? null : String.format(Locale.ROOT, "%02d/%02d/%04d", a[2], a[1], a[0]);
    }

    /** Days from the first to the last day, both counted; -1 when a date is invalid or the end is before the start. */
    public static int insuranceDays(String start, String end) {
        int[] s = parse(start), e = parse(end);
        if (s == null || e == null) return -1;
        long days = epochDay(e[0], e[1], e[2]) - epochDay(s[0], s[1], s[2]) + 1;
        return days < 1 || days > 100000 ? -1 : (int) days;
    }

    /**
     * An amount in shekels as the form prints it: thousands separator and two decimals ("5384" -> "5,384.00").
     * Accepts a currency sign, spaces and a comma as thousands separator; null when it is not a positive number.
     */
    public static String formatAmount(String text) {
        if (text == null) return null;
        String s = text.replace("₪", "").replace("ש\"ח", "").replace(" ", "").replace(",", "").trim();
        if (s.isEmpty() || !s.matches("[0-9]{1,9}(\\.[0-9]{1,2})?")) return null;
        String[] parts = s.split("\\.");
        String whole = parts[0].replaceFirst("^0+(?=[0-9])", "");
        String cents = parts.length > 1 ? (parts[1] + "0").substring(0, 2) : "00";
        if (whole.equals("0") && cents.equals("00")) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < whole.length(); i++) {
            if (i > 0 && (whole.length() - i) % 3 == 0) out.append(',');
            out.append(whole.charAt(i));
        }
        return out + "." + cents;
    }

    private static int daysInMonth(int y, int m) {
        switch (m) {
            case 2: return (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) ? 29 : 28;
            case 4: case 6: case 9: case 11: return 30;
            default: return 31;
        }
    }

    /** Days since 1970-01-01 (proleptic Gregorian calendar). */
    private static long epochDay(int y, int m, int d) {
        long yy = m <= 2 ? y - 1 : y;
        long era = (yy >= 0 ? yy : yy - 399) / 400;
        long yoe = yy - era * 400;
        long doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }
}
