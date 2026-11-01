package com.buildhubs.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class InsuranceDatesTest {

    @Test
    public void aFullYearIs365DaysWithBothEndDaysCounted() {
        assertEquals(365, InsuranceDates.insuranceDays("01/11/2026", "31/10/2027"));
    }

    @Test
    public void aLeapYearIs366Days() {
        assertEquals(366, InsuranceDates.insuranceDays("15/03/2027", "14/03/2028"));
    }

    @Test
    public void oneDayAndShortPeriods() {
        assertEquals(1, InsuranceDates.insuranceDays("05/05/2026", "05/05/2026"));
        assertEquals(31, InsuranceDates.insuranceDays("01/01/2026", "31/01/2026"));
        assertEquals(92, InsuranceDates.insuranceDays("01/11/2025", "31/01/2026"));
    }

    @Test
    public void endBeforeStartOrBadDatesGiveMinusOne() {
        assertEquals(-1, InsuranceDates.insuranceDays("05/05/2026", "04/05/2026"));
        assertEquals(-1, InsuranceDates.insuranceDays("31/02/2026", "01/03/2026"));
        assertEquals(-1, InsuranceDates.insuranceDays("", "01/03/2026"));
        assertEquals(-1, InsuranceDates.insuranceDays(null, null));
    }

    @Test
    public void parsingAcceptsCommonSeparatorsAndShortYears() {
        assertArrayEquals(new int[]{2026, 11, 1}, InsuranceDates.parse("01/11/2026"));
        assertArrayEquals(new int[]{2026, 11, 1}, InsuranceDates.parse(" 1.11.2026 "));
        assertArrayEquals(new int[]{2026, 11, 1}, InsuranceDates.parse("1-11-26"));
        assertNull(InsuranceDates.parse("31/04/2026"));
        assertNull(InsuranceDates.parse("29/02/2027"));
        assertNull(InsuranceDates.parse("01/13/2026"));
        assertNull(InsuranceDates.parse("abc"));
    }

    @Test
    public void february29OnlyInLeapYears() {
        assertArrayEquals(new int[]{2028, 2, 29}, InsuranceDates.parse("29/02/2028"));
        assertNull(InsuranceDates.parse("29/02/2100"));
    }

    @Test
    public void amountsAreFormattedLikeTheForm() {
        assertEquals("5,384.00", InsuranceDates.formatAmount("5384"));
        assertEquals("5,384.50", InsuranceDates.formatAmount("5,384.5"));
        assertEquals("5,384.00", InsuranceDates.formatAmount(" 5384.00 ₪"));
        assertEquals("950.00", InsuranceDates.formatAmount("950"));
        assertEquals("1,234,567.00", InsuranceDates.formatAmount("1234567"));
        assertEquals("0.50", InsuranceDates.formatAmount("0.5"));
        assertEquals("12.00", InsuranceDates.formatAmount("0012"));
    }

    @Test
    public void badAmountsAreRejected() {
        assertNull(InsuranceDates.formatAmount(""));
        assertNull(InsuranceDates.formatAmount(null));
        assertNull(InsuranceDates.formatAmount("abc"));
        assertNull(InsuranceDates.formatAmount("0"));
        assertNull(InsuranceDates.formatAmount("-5"));
        assertNull(InsuranceDates.formatAmount("12.345"));
        assertNull(InsuranceDates.formatAmount("1234567890"));
    }

    @Test
    public void theDateOfIssueUsesATwoDigitYear() {
        assertEquals("20/04/26", PolicyTerms.shortYear("20/04/2026"));
    }

    @Test
    public void normalizeUsesTheFormsFormat() {
        assertEquals("01/11/2026", InsuranceDates.normalize("1.11.26"));
        assertNull(InsuranceDates.normalize("32/01/2026"));
    }
}
