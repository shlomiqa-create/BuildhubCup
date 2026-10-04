package com.buildhubs.app;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Gravity;
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
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "BUILD_HUBS_PDF";

    private static final String[] OWNER_LABELS = {"שם הבעלים", "בעלים", "םילעבה םש", "םילעב"};
    private static final String[] ID_LABELS = {"מס׳ זהות / ח״פ", "מס זהות", "תעודת זהות", "זהות תעודת", "ת.ז.", "ת.ז", "תז", "זהות"};
    private static final String[] VEHICLE_LABELS = {"מס׳ רישוי", "מס' רישוי", "מס רישוי", "מספר רכב", "רכב מספר", "רישוי'מס", "רפסמ בכר", "בכר רפסמ"};
    private static final String[] ADDRESS_LABELS = {"מען", "כתובת", "ןעמ", "תבותכ"};
    private static final String[] YEAR_LABELS = {"שנת ייצור", "ייצור שנת", "תנש רוציי", "רוציי תנש"};
    private static final String[] ENGINE_LABELS = {"נפח מנוע", "נפח", "חפנ"};
    private static final String[] MAKE_LABELS = {"יצרן / דגם", "יצרן/דגם", "יצרן", "דגם", "תוצר", "דגם/ יצרן", "רצות"};
    private static final String[] VIN_LABELS = {"מס׳ שילדה", "מס' שילדה", "מס שילדה", "מספר שילדה", "שילדה מספר", "VIN", "שילדה'מס", "רפסמ הדליש", "הדליש רפסמ"};

    private static final String[][] ALL_LABEL_GROUPS = {
            OWNER_LABELS, ID_LABELS, VEHICLE_LABELS, ADDRESS_LABELS,
            YEAR_LABELS, ENGINE_LABELS, MAKE_LABELS, VIN_LABELS
    };

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
        registerPicker();
        buildUi();
    }

    private void registerPicker() {
        picker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }

            if (pickingSource) {
                sourceUri = uri;
                sourceLabel.setText("מקור: " + displayName(uri));
            } else {
                targetUri = uri;
                targetLabel.setText("יעד: " + displayName(uri));
            }
        });
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
        root.addView(title, new LinearLayout.LayoutParams(-1, 70));

        TextView sub = new TextView(this);
        sub.setText("העתקת שדות מ-PDF מקור אל טופס PDF");
        sub.setTextSize(18);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub, new LinearLayout.LayoutParams(-1, 60));

        sourceLabel = label("לא נבחר PDF מקור");
        root.addView(sourceLabel);
        Button sourceButton = button("1. בחירת PDF מקור");
        sourceButton.setOnClickListener(v -> {
            pickingSource = true;
            picker.launch(new String[]{"application/pdf"});
        });
        root.addView(sourceButton);

        targetLabel = label("לא נבחר PDF יעד");
        root.addView(targetLabel);
        Button targetButton = button("2. בחירת PDF יעד / טופס");
        targetButton.setOnClickListener(v -> {
            pickingSource = false;
            picker.launch(new String[]{"application/pdf"});
        });
        root.addView(targetButton);

        root.addView(new Space(this), new LinearLayout.LayoutParams(1, 24));
        Button createButton = button("3. יצירת PDF חדש");
        createButton.setOnClickListener(v -> create());
        root.addView(createButton);

        status = label("הערכים שיועתקו: בעלים, ת.ז., מספר רכב, מען, שנת ייצור, נפח, תוצר ומספר שילדה.");
        root.addView(status);
        setContentView(root);
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

    private String displayName(Uri uri) {
        String name = uri.getLastPathSegment();
        return name == null ? uri.toString() : name;
    }

    private void create() {
        if (sourceUri == null || targetUri == null) {
            status.setText("יש לבחור גם PDF מקור וגם PDF יעד.");
            return;
        }

        status.setText("קורא את PDF המקור...");
        try {
            Fields fields = readFields(sourceUri);
            File directory = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (directory == null) directory = getFilesDir();
            File output = new File(directory, "BuildHubs_" + System.currentTimeMillis() + ".pdf");
            editTarget(targetUri, output, fields);
            status.setText("נוצר PDF חדש בהצלחה:\n" + output.getAbsolutePath());
            share(output);
        } catch (Exception e) {
            Log.e(TAG, "Operation failed", e);
            status.setText("שגיאה: " + (isEmpty(e.getMessage()) ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private Fields readFields(Uri uri) throws Exception {
        String sortedText;
        String unsortedText;

        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new Exception("לא ניתן לפתוח את PDF המקור.");
            try (PDDocument document = PDDocument.load(input)) {
                if (document.getNumberOfPages() == 0) throw new Exception("PDF המקור ריק.");

                PDFTextStripper sortedStripper = new PDFTextStripper();
                sortedStripper.setSortByPosition(true);
                sortedText = normalizeText(sortedStripper.getText(document));

                PDFTextStripper unsortedStripper = new PDFTextStripper();
                unsortedStripper.setSortByPosition(false);
                unsortedText = normalizeText(unsortedStripper.getText(document));
            }
        }

        Log.d(TAG, "===== SORTED TEXT =====\n" + sortedText);
        Log.d(TAG, "===== UNSORTED TEXT =====\n" + unsortedText);
        saveDebugText(sortedText, unsortedText);

        Fields fields = mergeFields(extractFields(sortedText), extractFields(unsortedText));
        Log.d(TAG, fields.toDebugString());

        List<String> missing = new ArrayList<>();
        if (isEmpty(fields.owner)) missing.add("בעלים");
        if (isEmpty(fields.id)) missing.add("תעודת זהות");
        if (isEmpty(fields.vehicle)) missing.add("מספר רכב");
        if (isEmpty(fields.address)) missing.add("מען");
        if (isEmpty(fields.year)) missing.add("שנת ייצור");
        if (isEmpty(fields.engine)) missing.add("נפח");
        if (isEmpty(fields.make)) missing.add("תוצר");
        if (isEmpty(fields.vin)) missing.add("מספר שילדה");

        if (!missing.isEmpty()) {
            throw new Exception("לא הצלחתי לזהות את השדות: " + join(missing) +
                    "\nנשמר קובץ אבחון: BuildHubs_ExtractedText.txt");
        }
        return fields;
    }

    private Fields extractFields(String text) {
        Fields f = new Fields();
        f.owner = findOwnerContextual(text);
        if (isEmpty(f.owner)) f.owner = findTextField(text, OWNER_LABELS, FieldKind.OWNER);
        f.id = findId(text);
        f.vehicle = findNumberField(text, VEHICLE_LABELS, NumberKind.VEHICLE);
        f.address = findTextField(text, ADDRESS_LABELS, FieldKind.ADDRESS);
        f.year = findNumberField(text, YEAR_LABELS, NumberKind.YEAR);
        f.engine = findNumberField(text, ENGINE_LABELS, NumberKind.ENGINE);
        f.make = findTextField(text, MAKE_LABELS, FieldKind.MAKE);
        f.vin = findVin(text);

        // Final contextual fallback for PDFs that place several fields on one visual line.
        if (isEmpty(f.id)) f.id = findIdContextual(text);
        if (isEmpty(f.vehicle)) f.vehicle = findVehicleContextual(text);
        if (isEmpty(f.make)) f.make = findMakeContextual(text);
        if (isEmpty(f.vin)) f.vin = findVinContextual(text);

        // VehicleParser is the primary parser (tested on real PDFBox output); the code above is only a fallback.
        VehicleParser.Fields vp = VehicleParser.parse(text);
        f.owner = first(vp.owner, f.owner);
        f.id = first(vp.id, f.id);
        f.vehicle = first(vp.vehicle, f.vehicle);
        f.address = first(vp.address, f.address);
        f.year = first(vp.year, f.year);
        f.engine = first(vp.engine, f.engine);
        f.make = first(vp.make, f.make);
        f.vin = first(vp.vin, f.vin);
        return f;
    }

    private Fields mergeFields(Fields a, Fields b) {
        Fields f = new Fields();
        f.owner = first(a.owner, b.owner);
        f.id = first(a.id, b.id);
        f.vehicle = first(a.vehicle, b.vehicle);
        f.address = first(a.address, b.address);
        f.year = first(a.year, b.year);
        f.engine = first(a.engine, b.engine);
        f.make = first(a.make, b.make);
        f.vin = first(a.vin, b.vin);
        return f;
    }

    private String findTextField(String text, String[] labels, FieldKind kind) {
        String[] lines = normalizeText(text).split("\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = cleanLine(lines[i]);
            for (String label : labels) {
                int at = indexOfIgnoreCase(line, label);
                if (at < 0) continue;

                String before = trimOtherLabels(cleanValue(line.substring(0, at)));
                String after = trimOtherLabels(cleanValue(line.substring(at + label.length())));
                if (validText(after, kind)) return after;
                if (validText(before, kind)) return before;

                String next = nearbyText(lines, i, 1, kind);
                if (next != null) return next;
                String previous = nearbyText(lines, i, -1, kind);
                if (previous != null) return previous;
            }
        }
        return null;
    }

    private String nearbyText(String[] lines, int base, int direction, FieldKind kind) {
        for (int distance = 1; distance <= 2; distance++) {
            int index = base + direction * distance;
            if (index < 0 || index >= lines.length) break;
            String value = cleanValue(lines[index]);
            if (value.isEmpty()) continue;
            if (containsAnyLabel(value)) break;
            if (validText(value, kind)) return value;
        }
        return null;
    }

    private boolean validText(String value, FieldKind kind) {
        if (isEmpty(value) || containsAnyLabel(value) || !Pattern.compile("\\p{L}").matcher(value).find()) return false;
        int max = kind == FieldKind.ADDRESS ? 150 : (kind == FieldKind.OWNER ? 100 : 80);
        return value.length() >= 2 && value.length() <= max;
    }

    private String findNumberField(String text, String[] labels, NumberKind kind) {
        String[] lines = normalizeText(text).split("\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = cleanLine(lines[i]);
            for (String label : labels) {
                int at = indexOfIgnoreCase(line, label);
                if (at < 0) continue;

                String value = numberFrom(line.substring(at + label.length()), kind);
                if (value != null) return value;
                value = numberFrom(line.substring(0, at), kind);
                if (value != null) return value;

                value = nearbyNumber(lines, i, 1, kind);
                if (value != null) return value;
                value = nearbyNumber(lines, i, -1, kind);
                if (value != null) return value;
            }
        }
        return null;
    }

    private String nearbyNumber(String[] lines, int base, int direction, NumberKind kind) {
        for (int distance = 1; distance <= 2; distance++) {
            int index = base + direction * distance;
            if (index < 0 || index >= lines.length) break;
            String line = cleanLine(lines[index]);
            if (line.isEmpty()) continue;
            if (containsAnyLabel(line)) break;
            String value = numberFrom(line, kind);
            if (value != null) return value;
        }
        return null;
    }

    private String numberFrom(String value, NumberKind kind) {
        if (isEmpty(value)) return null;
        Matcher matcher = Pattern.compile("(?<!\\d)(\\d(?:[ .\\-–—]?\\d){1,9})(?!\\d)").matcher(value);
        while (matcher.find()) {
            String digits = matcher.group(1).replaceAll("\\D", "");
            if (kind == NumberKind.VEHICLE && digits.matches("\\d{7,8}")) return digits;
            if (kind == NumberKind.YEAR && digits.matches("\\d{4}")) {
                int year = Integer.parseInt(digits);
                if (year >= 1900 && year <= 2100) return digits;
            }
            if (kind == NumberKind.ENGINE && digits.matches("\\d{2,6}")) {
                int engine = Integer.parseInt(digits);
                if (engine >= 50 && engine <= 20000) return digits;
            }
        }
        return null;
    }

    private String findId(String text) {
        String[] lines = normalizeText(text).split("\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = cleanLine(lines[i]);
            for (String label : ID_LABELS) {
                int at = indexOfIgnoreCase(line, label);
                if (at < 0) continue;
                String id = idFrom(line.substring(at + label.length()));
                if (id != null) return id;
                id = idFrom(line.substring(0, at));
                if (id != null) return id;
                if (i + 1 < lines.length && !containsAnyLabel(lines[i + 1])) {
                    id = idFrom(lines[i + 1]);
                    if (id != null) return id;
                }
                if (i > 0 && !containsAnyLabel(lines[i - 1])) {
                    id = idFrom(lines[i - 1]);
                    if (id != null) return id;
                }
            }
        }
        return null;
    }

    private String idFrom(String value) {
        if (isEmpty(value)) return null;
        Matcher hyphenated = Pattern.compile("(?<!\\d)(\\d{7,9})\\s*[-–—]\\s*(\\d)(?!\\d)").matcher(value);
        if (hyphenated.find()) return hyphenated.group(1) + "-" + hyphenated.group(2);
        Matcher plain = Pattern.compile("(?<!\\d)(\\d{8,9})(?!\\d)").matcher(value);
        return plain.find() ? plain.group(1) : null;
    }

    private String searchableText(String text) {
        return normalizeText(text)
                .replace('\n', ' ')
                .replace('׳', '\'')
                .replace('״', '"')
                .replaceAll("[\\t ]+", " ")
                .trim();
    }

    private String findOwnerContextual(String text) {
        String t = searchableText(text);

        String name = "([א-ת][א-ת'׳-]*(?:\\s+[א-ת][א-ת'׳-]*){1,3})";
        String normalLabel = "שם\\s*הבעלים";
        String reversedLabel = "םילעבה\\s*םש";
        String nextField = "(?=\\s*(?:פ\\s*[\\\"']?\\s*ח|ח\\s*[\\\"']?\\s*פ|מס\\s*['.]?\\s*זהות|זהות|תעודת\\s*זהות|ת\\s*[.]?\\s*ז|מס\\s*['.]?\\s*רישוי|רישוי\\s*['.]?\\s*מס|$))";

        // Normal logical order: שם הבעלים שליו חכם
        Matcher after = Pattern.compile(
                normalLabel + "[^א-ת]{0,20}" + name + nextField
        ).matcher(t);
        if (after.find()) {
            String value = cleanOwnerName(after.group(1));
            if (!isEmpty(value)) return value;
        }

        // Reversed label as sometimes returned by PDF text extraction.
        after = Pattern.compile(
                reversedLabel + "[^א-ת]{0,20}" + name + nextField
        ).matcher(t);
        if (after.find()) {
            String value = cleanOwnerName(after.group(1));
            if (!isEmpty(value)) return value;
        }

        // Value before label: שליו חכם שם הבעלים
        Matcher before = Pattern.compile(
                name + "[^א-ת]{0,20}(?:" + normalLabel + "|" + reversedLabel + ")"
        ).matcher(t);
        String result = null;
        while (before.find()) {
            String value = cleanOwnerName(before.group(1));
            if (!isEmpty(value)) result = value;
        }
        if (!isEmpty(result)) return result;

        // Layout fallback in these reports: name + 581-26-702 + שם הבעלים.
        Matcher reportLayout = Pattern.compile(
                name + "\\s+\\d{2,4}[-–]\\d{2,4}[-–]\\d{2,4}\\s*" + normalLabel
        ).matcher(t);
        if (reportLayout.find()) return cleanOwnerName(reportLayout.group(1));

        return null;
    }

    private String cleanOwnerName(String value) {
        if (isEmpty(value)) return null;
        String cleaned = value.replaceAll("[^א-ת'׳ -]", " ")
                .replaceAll("\\s+", " ").trim();
        if (!cleaned.matches("[א-ת][א-ת'׳-]*(?:\\s+[א-ת][א-ת'׳-]*){1,3}")) return null;
        if (cleaned.contains("פרטי רכב") || cleaned.contains("שם הבעלים")) return null;
        return cleaned;
    }

    private String findIdContextual(String text) {
        String t = searchableText(text);
        String[] regexes = {
                "(?:מס\\s*['.]?\\s*זהות|זהות\\s*תעודת|תעודת\\s*זהות|ת\\s*[.]?\\s*ז\\s*[.]?)[^0-9]{0,20}(\\d{7,9})\\s*[-–—]\\s*(\\d)",
                "(\\d{7,9})\\s*[-–—]\\s*(\\d)[^0-9א-ת]{0,20}(?:מס\\s*['.]?\\s*זהות|זהות\\s*תעודת|תעודת\\s*זהות)"
        };
        for (String regex : regexes) {
            Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(t);
            if (m.find()) return m.group(1) + "-" + m.group(2);
        }
        return null;
    }

    private String findVehicleContextual(String text) {
        String t = searchableText(text);
        String[] regexes = {
                "(?:מס\\s*['.]?\\s*רישוי|מספר\\s*רכב|רכב\\s*מספר|רישוי\\s*['.]?\\s*מס)[^0-9]{0,20}(\\d(?:[ .\\-–—]?\\d){6,7})(?!\\d)",
                "(?<!\\d)(\\d(?:[ .\\-–—]?\\d){6,7})[^0-9א-ת]{0,20}(?:מס\\s*['.]?\\s*רישוי|מספר\\s*רכב|רכב\\s*מספר)"
        };
        for (String regex : regexes) {
            Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(t);
            if (m.find()) {
                String digits = m.group(1).replaceAll("\\D", "");
                if (digits.matches("\\d{7,8}")) return digits;
            }
        }
        return null;
    }

    private String findMakeContextual(String text) {
        String t = searchableText(text);
        String label = "(?:יצרן\\s*/?\\s*דגם|דגם\\s*/?\\s*יצרן|תוצר)";
        String next = "(?=\\s+(?:מס\\s*['.]?\\s*שילדה|מספר\\s*שילדה|שילדה\\s*['.]?\\s*מס|שילדה\\s*מספר|VIN)\\b|$)";
        Matcher after = Pattern.compile(label + "\\s*[:\\-]?\\s*([א-תA-Za-z][א-תA-Za-z0-9 .\\-]{1,80}?)" + next,
                Pattern.CASE_INSENSITIVE).matcher(t);
        if (after.find()) return cleanValue(after.group(1));

        Matcher before = Pattern.compile("([א-תA-Za-z][א-תA-Za-z0-9 .\\-]{1,80}?)\\s*" + label,
                Pattern.CASE_INSENSITIVE).matcher(t);
        String result = null;
        while (before.find()) result = cleanValue(before.group(1));
        return result;
    }

    private String findVinContextual(String text) {
        String t = searchableText(text);
        String label = "(?:מס\\s*['.]?\\s*שילדה|מספר\\s*שילדה|שילדה\\s*['.]?\\s*מס|שילדה\\s*מספר|VIN)";
        Matcher after = Pattern.compile(label + "[^A-Za-z0-9]{0,20}([A-HJ-NPR-Z0-9]{10,25})(?![A-Za-z0-9])",
                Pattern.CASE_INSENSITIVE).matcher(t);
        if (after.find()) return after.group(1).toUpperCase(Locale.US);

        Matcher before = Pattern.compile("(?<![A-Za-z0-9])([A-HJ-NPR-Z0-9]{10,25})[^A-Za-z0-9א-ת]{0,20}" + label,
                Pattern.CASE_INSENSITIVE).matcher(t);
        if (before.find()) return before.group(1).toUpperCase(Locale.US);
        return null;
    }

    private String findVin(String text) {
        String normalized = normalizeText(text);
        String[] lines = normalized.split("\\n", -1);

        // VIN standard of 17 characters, if present anywhere in the document.
        Matcher standard = Pattern.compile(
                "(?<![A-Za-z0-9])([A-HJ-NPR-Z0-9]{17})(?![A-Za-z0-9])",
                Pattern.CASE_INSENSITIVE
        ).matcher(normalized);
        while (standard.find()) {
            String vin = standard.group(1).toUpperCase(Locale.US);
            if (vin.matches(".*[A-Z].*")) return vin;
        }

        // Some Israeli source reports contain a shorter chassis identifier.
        // Accept 10-25 alphanumeric characters only when adjacent to a chassis label.
        for (int i = 0; i < lines.length; i++) {
            String line = cleanLine(lines[i]);
            for (String label : VIN_LABELS) {
                int at = indexOfIgnoreCase(line, label);
                if (at < 0) continue;

                String vin = vinFrom(line.substring(at + label.length()));
                if (vin != null) return vin;
                vin = vinFrom(line.substring(0, at));
                if (vin != null) return vin;

                if (i + 1 < lines.length && !containsAnyLabel(lines[i + 1])) {
                    vin = vinFrom(lines[i + 1]);
                    if (vin != null) return vin;
                }
                if (i > 0 && !containsAnyLabel(lines[i - 1])) {
                    vin = vinFrom(lines[i - 1]);
                    if (vin != null) return vin;
                }
            }
        }
        return null;
    }

    private String vinFrom(String value) {
        if (isEmpty(value)) return null;
        Matcher matcher = Pattern.compile(
                "(?<![A-Za-z0-9])([A-HJ-NPR-Z0-9]{10,25})(?![A-Za-z0-9])",
                Pattern.CASE_INSENSITIVE
        ).matcher(value);
        while (matcher.find()) {
            String vin = matcher.group(1).toUpperCase(Locale.US);
            if (vin.matches(".*[A-Z].*") && vin.matches(".*[0-9].*")) return vin;
        }
        return null;
    }

    private String normalizeText(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]", "")
                .replace(' ', ' ').replace('\u0000', ' ')
                .replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n").trim();
    }

    private String cleanLine(String value) {
        return value == null ? "" : value.replaceAll("[\\t ]+", " ").trim();
    }

    private String cleanValue(String value) {
        if (value == null) return "";
        return value.replaceAll("^[\\s:：;,|/\\\\-–—]+", "")
                .replaceAll("[\\s:：;,|/\\\\-–—]+$", "").trim();
    }

    private String trimOtherLabels(String value) {
        if (isEmpty(value)) return "";
        String result = value;
        int earliest = result.length();
        for (String[] group : ALL_LABEL_GROUPS) {
            for (String label : group) {
                int at = indexOfIgnoreCase(result, label);
                if (at > 0 && at < earliest) earliest = at;
            }
        }
        return cleanValue(earliest < result.length() ? result.substring(0, earliest) : result);
    }

    private boolean containsAnyLabel(String value) {
        if (isEmpty(value)) return false;
        for (String[] group : ALL_LABEL_GROUPS) {
            for (String label : group) {
                if (indexOfIgnoreCase(value, label) >= 0) return true;
            }
        }
        return false;
    }

    private int indexOfIgnoreCase(String text, String search) {
        if (text == null || search == null) return -1;
        return text.toLowerCase(Locale.ROOT).indexOf(search.toLowerCase(Locale.ROOT));
    }

    private String first(String a, String b) {
        return !isEmpty(a) ? a : b;
    }

    private String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) result.append(", ");
            result.append(values.get(i));
        }
        return result.toString();
    }

    private boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private void saveDebugText(String sorted, String unsorted) {
        try {
            File directory = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (directory == null) directory = getFilesDir();
            File file = new File(directory, "BuildHubs_ExtractedText.txt");
            String content = "===== SORTED TEXT =====\n" + sorted +
                    "\n\n===== UNSORTED TEXT =====\n" + unsorted;
            try (FileOutputStream output = new FileOutputStream(file, false)) {
                output.write(content.getBytes(StandardCharsets.UTF_8));
            }
            Log.d(TAG, "Debug file: " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Unable to save debug text", e);
        }
    }

    // Writing onto the target form is done by PdfFiller, using cell positions measured from the form's borders.
    private void editTarget(Uri uri, File output, Fields f) throws Exception {
        PDDocument document;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new Exception("לא ניתן לפתוח את PDF היעד.");
            document = PDDocument.load(input);
        }

        try {
            if (document.getNumberOfPages() == 0) throw new Exception("PDF היעד ריק.");
            try (InputStream fontInput = getAssets().open("DejaVuSans.ttf")) {
                PdfFiller.fill(document, f.owner, f.id, f.vehicle, f.address,
                        f.year, f.engine, f.make, f.vin, fontInput);
            }
            document.save(output);
        } finally {
            document.close();
        }
    }

    private void share(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/pdf");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "שליחת PDF"));
        } catch (Exception e) {
            status.setText("ה-PDF נוצר בהצלחה:\n" + file.getAbsolutePath() +
                    "\n\nלא ניתן לפתוח את חלון השיתוף: " + e.getMessage());
        }
    }

    private enum FieldKind { OWNER, ADDRESS, MAKE }
    private enum NumberKind { VEHICLE, YEAR, ENGINE }

    static class Fields {
        String owner;
        String id;
        String vehicle;
        String address;
        String year;
        String engine;
        String make;
        String vin;

        String toDebugString() {
            return "===== EXTRACTED FIELDS =====" +
                    "\nowner=" + owner + "\nid=" + id + "\nvehicle=" + vehicle +
                    "\naddress=" + address + "\nyear=" + year + "\nengine=" + engine +
                    "\nmake=" + make + "\nvin=" + vin;
        }
    }
}
