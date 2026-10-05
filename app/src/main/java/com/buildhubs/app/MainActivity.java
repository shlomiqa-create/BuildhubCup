package com.buildhubs.app;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private File lastOutput;
    private File lastInsurance;
    private File lastForm;
    private String lastPlate;
    private Button viewButton;
    private ImageView preview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PDFBoxResourceLoader.init(getApplicationContext());
        registerPicker();
        buildUi();
        loadSavedTarget();
        // A PDF opened with / shared to the app (WhatsApp, Files, Gmail...) becomes the source automatically.
        if (savedInstanceState == null) handleIncoming(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncoming(intent);
    }

    @SuppressWarnings("deprecation")
    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri == null) return;

        sourceUri = uri;
        sourceLabel.setText("מקור: " + displayName(uri));
        if (targetUri != null) {
            create();
        } else {
            status.setText("התקבל PDF מקור. בחר PDF יעד / טופס (שלב 2) ואז צור PDF חדש. הטופס יישמר לפעם הבאה.");
        }
    }

    private void loadSavedTarget() {
        try {
            String saved = getSharedPreferences("buildhubs", MODE_PRIVATE).getString("target_uri", null);
            if (saved == null) return;
            Uri uri = Uri.parse(saved);
            for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions()) {
                if (p.getUri().equals(uri) && p.isReadPermission()) {
                    targetUri = uri;
                    targetLabel.setText("יעד (נשמר): " + displayName(uri));
                    return;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not restore saved target", e);
        }
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
                getSharedPreferences("buildhubs", MODE_PRIVATE).edit().putString("target_uri", uri.toString()).apply();
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

        Button formButton = button("טופס");
        formButton.setOnClickListener(v -> createPowerOfAttorney());
        root.addView(formButton);

        viewButton = button("4. צפייה ב-PDF שנוצר");
        viewButton.setEnabled(false);
        viewButton.setOnClickListener(v -> viewFile());
        root.addView(viewButton);

        // "שיתוף" shares the license, the insurance and the form together.
        Button shareAllButton = button("שיתוף");
        shareAllButton.setOnClickListener(v -> shareAll());
        root.addView(shareAllButton);

        Button shareImageButton = button("שיתוף כתמונה");
        shareImageButton.setOnClickListener(v -> shareInsuranceAsImage());
        root.addView(shareImageButton);

        status = label("הערכים שיועתקו: בעלים, ת.ז., מספר רכב, מען, שנת ייצור, נפח, תוצר ומספר שילדה.");
        root.addView(status);

        // Preview of the generated PDF (first page). Tap to open it full-screen in a PDF viewer.
        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setVisibility(android.view.View.GONE);
        preview.setOnClickListener(v -> viewFile());
        root.addView(preview, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
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
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) { }
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
            lastOutput = output;
            viewButton.setEnabled(true);
            showPreview(output);
            lastInsurance = output;
            lastPlate = fields.vehicle;
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

                // Ministry of Transport license printout: labels are graphics, so parse by position.
                VehicleParser.Fields lic = LicenseParser.parse(document);
                if (lic != null && lic.count() >= 7) { // engine volume may be empty (e.g. trailers)
                    Fields licFields = new Fields();
                    licFields.owner = lic.owner;
                    licFields.id = lic.id;
                    licFields.vehicle = lic.vehicle;
                    licFields.address = lic.address;
                    licFields.year = lic.year;
                    licFields.engine = lic.engine;
                    licFields.make = lic.make;
                    licFields.vin = lic.vin;
                    Log.d(TAG, "License format detected\n" + licFields.toDebugString());
                    return licFields;
                }

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

    /** "שיתוף": sends the license (source), the insurance and the form together in one share. */
    private void shareAll() {
        List<String> missing = new ArrayList<>();
        if (sourceUri == null) missing.add("רישיון רכב (בחירת PDF מקור)");
        if (lastInsurance == null || !lastInsurance.exists()) missing.add("ביטוח (כפתור 3)");
        if (lastForm == null || !lastForm.exists()) missing.add("טופס");
        if (!missing.isEmpty()) {
            status.setText("חסרים לשיתוף: " + join(missing));
            return;
        }
        try {
            File base = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (base == null) base = getFilesDir();
            File dir = new File(base, "share");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("לא ניתן ליצור תיקייה זמנית");
            String plate = isEmpty(lastPlate) ? "" : "_" + lastPlate.trim();

            File license = new File(dir, "רישיון_רכב" + plate + ".pdf");
            try (InputStream in = getContentResolver().openInputStream(sourceUri);
                 FileOutputStream out = new FileOutputStream(license)) {
                if (in == null) throw new Exception("לא ניתן לקרוא את קובץ הרישיון");
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            File insurance = copyTo(lastInsurance, new File(dir, "ביטוח" + plate + ".pdf"));
            File form = copyTo(lastForm, new File(dir, "טופס" + plate + ".pdf"));

            ArrayList<Uri> uris = new ArrayList<>();
            for (File f : new File[]{license, insurance, form}) {
                uris.add(FileProvider.getUriForFile(this, getPackageName() + ".provider", f));
            }
            Intent send = new Intent(Intent.ACTION_SEND_MULTIPLE);
            send.setType("application/pdf");
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            android.content.ClipData clip = android.content.ClipData.newRawUri("pdf", uris.get(0));
            for (int i = 1; i < uris.size(); i++) clip.addItem(new android.content.ClipData.Item(uris.get(i)));
            send.setClipData(clip);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "שיתוף רישיון, ביטוח וטופס"));
        } catch (Exception e) {
            Log.e(TAG, "Share all failed", e);
            status.setText("שגיאה בשיתוף: " + (isEmpty(e.getMessage()) ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /** "שיתוף כתמונה": renders page 1 of the insurance PDF to a PNG and shares only that image. */
    private void shareInsuranceAsImage() {
        if (lastInsurance == null || !lastInsurance.exists()) {
            status.setText("יש ליצור קודם את הביטוח (כפתור 3).");
            return;
        }
        try {
            File base = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (base == null) base = getFilesDir();
            File dir = new File(base, "share");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("לא ניתן ליצור תיקייה זמנית");
            String plate = isEmpty(lastPlate) ? "" : "_" + lastPlate.trim();
            File png = new File(dir, "ביטוח" + plate + ".png");

            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(lastInsurance, ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer renderer = new PdfRenderer(fd)) {
                if (renderer.getPageCount() == 0) throw new Exception("ה-PDF ריק");
                PdfRenderer.Page page = renderer.openPage(0);
                try {
                    float scale = 2.5f; // about 180 dpi: sharp enough to read, small enough to send
                    int w = Math.round(page.getWidth() * scale);
                    int h = Math.round(page.getHeight() * scale);
                    Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(Color.WHITE);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    try (FileOutputStream out = new FileOutputStream(png)) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                } finally {
                    page.close();
                }
            }

            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", png);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("image/png");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.setClipData(android.content.ClipData.newRawUri(png.getName(), uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "שיתוף כתמונה"));
        } catch (Exception e) {
            Log.e(TAG, "Share as image failed", e);
            status.setText("שגיאה בשיתוף כתמונה: " + (isEmpty(e.getMessage()) ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private File copyTo(File from, File to) throws Exception {
        try (InputStream in = new java.io.FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return to;
    }

    /** "טופס": fills the power-of-attorney form (bundled in assets) from the selected source PDF. */
    private void createPowerOfAttorney() {
        if (sourceUri == null) {
            status.setText("יש לבחור קודם PDF מקור (רישיון רכב).");
            return;
        }
        status.setText("יוצר טופס...");
        try {
            Fields f = readFields(sourceUri);
            File directory = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (directory == null) directory = getFilesDir();
            File output = new File(directory, "BuildHubs_Form_" + System.currentTimeMillis() + ".pdf");

            try (InputStream template = getAssets().open("poa_template.pdf");
                 PDDocument document = PDDocument.load(template)) {
                try (InputStream fontInput = getAssets().open("DejaVuSans.ttf")) {
                    PowerOfAttorneyFiller.fill(document, f.owner, f.id, f.vehicle, fontInput);
                }
                document.save(output);
            }

            lastOutput = output;
            viewButton.setEnabled(true);
            showPreview(output);
            lastForm = output;
            lastPlate = f.vehicle;
            status.setText("נוצר טופס בהצלחה:\n" + output.getAbsolutePath());
            share(output);
        } catch (Exception e) {
            Log.e(TAG, "Form failed", e);
            status.setText("שגיאה: " + (isEmpty(e.getMessage()) ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private void showPreview(File file) {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(fd)) {
            if (renderer.getPageCount() == 0) return;
            PdfRenderer.Page page = renderer.openPage(0);
            try {
                int width = getResources().getDisplayMetrics().widthPixels;
                int height = Math.round(width * (float) page.getHeight() / page.getWidth());
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                preview.setImageBitmap(bitmap);
                preview.setVisibility(android.view.View.VISIBLE);
            } finally {
                page.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "Preview failed", e);
            preview.setVisibility(android.view.View.GONE);
        }
    }

    private void viewFile() {
        if (lastOutput == null || !lastOutput.exists()) {
            status.setText("עדיין לא נוצר PDF לצפייה.");
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", lastOutput);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            status.setText("לא נמצאה אפליקציה לצפייה ב-PDF. התקן קורא PDF או שתף את הקובץ.\n" + e.getMessage());
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
