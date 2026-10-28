package com.buildhubs.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Right-to-left text laid out for a left-to-right PDF writer. */
public class VisualOrderTest {

    @Test
    public void textWithoutHebrewIsUnchanged() {
        assertEquals("12345678-2", PdfFiller.toVisual("12345678-2"));
        assertEquals("KLZ1000 ABS", PdfFiller.toVisual("KLZ1000 ABS"));
    }

    @Test
    public void hebrewWordsAreReversedAndTheirOrderFlipped() {
        // logical "שלום עולם": first word is on the right
        assertEquals("םלוע םולש", PdfFiller.toVisual("שלום עולם"));
    }

    @Test
    public void numbersKeepTheirDigits() {
        assertEquals("5800000 ןולוח 2 הריד 1 לצרה", PdfFiller.toVisual("הרצל 1 דירה 2 חולון 5800000"));
    }

    @Test
    public void aLatinRunKeepsItsLeftToRightOrder() {
        // the model "KLZ1000 ABS" must not become "ABS KLZ1000"
        assertEquals("KLZ1000 ABS ןפי יקאסאווק", PdfFiller.toVisual("קוואסאקי יפן KLZ1000 ABS"));
    }

    @Test
    public void extraSpacesAreCollapsed() {
        assertEquals("םלוע םולש", PdfFiller.toVisual("  שלום   עולם "));
    }
}
