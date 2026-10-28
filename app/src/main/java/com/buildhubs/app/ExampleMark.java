package com.buildhubs.app;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import com.tom_roush.pdfbox.util.Matrix;

import java.io.IOException;
import java.io.InputStream;

/**
 * Marks every page of an insurance document as a study example: a large diagonal "דוגמה ללימוד" across the
 * page and a line at the bottom saying that it is not a valid insurance certificate.
 *
 * Insurance certificates produced by the app are practice examples only; the mark is always added and cannot
 * be switched off, so they cannot be mistaken for a real certificate.
 */
public final class ExampleMark {

    private ExampleMark() {}

    static final String BIG = "דוגמה ללימוד";
    static final String SMALL = "אינו תעודת ביטוח תקפה";
    static final String FOOTER = "מסמך לדוגמה ללימוד בלבד - אינו תעודת ביטוח תקפה";

    public static void apply(PDDocument doc, InputStream fontStream) throws IOException {
        PDType0Font font = PDType0Font.load(doc, fontStream, true);
        PDExtendedGraphicsState translucent = new PDExtendedGraphicsState();
        translucent.setNonStrokingAlphaConstant(0.22f);

        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            PDPage page = doc.getPage(i);
            float w = page.getMediaBox().getWidth();
            float h = page.getMediaBox().getHeight();
            PDPageContentStream cs = new PDPageContentStream(doc, page,
                    PDPageContentStream.AppendMode.APPEND, true, true);
            try {
                // diagonal marks (translucent red)
                cs.saveGraphicsState();
                cs.setGraphicsStateParameters(translucent);
                cs.setNonStrokingColor(200, 30, 30);
                float angle = (float) Math.atan2(h, w) * 0.85f; // roughly along the page diagonal
                diagonal(cs, font, BIG, w / 2f, h / 2f + 20f, angle, fitSize(font, BIG, 0.78f * (float) Math.hypot(w, h), 90f));
                diagonal(cs, font, SMALL, w / 2f, h / 2f - 40f, angle, fitSize(font, SMALL, 0.62f * (float) Math.hypot(w, h), 36f));
                cs.restoreGraphicsState();

                // footer line, opaque
                String footer = PdfFiller.toVisual(FOOTER);
                float size = 9f;
                float fw = font.getStringWidth(footer) / 1000f * size;
                cs.setNonStrokingColor(160, 0, 0);
                cs.beginText();
                cs.setFont(font, size);
                cs.newLineAtOffset((w - fw) / 2f, 12f);
                cs.showText(footer);
                cs.endText();
            } finally {
                cs.close();
            }
        }
    }

    private static float fitSize(PDType0Font font, String text, float maxWidth, float maxSize) throws IOException {
        String visual = PdfFiller.toVisual(text);
        float size = maxSize;
        while (size > 8f && font.getStringWidth(visual) / 1000f * size > maxWidth) size -= 1f;
        return size;
    }

    /** Draws 'text' (right-to-left, converted for drawing) centred on (cx, cy), rotated by 'angle' radians. */
    private static void diagonal(PDPageContentStream cs, PDType0Font font, String text, float cx, float cy,
                                 float angle, float size) throws IOException {
        String visual = PdfFiller.toVisual(text);
        float tw = font.getStringWidth(visual) / 1000f * size;
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        float x = cx - (tw / 2f) * cos;
        float y = cy - (tw / 2f) * sin - size * 0.3f;
        cs.beginText();
        cs.setFont(font, size);
        cs.setTextMatrix(Matrix.getRotateInstance(angle, x, y));
        cs.showText(visual);
        cs.endText();
    }
}
