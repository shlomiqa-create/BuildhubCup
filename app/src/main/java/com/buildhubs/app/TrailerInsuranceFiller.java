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

    /** The form is typed in Courier New 10 pt (6 pt per character); Cousine has the same width, so the same size is used. */
    private static final float SIZE = 10f;
    /** Cap height of the monospaced font relative to its size: used to centre the text vertically in its cell. */
    private static final float CAP = 0.60f;
    /** Courier New has thin strokes; Cousine of the same size is heavier (about 1.8 times the ink; 85 is a compromise between the look and the weight), so the text is a dark gray to look as light as the form's own values. */
    private static final int INK = 85;

    /** Table lines (maroon, 0.49 pt) that run under the old values: they are drawn again after the covers. */
    private static final float[][] LINES = {
            {501f, 222.5f, 574.5f, 222.5f},   // top: under the name
            {361f, 185.7f, 423f, 185.7f},     // top: under the policy number row, above the ID
            {396f, 264.9f, 571.5f, 264.9f},   // top: under the address
            {511f, 290.3f, 554.5f, 290.3f},   // top: under the plate
            {403f, 290.3f, 476f, 290.3f},     // top: under the maker
            {448f, 316.4f, 467f, 316.4f},     // top: under the weight
            {341f, 641.4f, 396.5f, 641.4f},   // bottom: under the ID
            {517f, 665.1f, 566.5f, 665.1f},   // bottom: under the name
            {303f, 688.0f, 568.5f, 688.0f},   // bottom: under the address
            {305.2f, 675.5f, 305.2f, 688.1f}, // bottom: left edge of the address cell
            {505f, 712.2f, 566.5f, 712.2f},   // bottom: under the plate
            {419f, 712.2f, 493f, 712.2f},     // bottom: under the maker
            {334.5f, 712.2f, 354f, 712.2f},   // bottom: under the weight
    };

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
    private static final Cell TOP_MAKER = new Cell(476f, 282.2f, 90f);
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

    /** A date typed in the form: the old ink to hide {x0, top, x1, bottom}, and the centre of the new date. */
    private static final class DateCell {
        final float[] cover;
        final float cx, cy;
        DateCell(float x0, float top, float x1, float bottom, float cx, float cy) {
            this.cover = new float[]{x0, top, x1, bottom};
            this.cx = cx;
            this.cy = cy;
        }
    }

    // "not before" date (start of the insurance), top copy: the box at the top left
    private static final DateCell TOP_START = new DateCell(145.8f, 168.9f, 206.3f, 179.3f, 176.05f, 174.1f);
    private static final DateCell TOP_EXPIRY = new DateCell(46.1f, 194.4f, 106.3f, 204.8f, 76.2f, 199.6f);
    private static final DateCell TOP_ISSUE = new DateCell(391.9f, 347.4f, 452.5f, 356.9f, 422.2f, 352.6f);
    // credit notice (bottom copy)
    private static final DateCell BOTTOM_START = new DateCell(449.1f, 724.4f, 509.7f, 734.8f, 479.4f, 729.6f);
    private static final DateCell BOTTOM_EXPIRY = new DateCell(310.1f, 724.4f, 370.7f, 734.8f, 340.4f, 729.6f);
    private static final DateCell BOTTOM_START_BOX = new DateCell(156.6f, 755.7f, 217.1f, 766.1f, 186.85f, 760.9f);
    private static final DateCell BOTTOM_ISSUE = new DateCell(388.9f, 781.3f, 449.5f, 791.6f, 419.2f, 786.45f);

    public static void fill(PDDocument doc, String owner, String address, String plate, String id,
                            String weight, String makerModel, InputStream fontStream) throws IOException {
        fill(doc, owner, address, plate, id, weight, makerModel, null, null, fontStream);
    }

    /**
     * @param makerModel the maker followed by the model, as one line
     * @param startDate  "not before" date, dd/mm/yyyy (also written as the date of issue); empty = leave as is
     * @param expiryDate expiry date of the insurance, dd/mm/yyyy; empty = leave as is
     * @param fontStream a monospaced font with Hebrew (Cousine), like the Courier New of the form
     */
    public static void fill(PDDocument doc, String owner, String address, String plate, String id,
                            String weight, String makerModel, String startDate, String expiryDate,
                            InputStream fontStream) throws IOException {
        String start = InsuranceDates.normalize(startDate);
        String expiry = InsuranceDates.normalize(expiryDate);
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
            if (start != null) {
                cover(cs, pageH, k, TOP_START, BOTTOM_START, BOTTOM_START_BOX, TOP_ISSUE, BOTTOM_ISSUE);
            }
            if (expiry != null) {
                cover(cs, pageH, k, TOP_EXPIRY, BOTTOM_EXPIRY);
            }
            // the covers also hid some table lines: draw them again
            cs.setStrokingColor(122, 10, 59);
            cs.setLineWidth(0.49f * k);
            for (float[] l : LINES) {
                cs.moveTo(l[0] * k, pageH - l[1] * k);
                cs.lineTo(l[2] * k, pageH - l[3] * k);
                cs.stroke();
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

            if (start != null) {
                for (DateCell c : new DateCell[]{TOP_START, BOTTOM_START, BOTTOM_START_BOX, TOP_ISSUE, BOTTOM_ISSUE}) {
                    putDate(cs, font, pageH, k, c, start);
                }
            }
            if (expiry != null) {
                putDate(cs, font, pageH, k, TOP_EXPIRY, expiry);
                putDate(cs, font, pageH, k, BOTTOM_EXPIRY, expiry);
            }
        } finally {
            cs.close();
        }
    }

    private static void cover(PDPageContentStream cs, float pageH, float k, DateCell... cells) throws IOException {
        cs.setNonStrokingColor(255, 255, 255);
        for (DateCell c : cells) {
            cs.addRect(c.cover[0] * k, pageH - c.cover[3] * k, (c.cover[2] - c.cover[0]) * k, (c.cover[3] - c.cover[1]) * k);
            cs.fill();
        }
    }

    private static void putDate(PDPageContentStream cs, PDType0Font font, float pageH, float k, DateCell c, String date)
            throws IOException {
        float size = SIZE * k;
        float w = font.getStringWidth(date) / 1000f * size;
        cs.setNonStrokingColor(INK, INK, INK);
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(c.cx * k - w / 2f, pageH - (c.cy * k + size * CAP / 2f));
        cs.showText(date);
        cs.endText();
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
        float baseline = pageH - (c.centerY * k + size * CAP / 2f);
        cs.setNonStrokingColor(INK, INK, INK);
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, baseline);
        cs.showText(visual);
        cs.endText();
    }
}
