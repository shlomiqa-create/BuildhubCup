package com.buildhubs.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The study-example mark and its wording. */
public class ExampleTemplateTest {

    @Test
    public void theMarkSaysExampleAndNotValid() {
        assertTrue(ExampleMark.BIG.contains("דוגמה"));
        assertTrue(ExampleMark.SMALL.contains("אינו"));
        assertTrue(ExampleMark.FOOTER.contains("אינו תעודת ביטוח תקפה"));
        assertFalse(ExampleMark.FOOTER.isEmpty());
    }

    @Test
    public void markTextsAreConvertedForDrawing() {
        // the converted text is what is written into the PDF: it must differ from the logical order
        assertFalse(ExampleMark.BIG.equals(PdfFiller.toVisual(ExampleMark.BIG)));
        assertEquals(ExampleMark.BIG.length(), PdfFiller.toVisual(ExampleMark.BIG).length());
    }
}
