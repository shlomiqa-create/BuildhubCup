package com.buildhubs.app;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PDFBoxResourceLoader.init(getApplicationContext());

        buildUi();
    }

    // ============================================================
    // UI
    // ============================================================

    private void buildUi() {

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 30, 28, 28);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("Build Hubs");
        title.setTextSize(30);
        title.setTextColor(Color.rgb(13, 91, 120));
        title.setGravity(TextView.TEXT_ALIGNMENT_CENTER);

        root.addView(
                title,
                new LinearLayout.LayoutParams(
                        -1,
                        70
                )
        );

        TextView sub = new TextView(this);
        sub.setText("העתקת שדות מ-PDF מקור אל טופס PDF");
        sub.setTextSize(18);
        sub.setGravity(TextView.TEXT_ALIGNMENT_CENTER);

        root.addView(
                sub,
                new LinearLayout.LayoutParams(
                        -1,
                        60
                )
        );

        sourceLabel = label("לא נבחר PDF מקור");
        root.addView(sourceLabel);

        Button sourceButton = button("1. בחירת PDF מקור");

        sourceButton.setOnClickListener(v -> {

            pickingSource = true;

            picker.launch(
                    new String[]{"application/pdf"}
            );
        });

        root.addView(sourceButton);

        targetLabel = label("לא נבחר PDF יעד");
        root.addView(targetLabel);

        Button targetButton = button("2. בחירת PDF יעד / טופס");

        targetButton.setOnClickListener(v -> {

            pickingSource = false;

            picker.launch(
                    new String[]{"application/pdf"}
            );
        });

        root.addView(targetButton);

        Space space = new Space(this);

        root.addView(
                space,
                new LinearLayout.LayoutParams(
                        1,
                        24
                )
        );

        Button createButton = button("3. יצירת PDF חדש");

        createButton.setOnClickListener(v -> create());

        root.addView(createButton);

        status = label(
                "הערכים שיועתקו:\n" +
                "בעלים, ת.ז., מספר רכב, מען, שנת ייצור, נפח, תוצר ומספר שילדה."
        );

        root.addView(status);

        setContentView(root);

        picker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {

                    if (uri == null) {
                        return;
                    }

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
                                getDisplayName(uri)
                        );

                    } else {

                        targetUri = uri;

                        targetLabel.setText(
                                "יעד: " +
                                getDisplayName(uri)
                        );
                    }
                }
        );
    }

    private TextView label(String text) {

        TextView view = new TextView(this);

        view.setText(text);
        view.setTextSize(16);
        view.setPadding(4, 12, 4, 12);

        return view;
    }

    private Button button(String text) {

        Button button = new Button(this);

        button.setText(text);
        button.setTextSize(16);

        return button;
    }

    private String getDisplayName(Uri uri) {

        if (uri == null) {
            return "PDF";
        }

        String name = uri.getLastPathSegment();

        if (name == null || name.trim().isEmpty()) {
            return "PDF";
        }

        return name;
    }

    // ============================================================
    // CREATE
    // ============================================================

    private void create() {

        if (sourceUri == null) {

            status.setText(
                    "יש לבחור PDF מקור."
            );

            return;
        }

        if (targetUri == null) {

            status.setText(
                    "יש לבחור PDF יעד."
            );

            return;
        }

        status.setText(
                "קורא את PDF המקור..."
        );

        try {

            Fields fields = readFields(sourceUri);

            // מציגים למשתמש מה זוהה לפני יצירת הקובץ
            status.setText(
                    "השדות זוהו בהצלחה:\n\n" +
                    "בעלים: " + fields.owner + "\n" +
                    "ת.ז.: " + fields.id + "\n" +
                    "מספר רכב: " + fields.vehicle + "\n" +
                    "מען: " + fields.address + "\n" +
                    "שנת ייצור: " + fields.year + "\n" +
                    "נפח: " + fields.engine + "\n" +
                    "תוצר: " + fields.make + "\n" +
                    "שילדה: " + fields.vin + "\n\n" +
                    "יוצר PDF חדש..."
            );

            File documents =
                    getExternalFilesDir(
                            Environment.DIRECTORY_DOCUMENTS
                    );

            if (documents == null) {

                throw new Exception(
                        "לא ניתן ליצור תיקיית מסמכים."
                );
            }

            if (!documents.exists()) {

                if (!documents.mkdirs() &&
                        !documents.exists()) {

                    throw new Exception(
                            "לא ניתן ליצור תיקיית מסמכים."
                    );
                }
            }

            File outputFile =
                    new File(
                            documents,
                            "BuildHubs_" +
                            System.currentTimeMillis() +
                            ".pdf"
                    );

            editTarget(
                    targetUri,
                    outputFile,
                    fields
            );

            status.setText(
                    "ה-PDF נוצר בהצלחה."
            );

            share(outputFile);

        } catch (Exception e) {

            String message = e.getMessage();

            if (message == null ||
                    message.trim().isEmpty()) {

                message =
                        e.getClass()
                                .getSimpleName();
            }

            status.setText(
                    "שגיאה:\n" +
                    message
            );
        }
    }

    // ============================================================
    // READ SOURCE PDF
    // ============================================================

    private Fields readFields(Uri uri)
            throws Exception {

        String text;

        try (InputStream input =
                     getContentResolver()
                             .openInputStream(uri)) {

            if (input == null) {

                throw new Exception(
                        "לא ניתן לפתוח את PDF המקור."
                );
            }

            try (PDDocument document =
                         PDDocument.load(input)) {

                if (document.getNumberOfPages() == 0) {

                    throw new Exception(
                            "PDF המקור ריק."
                    );
                }

                PDFTextStripper stripper =
                        new PDFTextStripper();

                /*
                 * ניסיון ראשון:
                 * הסדר הגיאומטרי של הטקסט.
                 */
                stripper.setSortByPosition(true);

                text =
                        stripper.getText(document);

                text =
                        normalizeText(text);

                /*
                 * אם לא קיבלנו מספיק שדות,
                 * מנסים גם סדר טקסט רגיל.
                 */
                if (!looksLikeVehicleDocument(text)) {

                    PDFTextStripper fallback =
                            new PDFTextStripper();

                    fallback.setSortByPosition(false);

                    String fallbackText =
                            fallback.getText(document);

                    fallbackText =
                            normalizeText(
                                    fallbackText
                            );

                    if (vehicleTextScore(
                            fallbackText
                    ) > vehicleTextScore(text)) {

                        text = fallbackText;
                    }
                }
            }
        }

        if (text.trim().isEmpty()) {

            throw new Exception(
                    "לא נמצא טקסט ב-PDF המקור."
            );
        }

        Fields fields = new Fields();

        /*
         * כל שדה נבדק בשני הכיוונים:
         *
         * ערך -> כותרת
         *
         * וגם:
         *
         * כותרת -> ערך
         */

        fields.owner =
                findTextField(
                        text,
                        new String[]{
                                "בעלים",
                                "שם בעלים",
                                "בעל הרכב"
                        }
                );

        fields.address =
                findTextField(
                        text,
                        new String[]{
                                "מען",
                                "כתובת",
                                "כתובת בעלים"
                        }
                );

        fields.make =
                findTextField(
                        text,
                        new String[]{
                                "תוצר",
                                "תוצרת",
                                "יצרן"
                        }
                );

        fields.vehicle =
                findVehicleNumber(text);

        fields.year =
                findYear(text);

        fields.engine =
                findEngineVolume(text);

        fields.id =
                findId(text);

        fields.vin =
                findVin(text);

        StringBuilder missing =
                new StringBuilder();

        if (isEmpty(fields.owner)) {
            missing.append("בעלים, ");
        }

        if (isEmpty(fields.id)) {
            missing.append("תעודת זהות, ");
        }

        if (isEmpty(fields.vehicle)) {
            missing.append("מספר רכב, ");
        }

        if (isEmpty(fields.address)) {
            missing.append("מען, ");
        }

        if (isEmpty(fields.year)) {
            missing.append("שנת ייצור, ");
        }

        if (isEmpty(fields.engine)) {
            missing.append("נפח, ");
        }

        if (isEmpty(fields.make)) {
            missing.append("תוצר, ");
        }

        if (isEmpty(fields.vin)) {
            missing.append("מספר שילדה, ");
        }

        if (missing.length() > 0) {

            String result =
                    missing.toString();

            if (result.endsWith(", ")) {

                result =
                        result.substring(
                                0,
                                result.length() - 2
                        );
            }

            throw new Exception(
                    "לא הצלחתי לזהות: " +
                    result +
                    "\n\n" +
                    "הטקסט שנקרא מה-PDF אינו במבנה שהאפליקציה ציפתה לו."
            );
        }

        return fields;
    }

    // ============================================================
    // TEXT NORMALIZATION
    // ============================================================

    private String normalizeText(String text) {

        if (text == null) {
            return "";
        }

        text =
                text
                        .replace("\u200E", "")
                        .replace("\u200F", "")
                        .replace("\u202A", "")
                        .replace("\u202B", "")
                        .replace("\u202C", "")
                        .replace("\u202D", "")
                        .replace("\u202E", "")
                        .replace("\u2066", "")
                        .replace("\u2067", "")
                        .replace("\u2068", "")
                        .replace("\u2069", "")
                        .replace('\u00A0', ' ')
                        .replace('\u0000', ' ');

        text =
                normalizeDigits(text);

        text =
                text.replace("\r\n", "\n")
                        .replace('\r', '\n');

        text =
                text.replaceAll(
                        "[ \\t]+",
                        " "
                );

        text =
                text.replaceAll(
                        " *\\n *",
                        "\n"
                );

        text =
                text.replaceAll(
                        "\\n{3,}",
                        "\n\n"
                );

        return text.trim();
    }

    private String normalizeDigits(String text) {

        if (text == null) {
            return "";
        }

        StringBuilder result =
                new StringBuilder();

        for (int i = 0;
             i < text.length();
             i++) {

            char c = text.charAt(i);

            if (c >= '\u0660' &&
                    c <= '\u0669') {

                result.append(
                        (char) (
                                '0' +
                                (c - '\u0660')
                        )
                );

            } else if (
                    c >= '\u06F0' &&
                    c <= '\u06F9'
            ) {

                result.append(
                        (char) (
                                '0' +
                                (c - '\u06F0')
                        )
                );

            } else {

                result.append(c);
            }
        }

        return result.toString();
    }

    // ============================================================
    // VEHICLE DOCUMENT DETECTION
    // ============================================================

    private boolean looksLikeVehicleDocument(
            String text
    ) {

        return vehicleTextScore(text) >= 3;
    }

    private int vehicleTextScore(
            String text
    ) {

        if (text == null) {
            return 0;
        }

        int score = 0;

        String[] labels = {

                "בעלים",
                "מען",
                "תוצר",
                "יצרן",
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
                score++;
            }
        }

        return score;
    }

    // ============================================================
    // TEXT FIELD
    // ============================================================

    private String findTextField(
            String text,
            String[] labels
    ) {

        if (text == null ||
                text.trim().isEmpty()) {

            return null;
        }

        String[] lines =
                text.split("\\n");

        /*
         * בדיקה שורה-שורה.
         */
        for (int i = 0;
             i < lines.length;
             i++) {

            String line =
                    lines[i].trim();

            if (line.isEmpty()) {
                continue;
            }

            for (String label : labels) {

                int position =
                        line.indexOf(label);

                if (position < 0) {
                    continue;
                }

                /*
                 * כותרת -> ערך
                 */
                String after =
                        line.substring(
                                position +
                                label.length()
                        );

                String value =
                        cleanFieldValue(after);

                if (isValidTextValue(
                        value,
                        labels
                )) {

                    return value;
                }

                /*
                 * ערך -> כותרת
                 */
                String before =
                        line.substring(
                                0,
                                position
                        );

                value =
                        cleanFieldValue(before);

                if (isValidTextValue(
                        value,
                        labels
                )) {

                    return value;
                }

                /*
                 * כותרת בשורה אחת,
                 * ערך בשורה שאחריה.
                 */
                if (i + 1 <
                        lines.length) {

                    value =
                            cleanFieldValue(
                                    lines[i + 1]
                            );

                    if (isValidTextValue(
                            value,
                            labels
                    )) {

                        return value;
                    }
                }

                /*
                 * ערך בשורה הקודמת,
                 * כותרת בשורה הנוכחית.
                 */
                if (i > 0) {

                    value =
                            cleanFieldValue(
                                    lines[i - 1]
                            );

                    if (isValidTextValue(
                            value,
                            labels
                    )) {

                        return value;
                    }
                }
            }
        }

        /*
         * ניסיון נוסף על כל הטקסט.
         */

        for (String label : labels) {

            Pattern after =
                    Pattern.compile(
                            Pattern.quote(label) +
                            "\\s*[:：\\-–—]?\\s*" +
                            "([^\\r\\n]+)"
                    );

            Matcher matcher =
                    after.matcher(text);

            if (matcher.find()) {

                String value =
                        cleanFieldValue(
                                matcher.group(1)
                        );

                if (isValidTextValue(
                        value,
                        labels
                )) {

                    return value;
                }
            }

            Pattern before =
                    Pattern.compile(
                            "([^\\r\\n]+?)" +
                            "\\s*" +
                            Pattern.quote(label)
                    );

            matcher =
                    before.matcher(text);

            if (matcher.find()) {

                String value =
                        cleanFieldValue(
                                matcher.group(1)
                        );

                if (isValidTextValue(
                        value,
                        labels
                )) {

                    return value;
                }
            }
        }

        return null;
    }

    private String cleanFieldValue(
            String value
    ) {

        if (value == null) {
            return "";
        }

        value =
                normalizeText(value)
                        .replace("\n", " ")
                        .trim();

        value =
                value.replaceAll(
                        "^[\\s:：\\-–—]+",
                        ""
                );

        value =
                value.replaceAll(
                        "[\\s:：\\-–—]+$",
                        ""
                );

        return value.trim();
    }

    private boolean isValidTextValue(
            String value,
            String[] labels
    ) {

        if (value == null ||
                value.trim().isEmpty()) {

            return false;
        }

        String v =
                value.trim();

        /*
         * אם זה רק כותרת - לא ערך.
         */
        for (String label : labels) {

            if (v.equals(label)) {
                return false;
            }
        }

        /*
         * לא לקחת שורה שמורכבת מכותרות אחרות.
         */
        String[] allLabels = {

                "בעלים",
                "מען",
                "תוצר",
                "תוצרת",
                "יצרן",
                "תעודת זהות",
                "ת.ז",
                "מספר רכב",
                "שנת ייצור",
                "שנת יצור",
                "נפח",
                "מספר שילדה",
                "שילדה מספר",
                "VIN"
        };

        for (String label : allLabels) {

            if (v.equals(label)) {
                return false;
            }
        }

        return true;
    }

    // ============================================================
    // VEHICLE NUMBER
    // ============================================================

    private String findVehicleNumber(
            String text
    ) {

        String[] labels = {

                "מספר רכב",
                "מספר רישוי",
                "מס' רכב",
                "מס רכב",
                "רישוי"
        };

        String result =
                findNumberNearLabels(
                        text,
                        labels,
                        "vehicle"
                );

        if (isValidVehicleNumber(result)) {
            return result;
        }

        /*
         * fallback:
         * מספר רכב ישראלי נפוץ הוא 7-8 ספרות.
         *
         * אנחנו לא משתמשים בו לפני חיפוש
         * לפי הכותרת כדי למנוע בלבול עם ת"ז.
         */
        Pattern pattern =
                Pattern.compile(
                        "(?<!\\d)" +
                        "(\\d{7,8})" +
                        "(?!\\d)"
                );

        Matcher matcher =
                pattern.matcher(text);

        while (matcher.find()) {

            String number =
                    matcher.group(1);

            if (isValidVehicleNumber(
                    number
            )) {

                return number;
            }
        }

        return null;
    }

    private boolean isValidVehicleNumber(
            String value
    ) {

        if (value == null) {
            return false;
        }

        return value.matches(
                "\\d{6,8}"
        );
    }

    // ============================================================
    // YEAR
    // ============================================================

    private String findYear(
            String text
    ) {

        String[] labels = {

                "שנת ייצור",
                "שנת יצור",
                "שנת דגם",
                "שנת רכב"
        };

        String result =
                findNumberNearLabels(
                        text,
                        labels,
                        "year"
                );

        if (isValidYear(result)) {
            return result;
        }

        Pattern pattern =
                Pattern.compile(
                        "(?<!\\d)" +
                        "(19\\d{2}|20\\d{2}|21\\d{2})" +
                        "(?!\\d)"
                );

        Matcher matcher =
                pattern.matcher(text);

        while (matcher.find()) {

            String year =
                    matcher.group(1);

            if (isValidYear(year)) {
                return year;
            }
        }

        return null;
    }

    private boolean isValidYear(
            String value
    ) {

        if (value == null ||
                !value.matches("\\d{4}")) {

            return false;
        }

        try {

            int year =
                    Integer.parseInt(value);

            return year >= 1900 &&
                    year <= 2100;

        } catch (Exception e) {

            return false;
        }
    }

    // ============================================================
    // ENGINE
    // ============================================================

    private String findEngineVolume(
            String text
    ) {

        String[] labels = {

                "נפח מנוע",
                "נפח מנוע סמ״ק",
                "נפח מנוע סמ\"ק",
                "נפח מנוע סמ''ק",
                "נפח"
        };

        String result =
                findNumberNearLabels(
                        text,
                        labels,
                        "engine"
                );

        if (isValidEngineVolume(result)) {
            return result;
        }

        return null;
    }

    private boolean isValidEngineVolume(
            String value
    ) {

        if (value == null ||
                !value.matches("\\d{2,6}")) {

            return false;
        }

        try {

            int volume =
                    Integer.parseInt(value);

            return volume >= 50 &&
                    volume <= 15000;

        } catch (Exception e) {

            return false;
        }
    }

    // ============================================================
    // NUMBER NEAR LABEL
    // ============================================================

    private String findNumberNearLabels(
            String text,
            String[] labels,
            String type
    ) {

        if (text == null ||
                text.trim().isEmpty()) {

            return null;
        }

        String[] lines =
                text.split("\\n");

        /*
         * קודם כל - שורה מדויקת.
         */
        for (int i = 0;
             i < lines.length;
             i++) {

            String line =
                    lines[i].trim();

            if (line.isEmpty()) {
                continue;
            }

            for (String label : labels) {

                int position =
                        line.indexOf(label);

                if (position < 0) {
                    continue;
                }

                /*
                 * LABEL -> NUMBER
                 */
                String after =
                        line.substring(
                                position +
                                label.length()
                        );

                String number =
                        firstNumber(after);

                if (isValidNumberByType(
                        number,
                        type
                )) {

                    return number;
                }

                /*
                 * NUMBER -> LABEL
                 */
                String before =
                        line.substring(
                                0,
                                position
                        );

                number =
                        lastNumber(before);

                if (isValidNumberByType(
                        number,
                        type
                )) {

                    return number;
                }

                /*
                 * LABEL בשורה אחת,
                 * NUMBER בשורה הבאה.
                 */
                if (i + 1 <
                        lines.length) {

                    number =
                            firstNumber(
                                    lines[i + 1]
                            );

                    if (isValidNumberByType(
                            number,
                            type
                    )) {

                        return number;
                    }
                }

                /*
                 * NUMBER בשורה הקודמת,
                 * LABEL בשורה הנוכחית.
                 */
                if (i > 0) {

                    number =
                            lastNumber(
                                    lines[i - 1]
                            );

                    if (isValidNumberByType(
                            number,
                            type
                    )) {

                        return number;
                    }
                }
            }
        }

        /*
         * fallback:
         * חיפוש קרוב לכותרת.
         */
        for (String label : labels) {

            Pattern after =
                    Pattern.compile(
                            Pattern.quote(label) +
                            "[^\\n]{0,40}?" +
                            "(\\d{1,10})"
                    );

            Matcher matcher =
                    after.matcher(text);

            while (matcher.find()) {

                String number =
                        matcher.group(1);

                if (isValidNumberByType(
                        number,
                        type
                )) {

                    return number;
                }
            }

            Pattern before =
                    Pattern.compile(
                            "(\\d{1,10})" +
                            "[^\\n]{0,40}?" +
                            Pattern.quote(label)
                    );

            matcher =
                    before.matcher(text);

            while (matcher.find()) {

                String number =
                        matcher.group(1);

                if (isValidNumberByType(
                        number,
                        type
                )) {

                    return number;
                }
            }
        }

        return null;
    }

    private String firstNumber(
            String text
    ) {

        if (text == null) {
            return null;
        }

        Matcher matcher =
                Pattern.compile(
                        "(?<!\\d)" +
                        "(\\d{1,10})" +
                        "(?!\\d)"
                ).matcher(text);

        if (matcher.find()) {

            return matcher.group(1);
        }

        return null;
    }

    private String lastNumber(
            String text
    ) {

        if (text == null) {
            return null;
        }

        Matcher matcher =
                Pattern.compile(
                        "(?<!\\d)" +
                        "(\\d{1,10})" +
                        "(?!\\d)"
                ).matcher(text);

        String result = null;

        while (matcher.find()) {

            result =
                    matcher.group(1);
        }

        return result;
    }

    private boolean isValidNumberByType(
            String value,
            String type
    ) {

        if ("vehicle".equals(type)) {

            return isValidVehicleNumber(
                    value
            );
        }

        if ("year".equals(type)) {

            return isValidYear(value);
        }

        if ("engine".equals(type)) {

            return isValidEngineVolume(
                    value
            );
        }

        return false;
    }

    // ============================================================
    // ID
    // ============================================================

    private String findId(
            String text
    ) {

        if (text == null) {
            return null;
        }

        String[] labels = {

                "תעודת זהות",
                "תעודת זיהוי",
                "ת.ז",
                "ת. ז",
                "תז"
        };

        /*
         * כותרת -> ת"ז
         */
        for (String label : labels) {

            Pattern after =
                    Pattern.compile(
                            Pattern.quote(label) +
                            "[^\\n]{0,25}?" +
                            "(\\d{7,9}" +
                            "\\s*[-–]" +
                            "\\s*\\d)"
                    );

            Matcher matcher =
                    after.matcher(text);

            if (matcher.find()) {

                String id =
                        normalizeId(
                                matcher.group(1)
                        );

                if (isValidId(id)) {
                    return id;
                }
            }

            /*
             * ת"ז -> כותרת
             */
            Pattern before =
                    Pattern.compile(
                            "(\\d{7,9}" +
                            "\\s*[-–]" +
                            "\\s*\\d)" +
                            "[^\\n]{0,25}?" +
                            Pattern.quote(label)
                    );

            matcher =
                    before.matcher(text);

            if (matcher.find()) {

                String id =
                        normalizeId(
                                matcher.group(1)
                        );

                if (isValidId(id)) {
                    return id;
                }
            }
        }

        /*
         * fallback כללי לתבנית ת"ז.
         */
        Pattern general =
                Pattern.compile(
                        "(?<!\\d)" +
                        "(\\d{7,9})" +
                        "\\s*[-–]\\s*" +
                        "(\\d)" +
                        "(?!\\d)"
                );

        Matcher matcher =
                general.matcher(text);

        if (matcher.find()) {

            String id =
                    matcher.group(1) +
                    "-" +
                    matcher.group(2);

            if (isValidId(id)) {
                return id;
            }
        }

        return null;
    }

    private String normalizeId(
            String id
    ) {

        if (id == null) {
            return null;
        }

        return id
                .replaceAll("\\s+", "")
                .replace("–", "-");
    }

    private boolean isValidId(
            String id
    ) {

        if (id == null) {
            return false;
        }

        return id.matches(
                "\\d{7,9}(-\\d)?"
        );
    }

    // ============================================================
    // VIN / CHASSIS
    // ============================================================

    private String findVin(
            String text
    ) {

        if (text == null) {
            return null;
        }

        /*
         * VIN רגיל של 17 תווים.
         */
        Pattern exactVin =
                Pattern.compile(
                        "(?<![A-Za-z0-9])" +
                        "([A-HJ-NPR-Z0-9]{17})" +
                        "(?![A-Za-z0-9])",
                        Pattern.CASE_INSENSITIVE
                );

        Matcher matcher =
                exactVin.matcher(text);

        while (matcher.find()) {

            String vin =
                    matcher.group(1)
                            .toUpperCase();

            /*
             * VIN אמיתי ברוב המקרים
             * כולל אותיות.
             */
            if (vin.matches(
                    ".*[A-Z].*"
            )) {

                return vin;
            }
        }

        /*
         * VALUE -> שילדה מספר
         *
         * זה בדיוק המבנה שנמצא ב-PDF שלך.
         */
        Pattern before =
                Pattern.compile(
                        "([A-HJ-NPR-Z0-9]{10,25})" +
                        "\\s*" +
                        "שילדה" +
                        "\\s*" +
                        "מספר",
                        Pattern.CASE_INSENSITIVE
                );

        matcher =
                before.matcher(text);

        if (matcher.find()) {

            return matcher
                    .group(1)
                    .toUpperCase();
        }

        /*
         * מספר שילדה -> VALUE
         */
        Pattern after =
                Pattern.compile(
                        "מספר" +
                        "\\s*" +
                        "שילדה" +
                        "\\s*" +
                        "[:：\\-–—]?" +
                        "\\s*" +
                        "([A-HJ-NPR-Z0-9]{10,25})",
                        Pattern.CASE_INSENSITIVE
                );

        matcher =
                after.matcher(text);

        if (matcher.find()) {

            return matcher
                    .group(1)
                    .toUpperCase();
        }

        /*
         * בדיקת שורות.
         */
        String[] lines =
                text.split("\\n");

        for (int i = 0;
             i < lines.length;
             i++) {

            String line =
                    lines[i].trim();

            if (!line.contains("שילדה")) {
                continue;
            }

            Matcher candidate =
                    Pattern.compile(
                            "([A-HJ-NPR-Z0-9]{10,25})",
                            Pattern.CASE_INSENSITIVE
                    ).matcher(line);

            if (candidate.find()) {

                String vin =
                        candidate
                                .group(1)
                                .toUpperCase();

                if (vin.matches(
                        ".*[A-Z].*"
                )) {

                    return vin;
                }
            }

            /*
             * VIN בשורה שאחרי "שילדה".
             */
            if (i + 1 <
                    lines.length) {

                candidate =
                        Pattern.compile(
                                "^\\s*" +
                                "([A-HJ-NPR-Z0-9]{10,25})" +
                                "\\s*$",
                                Pattern.CASE_INSENSITIVE
                        ).matcher(
                                lines[i + 1]
                        );

                if (candidate.find()) {

                    return candidate
                            .group(1)
                            .toUpperCase();
                }
            }

            /*
             * VIN בשורה לפני "שילדה".
             */
            if (i > 0) {

                candidate =
                        Pattern.compile(
                                "^\\s*" +
                                "([A-HJ-NPR-Z0-9]{10,25})" +
                                "\\s*$",
                                Pattern.CASE_INSENSITIVE
                        ).matcher(
                                lines[i - 1]
                        );

                if (candidate.find()) {

                    return candidate
                            .group(1)
                            .toUpperCase();
                }
            }
        }

        return null;
    }

    // ============================================================
    // TARGET PDF
    // ============================================================

    private void editTarget(
            Uri uri,
            File output,
            Fields fields
    ) throws Exception {

        InputStream input =
                getContentResolver()
                        .openInputStream(uri);

        if (input == null) {

            throw new Exception(
                    "לא ניתן לפתוח את PDF היעד."
            );
        }

        PDDocument document;

        try {

            document =
                    PDDocument.load(input);

        } finally {

            input.close();
        }

        try {

            if (document.getNumberOfPages() == 0) {

                throw new Exception(
                        "PDF היעד ריק."
                );
            }

            PDPage page =
                    document.getPage(0);

            /*
             * חובה שהקובץ יהיה קיים:
             *
             * app/src/main/assets/DejaVuSans.ttf
             */
            InputStream fontInput =
                    getAssets()
                            .open("DejaVuSans.ttf");

            PDType0Font font;

            try {

                font =
                        PDType0Font.load(
                                document,
                                fontInput,
                                true
                        );

            } finally {

                fontInput.close();
            }

            /*
             * מספר רכב
             */
            coverAndText(
                    document,
                    page,
                    font,
                    fields.vehicle,
                    340,
                    602,
                    396,
                    625,
                    9,
                    false
            );

            /*
             * ת"ז
             */
            coverAndText(
                    document,
                    page,
                    font,
                    fields.id,
                    265,
                    576,
                    335,
                    599,
                    9,
                    false
            );

            /*
             * בעלים
             */
            coverAndText(
                    document,
                    page,
                    font,
                    fields.owner,
                    465,
                    576,
                    568,
                    599,
                    9,
                    true
            );

            /*
             * כתובת
             */
            coverAndText(
                    document,
                    page,
                    font,
                    fields.address,
                    440,
                    552,
                    568,
                    574,
                    8,
                    true
            );

            /*
             * שנת ייצור
             */
            coverAndText(
                    document,
                    page,
                    font,
                    fields.year,
                    84,
                    518,
                    120,
                    538,
                    9,
                    false
            );

            /*
             * נפח
             */
            coverAndText(
                    document,
                    page,
                    font,
                    fields.engine,
                    185,
                    518,
                    235,
                    538,
                    9,
                    false
            );

            /*
             * תוצר
             */
            coverAndText(
                    document,
                    page,
                    font,
                    fields.make,
                    245,
                    518,
                    330,
                    538,
                    8,
                    true
            );

            /*
             * מספר שילדה
             */
            coverAndText(
                    document,
                    page,
                    font,
                    fields.vin,
                    465,
                    518,
                    560,
                    538,
                    7,
                    false
            );

            /*
             * שורה תחתונה
             */
            String bottomText =
                    "ת\"ז " +
                    fields.id +
                    " " +
                    fields.owner +
                    " בלבד";

            coverAndText(
                    document,
                    page,
                    font,
                    bottomText,
                    100,
                    445,
                    450,
                    487,
                    22,
                    true
            );

            document.save(output);

        } finally {

            document.close();
        }
    }

    // ============================================================
    // WRITE TEXT
    // ============================================================

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

        if (text == null ||
                text.trim().isEmpty()) {

            return;
        }

        text =
                cleanFieldValue(text);

        if (text.isEmpty()) {
            return;
        }

        String visualText = text;

        if (rtl) {

            try {

                Bidi bidi =
                        new Bidi(
                                text,
                                Bidi.DIRECTION_RIGHT_TO_LEFT
                        );

                visualText =
                        bidi.writeReordered(
                                Bidi.DO_MIRRORING
                        );

            } catch (Exception ignored) {
            }
        }

        float availableWidth =
                x1 - x0;

        float drawSize =
                size;

        /*
         * הקטנת הטקסט אם הוא ארוך.
         */
        while (drawSize > 5f) {

            float width =
                    font.getStringWidth(
                            visualText
                    ) /
                    1000f *
                    drawSize;

            if (width <=
                    availableWidth) {

                break;
            }

            drawSize -= 0.5f;
        }

        PDPageContentStream stream =
                new PDPageContentStream(
                        document,
                        page,
                        PDPageContentStream.AppendMode.APPEND,
                        true,
                        true
                );

        try {

            /*
             * מכסה את הטקסט הקיים.
             */
            stream.setNonStrokingColor(
                    Color.WHITE
            );

            stream.addRect(
                    x0,
                    y0,
                    x1 - x0,
                    y1 - y0
            );

            stream.fill();

            /*
             * רוחב הטקסט.
             */
            float textWidth =
                    font.getStringWidth(
                            visualText
                    ) /
                    1000f *
                    drawSize;

            float tx;

            if (rtl) {

                tx =
                        x1 -
                        Math.min(
                                textWidth,
                                availableWidth
                        );

            } else {

                tx = x0;
            }

            float ty =
                    y0 +
                    ((y1 - y0 - drawSize)
                            / 2f) +
                    (drawSize * 0.72f);

            stream.beginText();

            stream.setNonStrokingColor(
                    Color.BLACK
            );

            stream.setFont(
                    font,
                    drawSize
            );

            stream.newLineAtOffset(
                    tx,
                    ty
            );

            stream.showText(
                    visualText
            );

            stream.endText();

        } finally {

            stream.close();
        }
    }

    // ============================================================
    // SHARE
    // ============================================================

    private void share(
            File file
    ) {

        try {

            Uri uri =
                    FileProvider.getUriForFile(
                            this,
                            getPackageName() +
                            ".provider",
                            file
                    );

            Intent intent =
                    new Intent(
                            Intent.ACTION_SEND
                    );

            intent.setType(
                    "application/pdf"
            );

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
                    "ה-PDF נוצר בהצלחה.\n\n" +
                    "לא ניתן לפתוח את חלון השיתוף:\n" +
                    e.getMessage()
            );
        }
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private boolean isEmpty(
            String value
    ) {

        return value == null ||
                value.trim().isEmpty();
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
