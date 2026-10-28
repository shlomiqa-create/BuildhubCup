package com.buildhubs.app;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;

import java.io.IOException;
import java.io.InputStream;

/**
 * Fills the six policy-holder / vehicle fields of the trailer compulsory-insurance certificate
 * (the Ayalon A4 form uploaded by the user), in both copies on page 1: the certificate and the credit notice.
 *
 * Fields: policy holder name, address, vehicle (licence) number, ID / company number, weight, maker / model.
 * The form is uploaded by the user (nothing is bundled): the old values of these cells are covered first.
 * Coordinates are top-down points measured from the printed cells.
 */
public final class TrailerInsuranceFiller {

    private TrailerInsuranceFiller() {}

    private static final float SIZE = 12f;

    /** One cell: the right edge of the text (RTL), the vertical centre and the width available. */
    private static final class Cell {
        final float right, centerY, maxWidth;
        Cell(float right, float centerY, float maxWidth) { this.right = right; this.centerY = centerY; this.maxWidth = maxWidth; }
    }

    // ---- certificate (top copy) ----
    private static final Cell TOP_NAME = new Cell(575f, 214.4f, 265f);
    private static final Cell TOP_ID = new Cell(422f, 194.6f, 112f);
    private static final Cell TOP_ADDRESS = new Cell(575f, 256.8f, 265f);
    private static final Cell TOP_PLATE = new Cell(554f, 282.2f, 70f);
    private static final Cell TOP_MAKER = new Cell(476f, 282.2f, 112f);
    private static final Cell TOP_WEIGHT = new Cell(467f, 308.3f, 55f);

    // ---- credit notice (bottom copy) ----
    private static final Cell BOTTOM_ID = new Cell(396f, 634.6f, 88f);
    private static final Cell BOTTOM_NAME = new Cell(566f, 658.9f, 250f);
    private static final Cell BOTTOM_ADDRESS = new Cell(568.5f, 681.8f, 260f);
    private static final Cell BOTTOM_PLATE = new Cell(566f, 706.2f, 68f);
    private static final Cell BOTTOM_MAKER = new Cell(492.5f, 706.2f, 98f);
    private static final Cell BOTTOM_WEIGHT = new Cell(353.5f, 706.2f, 44f);

    /** The old values to hide: {x0, top, x1, bottom} in top-down points, kept clear of the printed labels. */
    private static final float[][] COVERS = {
            // certificate (top copy)
            {501f, 206.5f, 574.5f, 223.2f},   // name
            {361f, 186.2f, 423f, 203f},       // ID
            {396f, 252f, 571.5f, 266.2f},     // address
            {511f, 278f, 554.5f, 290f},       // plate
            {403f, 278f, 476f, 290f},         // maker / model
            {448f, 306f, 467f, 323f},         // weight
            // credit notice (bottom copy)
            {341f, 626.2f, 396.5f, 643f},     // ID
            {517f, 652.4f, 566.5f, 666.6f},   // name
            {303f, 676f, 568.5f, 690.6f},     // address
            {505f, 699.5f, 566.5f, 714.6f},   // plate
            {419f, 699.5f, 493f, 714.6f},     // maker / model
            {334.5f, 699.5f, 354f, 714.6f},   // weight
    };

    /**
     * @param makerModel the maker followed by the model, as one line
     */
    public static void fill(PDDocument doc, String owner, String address, String plate, String id,
                            String weight, String makerModel, InputStream fontStream) throws IOException {
        PDPage page = doc.getPage(0);
        float pageW = page.getMediaBox().getWidth();
        float pageH = page.getMediaBox().getHeight();
        float k = Math.min(pageW / 595.276f, pageH / 841.89f);
        PDType0Font font = PDType0Font.load(doc, fontStream, true);

        PDPageContentStream cs = new PDPageContentStream(doc, page,
                PDPageContentStream.AppendMode.APPEND, true, true);
        try {
            // hide the values printed on the uploaded form (the cells stay: only the old text is covered)
            cs.setNonStrokingColor(255, 255, 255);
            for (float[] c : COVERS) {
                cs.addRect(c[0] * k, pageH - c[3] * k, (c[2] - c[0]) * k, (c[3] - c[1]) * k);
                cs.fill();
            }
            cs.setNonStrokingColor(0, 0, 0);
            put(cs, font, pageH, k, TOP_NAME, owner);
            put(cs, font, pageH, k, TOP_ID, id);
            put(cs, font, pageH, k, TOP_ADDRESS, address);
            put(cs, font, pageH, k, TOP_PLATE, plate);
            put(cs, font, pageH, k, TOP_MAKER, makerModel);
            put(cs, font, pageH, k, TOP_WEIGHT, weight);

            put(cs, font, pageH, k, BOTTOM_ID, id);
            put(cs, font, pageH, k, BOTTOM_NAME, owner);
            put(cs, font, pageH, k, BOTTOM_ADDRESS, address);
            put(cs, font, pageH, k, BOTTOM_PLATE, plate);
            put(cs, font, pageH, k, BOTTOM_MAKER, makerModel);
            put(cs, font, pageH, k, BOTTOM_WEIGHT, weight);
        } finally {
            cs.close();
        }
    }

    private static void put(PDPageContentStream cs, PDType0Font font, float pageH, float k, Cell c, String text)
            throws IOException {
        if (text == null || text.trim().isEmpty()) return;
        String visual = PdfFiller.toVisual(text.trim());
        float size = SIZE * k;
        float avail = c.maxWidth * k;
        float w = font.getStringWidth(visual) / 1000f * size;
        while (w > avail && size > 5f) {
            size -= 0.25f;
            w = font.getStringWidth(visual) / 1000f * size;
        }
        float x = c.right * k - w;
        float baseline = pageH - (c.centerY * k + size * 0.36f);
        cs.setNonStrokingColor(0, 0, 0);
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, baseline);
        cs.showText(visual);
        cs.endText();
    }
}
