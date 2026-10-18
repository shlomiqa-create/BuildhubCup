package com.buildhubs.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Text parsing of the "vehicle and owner details" PDF, in the layouts PDFBox produces (made-up data). */
public class VehicleParserTest {

    private static void assertAll(VehicleParser.Fields f) {
        assertNotNull(f);
        assertEquals("ישראל ישראלי", f.owner);
        assertEquals("12345678-2", f.id);
        assertEquals("1234567", f.vehicle);
        assertEquals("הרצל 1 דירה 2 חולון", f.address);
        assertEquals("2015", f.year);
        assertEquals("1598", f.engine);
        assertEquals("טויוטה", f.make);
        assertEquals("TMBEG7NE0F0123456", f.vin);
        assertEquals(8, f.count());
    }

    @Test
    public void valueThenLabelOnOneLine() {
        String text = "פרטי רכב ובעלים\n"
                + "ישראל ישראלי שם הבעלים\n"
                + "12345678-2 מס' זהות / ח\"פ\n"
                + "1234567 מס' רישוי\n"
                + "הרצל 1 דירה 2 חולון כתובת\n"
                + "2015 שנת ייצור\n"
                + "1598 נפח מנוע\n"
                + "טויוטה יצרן / דגם\n"
                + "TMBEG7NE0F0123456 מס' שילדה\n";
        assertAll(VehicleParser.parse(text));
    }

    @Test
    public void valueGluedToLabelIsCompletedFromAnotherReading() {
        String glued = "ישראל ישראלישם הבעלים\n"
                + "12345678-2מס' זהות / ח\"פ\n"
                + "1234567מס' רישוי\n"
                + "הרצל 1 דירה 2 חולוןכתובת\n"
                + "2015שנת ייצור\n"
                + "1598נפח מנוע\n"
                + "טויוטהיצרן / דגם\n"
                + "TMBEG7NE0F0123456מס' שילדה\n";
        String sorted = "ישראל ישראלי שם הבעלים\n"
                + "הרצל 1 דירה 2 חולון כתובת\n";
        VehicleParser.Fields f = VehicleParser.parseBest(glued, sorted);
        assertEquals("ישראל ישראלי", f.owner);
        assertEquals("12345678-2", f.id);
        assertEquals("הרצל 1 דירה 2 חולון", f.address);
        assertEquals("TMBEG7NE0F0123456", f.vin);
    }

    @Test
    public void labelOnOneLineValueOnTheNext() {
        String text = "שם הבעלים\nישראל ישראלי\n"
                + "מס' זהות / ח\"פ\n12345678-2\n"
                + "מס' רישוי\n1234567\n"
                + "כתובת\nהרצל 1 דירה 2 חולון\n"
                + "שנת ייצור\n2015\n"
                + "נפח מנוע\n1598\n"
                + "יצרן / דגם\nטויוטה\n"
                + "מס' שילדה\nTMBEG7NE0F0123456\n";
        assertAll(VehicleParser.parse(text));
    }

    @Test
    public void labelBeforeValueOnOneLine() {
        String text = "בעלים ישראל ישראלי\n"
                + "מען הרצל 1 דירה 2 חולון\n"
                + "תעודת זהות 12345678-2\n"
                + "מספר רכב 1234567\n"
                + "שנת ייצור 2015\n"
                + "נפח 1598\n"
                + "תוצר טויוטה\n"
                + "TMBEG7NE0F0123456 שילדה מספר\n";
        assertAll(VehicleParser.parse(text));
    }

    @Test
    public void vinIsFoundWithoutItsLabel() {
        VehicleParser.Fields f = VehicleParser.parse("משהו TMBEG7NE0F0123456 אחר\n");
        assertEquals("TMBEG7NE0F0123456", f.vin);
    }

    @Test
    public void missingFieldsAreReported() {
        VehicleParser.Fields f = VehicleParser.parse("שנת ייצור 2015\n");
        assertEquals("2015", f.year);
        assertTrue(f.missing().contains("בעלים"));
        assertTrue(f.missing().contains("מספר שילדה"));
    }
}
