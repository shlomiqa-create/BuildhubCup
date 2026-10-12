package com.buildhubs.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * OCR of a vehicle license photo / scan / screenshot (Tesseract, Hebrew + English models bundled in assets).
 * Everything here is slow (seconds): call it from a background thread.
 *
 * Up to three attempts are made, stopping as soon as the result looks complete:
 *   A. the image as it is, normal page analysis;
 *   B. the same image with "sparse text" analysis (copes with ruled lines / banners that confuse A);
 *   C. the image cropped to the sheet of paper.
 * The attempts are merged field by field ({@link OcrLicenseParser#merge}).
 */
public final class OcrReader {

    private OcrReader() {}

    /** The page is scaled to about 300 dpi: much better recognition of small digits / Latin letters. */
    private static final int TARGET_WIDTH = 2480;
    private static final String[] MODELS = {"heb.traineddata", "eng.traineddata"};

    /**
     * @return the recognised fields, or null when the image does not look like a license page
     */
    public static VehicleParser.Fields readLicense(Context context, Bitmap source) throws Exception {
        String dataPath = prepareTessData(context).getAbsolutePath();

        final TessBaseAPI main = new TessBaseAPI();   // heb + eng: the full page
        final TessBaseAPI digits = new TessBaseAPI(); // eng: digits / chassis crops
        Bitmap plain = null;
        Bitmap cropped = null;
        try {
            if (!main.init(dataPath, "heb+eng") || !digits.init(dataPath, "eng")) {
                throw new Exception("לא ניתן לטעון את מנוע הזיהוי (OCR).");
            }

            plain = preparePage(source, false);
            VehicleParser.Fields best = attempt(main, digits, plain, TessBaseAPI.PageSegMode.PSM_AUTO);

            if (!OcrLicenseParser.isGood(best)) {
                best = OcrLicenseParser.merge(best, attempt(main, digits, plain, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT));
            }
            if (!OcrLicenseParser.isGood(best)) {
                cropped = preparePage(source, true);
                if (cropped != null) {
                    best = OcrLicenseParser.merge(best, attempt(main, digits, cropped, TessBaseAPI.PageSegMode.PSM_AUTO));
                }
            }
            return best;
        } finally {
            main.recycle();
            digits.recycle();
            if (plain != null && plain != source) plain.recycle();
            if (cropped != null && cropped != source) cropped.recycle();
        }
    }

    /** One OCR pass over a prepared page: read, normalise the text size if needed, parse. */
    private static VehicleParser.Fields attempt(final TessBaseAPI main, final TessBaseAPI digits,
                                                Bitmap page, int segmentationMode) {
        final Bitmap[] work = {page};
        try {
            main.setPageSegMode(segmentationMode);
            main.setImage(work[0]);
            String hocr = main.getHOCRText(0);
            List<OcrLicenseParser.Word> words = OcrLicenseParser.parseHocr(hocr);
            float tilt = OcrLicenseParser.hocrTilt(hocr);

            // Normalise the text size (about 4.17 pixels per point = 300 dpi): a photo of part of the page
            // or a small / large scan is resized and read again.
            float scale = OcrLicenseParser.estimateScale(words, work[0].getWidth(), tilt);
            if (!Float.isNaN(scale) && (scale < 3.6f || scale > 4.8f)) {
                float factor = 4.17f / scale;
                int w = Math.max(200, Math.round(work[0].getWidth() * factor));
                int h = Math.max(200, Math.round(work[0].getHeight() * factor));
                work[0] = Bitmap.createScaledBitmap(page, w, h, true);
                main.setImage(work[0]);
                hocr = main.getHOCRText(0);
                words = OcrLicenseParser.parseHocr(hocr);
                tilt = OcrLicenseParser.hocrTilt(hocr);
            }

            OcrLicenseParser.Refiner refiner = new OcrLicenseParser.Refiner() {
                @Override
                public String read(float x0, float y0, float x1, float y1, String whitelist) {
                    return readCrop(main, digits, work[0], x0, y0, x1, y1, whitelist);
                }
            };
            return OcrLicenseParser.parse(words, work[0].getWidth(), work[0].getHeight(), tilt, refiner);
        } finally {
            if (work[0] != page) work[0].recycle();
            main.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
        }
    }

    /** Re-reads a small rectangle: one word (digits / Hebrew), or a single line for the chassis number. */
    private static String readCrop(TessBaseAPI hebrew, TessBaseAPI latin, Bitmap src,
                                   float x0, float y0, float x1, float y1, String whitelist) {
        try {
            int ix0 = Math.max(0, Math.round(x0)), iy0 = Math.max(0, Math.round(y0));
            int ix1 = Math.min(src.getWidth(), Math.round(x1)), iy1 = Math.min(src.getHeight(), Math.round(y1));
            int w = ix1 - ix0, h = iy1 - iy0;
            if (w < 10 || h < 10) return "";

            Bitmap crop = Bitmap.createBitmap(src, ix0, iy0, w, h);
            Bitmap padded = Bitmap.createBitmap(w + 40, h + 40, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(padded);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(crop, 20, 20, null);
            crop.recycle();

            TessBaseAPI api = whitelist == null ? hebrew : latin;
            api.setVariable("tessedit_char_whitelist", whitelist == null ? "" : whitelist);
            api.setPageSegMode(OcrLicenseParser.CHASSIS_CHARS.equals(whitelist)
                    ? TessBaseAPI.PageSegMode.PSM_SINGLE_LINE : TessBaseAPI.PageSegMode.PSM_SINGLE_WORD);
            api.setImage(padded);
            String text = api.getUTF8Text();
            padded.recycle();
            return text == null ? "" : text.trim();
        } catch (Exception e) {
            return "";
        }
    }

    // ---- image preparation ----

    /**
     * Scale to ~300 dpi and brighten dim photos; optionally crop to the sheet of paper first.
     * @return the prepared page, or null when a crop was asked for but no sheet of paper was found
     */
    static Bitmap preparePage(Bitmap source, boolean crop) {
        Bitmap base = crop ? cropPaper(source) : source;
        if (base == null) return null;
        int w = TARGET_WIDTH;
        int h = Math.round(base.getHeight() * (w / (float) base.getWidth()));
        Bitmap scaled = Bitmap.createScaledBitmap(base, w, h, true);
        if (base != source && base != scaled) base.recycle();
        if (!scaled.isMutable()) scaled = scaled.copy(Bitmap.Config.ARGB_8888, true);

        int[] pixels = new int[w * h];
        scaled.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] lum = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) lum[i] = ImagePrep.luminance(pixels[i]);
        int[] stretched = ImagePrep.stretch(lum);
        if (!Arrays.equals(stretched, lum)) { // only dim / washed-out images are converted to grayscale
            for (int i = 0; i < pixels.length; i++) {
                int v = stretched[i];
                pixels[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
            scaled.setPixels(pixels, 0, w, 0, 0, w, h);
        }
        return scaled;
    }

    /** Finds the sheet of paper in a photo (dark table around it) using a small copy of the image; null if none. */
    private static Bitmap cropPaper(Bitmap src) {
        int sw = src.getWidth(), sh = src.getHeight();
        float shrink = Math.max(1f, Math.max(sw, sh) / 500f);
        int tw = Math.max(50, Math.round(sw / shrink)), th = Math.max(50, Math.round(sh / shrink));
        Bitmap small = Bitmap.createScaledBitmap(src, tw, th, true);
        int[] px = new int[tw * th];
        small.getPixels(px, 0, tw, 0, 0, tw, th);
        if (small != src) small.recycle();
        int[] lum = new int[px.length];
        for (int i = 0; i < px.length; i++) lum[i] = ImagePrep.luminance(px[i]);

        int[] box = PaperCrop.findPaper(lum, tw, th);
        if (box == null) return null;
        int x0 = Math.max(0, Math.round(box[0] * (sw / (float) tw)));
        int y0 = Math.max(0, Math.round(box[1] * (sh / (float) th)));
        int x1 = Math.min(sw, Math.round(box[2] * (sw / (float) tw)));
        int y1 = Math.min(sh, Math.round(box[3] * (sh / (float) th)));
        if (x1 - x0 < 100 || y1 - y0 < 100) return null;
        return Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0);
    }

    // ---- language models ----

    /** Copies the Tesseract models from assets (once) and returns the folder that contains "tessdata". */
    private static File prepareTessData(Context context) throws Exception {
        File base = context.getFilesDir();
        File dir = new File(base, "tessdata");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("לא ניתן ליצור תיקיית נתוני OCR");
        for (String name : MODELS) {
            File target = new File(dir, name);
            long expected;
            try (InputStream in = context.getAssets().open("tessdata/" + name)) {
                expected = in.available();
            }
            if (target.exists() && target.length() == expected && expected > 0) continue;
            try (InputStream in = context.getAssets().open("tessdata/" + name);
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
        return base;
    }
}
