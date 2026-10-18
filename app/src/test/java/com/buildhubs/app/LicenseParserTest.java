package com.buildhubs.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Positional parsing of the license printout (text layer of the PDF), with made-up values. */
public class LicenseParserTest {

    @Test
    public void readsAllEightFields() {
        VehicleParser.Fields f = LicenseParser.extract(FakeLicense.items());
        assertNotNull(f);
        assertEquals(FakeLicense.OWNER, f.owner);          // exactly as printed
        assertEquals(FakeLicense.ID, f.id);
        assertEquals(FakeLicense.PLATE, f.vehicle);
        assertEquals(FakeLicense.ADDRESS, f.address);      // postal code kept
        assertEquals(FakeLicense.YEAR, f.year);
        assertEquals(FakeLicense.ENGINE, f.engine);
        assertEquals(FakeLicense.MAKE, f.make);
        assertEquals(FakeLicense.VIN_WITHOUT_FIRST_3, f.vin);
    }

    @Test
    public void toleratesAVerticalOffsetOfThePage() {
        List<LicenseParser.Item> moved = new ArrayList<>();
        for (LicenseParser.Item i : FakeLicense.items()) moved.add(new LicenseParser.Item(i.str, i.x, i.y + 9f));
        VehicleParser.Fields f = LicenseParser.extract(moved);
        assertNotNull(f);
        assertEquals(FakeLicense.PLATE, f.vehicle);
        assertEquals(FakeLicense.ID, f.id);
        assertEquals(FakeLicense.VIN_WITHOUT_FIRST_3, f.vin);
    }

    @Test
    public void noPlateAnchorMeansNotALicense() {
        List<LicenseParser.Item> items = new ArrayList<>();
        items.add(new LicenseParser.Item("שלום", 300f, 200f));
        assertNull(LicenseParser.extract(items));
    }

    @Test
    public void emptyEngineIsAllowed() {
        List<LicenseParser.Item> items = new ArrayList<>();
        for (LicenseParser.Item i : FakeLicense.items()) if (!i.str.equals(FakeLicense.ENGINE)) items.add(i);
        VehicleParser.Fields f = LicenseParser.extract(items);
        assertNotNull(f);
        assertNull(f.engine);
        assertEquals(7, f.count());
    }

    @Test
    public void chassisWindowDropsAStrayCharacter() {
        assertEquals("VTR4E3000J1171252", LicenseParser.bestChassisWindow("LVTR4E3000J1171252"));
        assertEquals("JTDKB20U123456789", LicenseParser.bestChassisWindow("JTDKB20U123456789"));
        assertEquals("ABC123", LicenseParser.bestChassisWindow("ABC123")); // short ones are left alone
    }
}
