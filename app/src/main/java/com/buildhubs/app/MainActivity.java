package com.buildhubs.app;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.icu.text.Bidi;

public class MainActivity extends AppCompatActivity {

    private Uri sourceUri;
    private Uri targetUri;

    private TextView sourceLabel;
    private TextView targetLabel;
    private TextView status;

    private ActivityResultLauncher<String[]> picker;
    private boolean pickingSource;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);

        PDFBoxResourceLoader.init(getApplicationContext());

        buildUi();
    }

    private void buildUi() {

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 30, 28, 28);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("Build Hubs");
        title.setTextSize(30);
        title.setTextColor(Color.rgb(13, 91, 120));
        title.setGravity(Gravity.CENTER);

        root.addView(title,
                new LinearLayout.LayoutParams(-1, 70));

        TextView sub = new TextView(this);
        sub.setText("העתקת שדות מ-PDF מקור אל טופס PDF");
        sub.setTextSize(18);
        sub.setGravity(Gravity.CENTER);

        root.addView(sub,
                new LinearLayout.LayoutParams(-1, 60));

        sourceLabel = label("לא נבחר PDF מקור");
        root.addView(sourceLabel);

        Button s = button("1. בחירת PDF מקור");

        s.setOnClickListener(v -> {
            pickingSource = true;
            picker.launch(new String[]{"application/pdf"});
        });

        root.addView(s);

        targetLabel = label("לא נבחר PDF יעד");
        root.addView(targetLabel);

        Button t = button("2. בחירת PDF יעד / טופס");

        t.setOnClickListener(v -> {
            pickingSource = false;
            picker.launch(new String[]{"application/pdf"});
        });

        root.addView(t);

        Space sp = new Space(this);
        root.addView(sp,
                new LinearLayout.LayoutParams(1, 24));

        Button make = button("3. יצירת PDF חדש");

        make.setOnClickListener(v -> create());

        root.addView(make);

        status = label(
                "הערכים שיועתקו: בעלים, ת.ז., מספר רכב, מען, שנת ייצור, נפח, תוצר ומספר שילדה."
        );

        root.addView(status);

        setContentView(root);

        picker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {

                        try {
                            getContentResolver()
                                    .takePersistableUriPermission(
                                            uri,
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    );
                        } catch (Exception ignored) {
                        }

                        if (pickingSource) {
                            sourceUri = uri;
                            sourceLabel.setText(
                                    "מקור: " +
                                    uri.getLastPathSegment()
                            );
                        } else {
                            targetUri = uri;
                            targetLabel.setText(
                                    "יעד: " +
                                    uri.getLastPathSegment()
                            );
                        }
                    }
                }
        );
    }

    private TextView label(String s) {

        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(16);
        v.setPadding(4, 12, 4, 12);

        return v;
    }

    private Button button(String s) {

        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(16);

        return b;
    }

    private void create() {

        if (sourceUri == null || targetUri == null) {

            status.setText(
                    "יש לבחור גם PDF מקור וגם PDF יעד."
            );

            return;
        }

        status.setText("קורא את PDF המקור...");

        try {

            Fields f = readFields(sourceUri);

            File out = new File(
                    getExternalFilesDir(
                            Environment.DIRECTORY_DOCUMENTS
                    ),
                    "BuildHubs_" +
                    System.currentTimeMillis() +
                    ".pdf"
            );

            editTarget(targetUri, out, f);

            status.setText(
                    "נוצר PDF חדש בהצלחה:\n" +
                    out.getAbsolutePath()
            );

            share(out);

        } catch (Exception e) {

            status.setText(
                    "שגיאה: " + e.getMessage()
            );
        }
    }    private Fields readFields(Uri uri) throws Exception {

        Fields f = new Fields();
        String text = "";

        try (InputStream in = getContentResolver().openInputStream(uri)) {

            if (in == null) {
                throw new Exception("לא ניתן לפתוח את PDF המקור.");
            }

            try (PDDocument document = PDDocument.load(in)) {

                if (document.getNumberOfPages() == 0) {
                    throw new Exception("PDF המקור ריק.");
                }

                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                text = stripper.getText(document);

                String normalized = normalizeText(text);

                if (!looksLikeVehicleDocument(normalized)) {
                    PDFTextStripper fallback = new PDFTextStripper();
                    fallback.setSortByPosition(false);
                    String fallbackText = fallback.getText(document);

                    if (fallbackText != null &&
                            fallbackText.trim().length() > normalized.length()) {
                        text = fallbackText;
                    }
                }
            }
        }

        text = normalizeText(text);

        f.owner = findTextField(text, "בעלים");
        f.address = findTextField(text, "מען");
        f.make = findTextField(text, "תוצר");
        f.id = findId(text);
        f.vehicle = findNumberField(text, "מספר רכב");
        f.year = findNumberField(text, "שנת ייצור");
        f.engine = findNumberField(text, "נפח");
        f.vin = findVinField(text);

        StringBuilder missing = new StringBuilder();

        if (isEmpty(f.owner))   missing.append("בעלים, ");
        if (isEmpty(f.id))      missing.append("תעודת זהות, ");
        if (isEmpty(f.vehicle)) missing.append("מספר רכב, ");
        if (isEmpty(f.address)) missing.append("מען, ");
        if (isEmpty(f.year))    missing.append("שנת ייצור, ");
        if (isEmpty(f.engine))  missing.append("נפח, ");
        if (isEmpty(f.make))    missing.append("תוצר, ");
        if (isEmpty(f.vin))     missing.append("מספר שילדה, ");

        if (missing.length() > 0) {
            String missingText = missing.toString();
            if (missingText.endsWith(", ")) {
                missingText = missingText.substring(0, missingText.length() - 2);
            }

            throw new Exception(
                    "לא הצלחתי לזהות: " + missingText +
                    ". ודא שזה PDF עם טקסט ולא צילום סרוק."
            );
        }

        return f;
    }

    private boolean looksLikeVehicleDocument(String text) {
        if (text == null) return false;

        int found = 0;

        String[] labels = {
                "בעלים",
                "מען",
                "תוצר",
                "מספר רכב",
                "שנת ייצור",
                "נפח",
                "תעודת זהות",
                "ת.ז",
                "שילדה",
                "VIN"
        };

        for (String label : labels) {
            if (text.contains(label)) {
                found++;
            }
        }

        return found >= 2;
    }

    private String normalizeText(String text) {

        if (text == null) {
            return "";
        }

        text = text
                .replace("\u200E", "")
                .replace("\u200F", "")
                .replace("\u202A", "")
                .replace("\u202B", "")
                .replace("\u202C", "")
                .replace("\u202D", "")
                .replace("\u202E", "")
                .replace("\u2066", "")
                .replace("\u2067", "")
                .replace("\u2069", "")
                .replace('\u00A0', ' ')
                .replace('\u0000', ' ');

        text = text.replace("\r\n", "\n")
                .replace('\r', '\n');

        text = text.replaceAll("[ \t]+", " ");
        text = text.replaceAll(" *\n *", "\n");
        text = text.replaceAll("\n{3,}", "\n\n");

        return text.trim();
    }

    private String cleanText(String text) {
        return normalizeText(text).replace("\n", " ").trim();
    }

    private String findTextField(String text, String label) {

        if (text == null || text.isEmpty()) {
            return null;
        }

        String[] lines = text.split("\n");

        for (int i = 0; i < lines.length; i++) {

            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            int pos = line.indexOf(label);

            if (pos >= 0) {

                String value = line.substring(pos + label.length())
                        .replaceFirst("^[\\s:：\\-–—]+", "")
                        .trim();

                value = cleanFieldValue(value);

                if (isValidTextValue(value)) {
                    return value;
                }

                if (i + 1 < lines.length) {
                    String next = cleanFieldValue(lines[i + 1]);

                    if (isValidTextValue(next)) {
                        return next;
                    }
                }
            }
        }

        return null;
    }

    private String cleanFieldValue(String value) {

        if (value == null) return "";

        value = normalizeText(value)
                .replace("\n", " ")
                .trim();

        value = value.replaceAll("^[\\s:：\\-–—]+", "");
        value = value.replaceAll("[\\s:：\\-–—]+$", "");

        return value.trim();
    }

    private boolean isValidTextValue(String value) {

        if (value == null || value.trim().isEmpty()) {
            return false;
        }

        String v = value.trim();

        if (isKnownLabel(v)) {
            return false;
        }

        String[] labels = {
                "בעלים", "מען", "תוצר", "תעודת זהות", "ת.ז",
                "מספר רכב", "שנת ייצור", "נפח", "מספר שילדה",
                "שילדה מספר", "VIN"
        };

        for (String label : labels) {
            if (v.startsWith(label + " ") ||
                    v.startsWith(label + ":")) {
                return false;
            }
        }

        return true;
    }

    private boolean isKnownLabel(String value) {

        if (value == null) return true;

        String v = value.trim();

        return v.equals("בעלים") ||
                v.equals("מען") ||
                v.equals("תוצר") ||
                v.equals("תעודת זהות") ||
                v.equals("ת.ז") ||
                v.equals("מספר רכב") ||
                v.equals("שנת ייצור") ||
                v.equals("נפח") ||
                v.equals("מספר שילדה") ||
                v.equals("שילדה מספר") ||
                v.equalsIgnoreCase("VIN");
    }

    private String findNumberField(String text, String label) {

        if (text == null) return null;

        String[] lines = text.split("\n");

        for (int i = 0; i < lines.length; i++) {

            String line = lines[i].trim();

            if (!line.contains(label)) continue;

            String after = line.substring(
                    line.indexOf(label) + label.length()
            );

            Matcher m = Pattern.compile(
                    "(?<!\\d)(\\d{1,10})(?!\\d)"
            ).matcher(after);

            if (m.find()) {
                String value = m.group(1);

                if (isValidNumberForField(label, value)) {
                    return value;
                }
            }

            if (i + 1 < lines.length) {

                Matcher next = Pattern.compile(
                        "^\\s*(\\d{1,10})\\s*$"
                ).matcher(lines[i + 1]);

                if (next.find()) {

                    String value = next.group(1);

                    if (isValidNumberForField(label, value)) {
                        return value;
                    }
                }
            }
        }

        return null;
    }

    private boolean isValidNumberForField(String label, String value) {

        if (value == null || value.isEmpty()) return false;

        try {
            int n = Integer.parseInt(value);

            if (label.equals("שנת ייצור")) {
                return n >= 1900 && n <= 2100;
            }

            if (label.equals("מספר רכב")) {
                return value.length() >= 5 && value.length() <= 9;
            }

            if (label.equals("נפח")) {
                return value.length() >= 2 && value.length() <= 6;
            }

            return true;

        } catch (Exception e) {
            return false;
        }
    }

    private String findId(String text) {

        if (text == null) return null;

        String[] patterns = {

                "תעודת\\s*זהות\\s*[:：\\-]?\\s*(\\d{7,9})\\s*[-–]\\s*(\\d)",
                "תעודת\\s*זהות\\s*[:：\\-]?\\s*(\\d{8,9})",
                "ת\\.?\\s*ז\\.?\\s*[:：\\-]?\\s*(\\d{7,9})\\s*[-–]\\s*(\\d)",
                "\\b(\\d{7,9})\\s*[-–]\\s*(\\d)\\b"
        };

        for (String regex : patterns) {

            Matcher m = Pattern.compile(
                    regex,
                    Pattern.CASE_INSENSITIVE
            ).matcher(text);

            if (m.find()) {

                String first = m.group(1)
                        .replaceAll("\\s+", "");

                if (m.groupCount() >= 2 &&
                        m.group(2) != null) {

                    String second = m.group(2)
                            .replaceAll("\\s+", "");

                    if (!second.isEmpty()) {
                        return first + "-" + second;
                    }
                }

                return first;
            }
        }

        return null;
    }

    private String findVinField(String text) {

        if (text == null) return null;

        Pattern vin17 = Pattern.compile(
                "(?<![A-Za-z0-9])([A-HJ-NPR-Z0-9]{17})(?![A-Za-z0-9])",
                Pattern.CASE_INSENSITIVE
        );

        Matcher m = vin17.matcher(text);

        while (m.find()) {

            String vin = m.group(1).toUpperCase();

            if (vin.matches(".*[A-Z].*")) {
                return vin;
            }
        }

        Pattern afterLabel = Pattern.compile(
                "מספר\\s*שילדה\\s*[:：\\-]?\\s*" +
                "([A-HJ-NPR-Z0-9]{10,25})",
                Pattern.CASE_INSENSITIVE
        );

        m = afterLabel.matcher(text);

        if (m.find()) {
            return m.group(1).toUpperCase();
        }

        Pattern beforeLabel = Pattern.compile(
                "([A-HJ-NPR-Z0-9]{10,25})\\s*" +
                "שילדה\\s*מספר",
                Pattern.CASE_INSENSITIVE
        );

        m = beforeLabel.matcher(text);

        if (m.find()) {
            return m.group(1).toUpperCase();
        }

        String[] lines = text.split("\n");

        for (int i = 0; i < lines.length; i++) {

            String line = lines[i].trim();

            if (line.contains("שילדה")) {

                Matcher candidate = Pattern.compile(
                        "([A-HJ-NPR-Z0-9]{10,25})",
                        Pattern.CASE_INSENSITIVE
                ).matcher(line);

                if (candidate.find()) {
                    String vin = candidate.group(1).toUpperCase();

                    if (vin.matches(".*[A-Z].*")) {
                        return vin;
                    }
                }

                if (i + 1 < lines.length) {

                    candidate = Pattern.compile(
                            "^\\s*([A-HJ-NPR-Z0-9]{10,25})\\s*$",
                            Pattern.CASE_INSENSITIVE
                    ).matcher(lines[i + 1]);

                    if (candidate.find()) {
                        return candidate.group(1).toUpperCase();
                    }
                }
            }
        }

        return null;
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private void editTarget(
            Uri uri,
            File out,
            Fields f
    ) throws Exception {

        InputStream in =
                getContentResolver().openInputStream(uri);

        if (in == null) {
            throw new Exception("לא ניתן לפתוח את PDF היעד.");
        }

        PDDocument document = PDDocument.load(in);
        in.close();

        if (document.getNumberOfPages() == 0) {
            document.close();
            throw new Exception("PDF היעד ריק.");
        }

        PDPage page = document.getPage(0);

        PDType0Font font = PDType0Font.load(
                document,
                getAssets().open("DejaVuSans.ttf"),
                true
        );

        // מספר רישוי
        coverAndText(
                document, page, font,
                f.vehicle,
                340, 602, 396, 625,
                9, false
        );

        // מספר זהות
        coverAndText(
                document, page, font,
                f.id,
                265, 576, 335, 599,
                9, false
        );

        // שם בעל הפוליסה
        coverAndText(
                document, page, font,
                f.owner,
                465, 576, 568, 599,
                9, true
        );

        // כתובת בעל הפוליסה
        coverAndText(
                document, page, font,
                f.address,
                440, 552, 568, 574,
                8, true
        );

        // שנת ייצור
        coverAndText(
                document, page, font,
                f.year,
                84, 518, 120, 538,
                9, false
        );

        // נפח מנוע
        coverAndText(
                document, page, font,
                f.engine,
                185, 518, 235, 538,
                9, false
        );

        // שם היצרן והדגם
        coverAndText(
                document, page, font,
                f.make,
                245, 518, 330, 538,
                8, true
        );

        // מספר שילדה
        coverAndText(
                document, page, font,
                f.vin,
                465, 518, 560, 538,
                7, false
        );

        // השורה הגדולה בתחתית
        String bottomText =
                "ת\"ז " +
                f.id +
                " " +
                f.owner +
                " בלבד";

        coverAndText(
                document, page, font,
                bottomText,
                100, 445, 450, 487,
                22, true
        );

        document.save(out);
        document.close();
    }

    private void coverAndText(
            PDDocument document,
            PDPage page,
            PDType0Font font,
            String text,
            float x0,
            float y0,
            float x1,
            float y1,
            float size,
            boolean rtl
    ) throws Exception {

        if (isEmpty(text)) {
            return;
        }

        text = cleanFieldValue(text);

        if (text.isEmpty()) {
            return;
        }

        PDPageContentStream cs =
                new PDPageContentStream(
                        document,
                        page,
                        PDPageContentStream.AppendMode.APPEND,
                        true,
                        true
                );

        try {

            cs.setNonStrokingColor(Color.WHITE);
            cs.addRect(x0, y0, x1 - x0, y1 - y0);
            cs.fill();

            String visualText = text;

            if (rtl) {
                try {
                    Bidi bidi = new Bidi(
                            text,
                            Bidi.DIRECTION_RIGHT_TO_LEFT
                    );

                    visualText = bidi.writeReordered(
                            Bidi.DO_MIRRORING
                    );

                } catch (Exception ignored) {
                }
            }

            float availableWidth = x1 - x0;
            float drawSize = size;

            while (drawSize > 5f) {

                float width =
                        font.getStringWidth(visualText)
                                / 1000f
                                * drawSize;

                if (width <= availableWidth) {
                    break;
                }

                drawSize -= 0.5f;
            }

            float textWidth =
                    font.getStringWidth(visualText)
                            / 1000f
                            * drawSize;

            float tx;

            if (rtl) {
                tx = x1 - Math.min(textWidth, availableWidth);
            } else {
                tx = x0;
            }

            float ty =
                    y0 +
                    ((y1 - y0 - drawSize) / 2f) +
                    (drawSize * 0.72f);

            cs.beginText();
            cs.setNonStrokingColor(Color.BLACK);
            cs.setFont(font, drawSize);
            cs.newLineAtOffset(tx, ty);
            cs.showText(visualText);
            cs.endText();

        } finally {
            cs.close();
        }
    }

    private void share(File file) {

        try {

            Uri uri =
                    FileProvider.getUriForFile(
                            this,
                            getPackageName() +
                            ".provider",
                            file
                    );

            Intent intent =
                    new Intent(Intent.ACTION_SEND);

            intent.setType("application/pdf");

            intent.putExtra(
                    Intent.EXTRA_STREAM,
                    uri
            );

            intent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );

            startActivity(
                    Intent.createChooser(
                            intent,
                            "שליחת PDF"
                    )
            );

        } catch (Exception e) {

            status.setText(
                    "ה-PDF נוצר בהצלחה:\n" +
                    file.getAbsolutePath() +
                    "\n\nלא ניתן לפתוח את חלון השיתוף: " +
                    e.getMessage()
            );
        }
    }

    static class Fields {

        String owner;
        String id;
        String vehicle;
        String address;
        String year;
        String engine;
        String make;
        String vin;
    }


}
