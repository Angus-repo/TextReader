package tw.codeviva.cardreader;

import android.content.res.Configuration;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.PersistableBundle;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import static tw.codeviva.cardreader.ReaderScreen.*;

final class ResultLayout {
    private final ReaderScreen ui;
    private final LinearLayout page;

    ResultLayout(ReaderScreen ui, LinearLayout page) {
        this.ui = ui;
        this.page = page;
    }

    void show(AzureReader.Reading reading) { show(reading, null); }

    void show(AzureReader.Reading reading, Runnable rescanBarcode) {
        TextView title = ui.text(reading.type.label, 26, INK, true);
        title.setAccessibilityHeading(true);
        page.addView(title);
        ui.gap(page, 16);

        String displayText = ResultText.forDisplay(reading.text);
        List<Row> rows = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        boolean nhi = reading.type.code.equals("nhi_front");
        boolean license = LicenseFields.supports(reading.type.code);
        JSONObject cells = reading.local.optJSONObject("LicenseCells");
        JSONObject blankCells = reading.local.optJSONObject("LicenseBlankCells");
        java.util.Map<String,String> licenseLabels = license ? LicenseFields.labeled(reading.type, displayText) : null;
        boolean front = reading.type.code.equals("id_front"), back = reading.type.code.equals("id_back");
        String sexLabel = front ? reading.type.fieldLabel("Sex") : null;
        String idLabel = front ? reading.type.fieldLabel("IdNumber") : null;
        String serviceLabel = back ? reading.type.fieldLabel("MilitaryService") : null;
        String serialLabel = back ? reading.type.fieldLabel("Serial") : null;
        String deviceNote = null;
        boolean usedImage = false;
        List<Row> local = null;
        int cloudValues = 0;
        int definedFields = reading.type.fields.length();
        for (int i = 0; i < reading.type.fields.length(); i++) {
            JSONObject definition = reading.type.fields.optJSONObject(i);
            String label = definition.optString("label");
            String key = definition.optString("key");
            if (back && (backBarcodeLabel(label) || "IdNumber".equals(key) || "Barcode".equals(key))) {
                // These reserved rows use only a decoded payload, including on older custom rules.
                definedFields--;
                continue;
            }
            if (license && blankCells != null && blankCells.optBoolean(key)) {
                rows.add(new Row(label, "", Double.NaN));
                continue;
            }
            JSONObject field = reading.fields == null ? null : reading.fields.optJSONObject(key);
            String value = IdCardDetails.cloudValue(reading, key);
            if (license) value = LicenseFields.clean(key,value);
            if (front && "Sex".equals(key)) value = IdCardDetails.sexValue(value);
            if (front && "IdNumber".equals(key)) value = IdCardDetails.idNumber(value);
            if (back && "MilitaryService".equals(key)) value = IdCardDetails.serviceValue(value);
            if (back && "Serial".equals(key)) value = IdCardDetails.serial(value);
            if (!value.isEmpty()) {
                rows.add(new Row(label, value, field.optDouble("confidence")));
                cloudValues++;
                continue;
            }
            if (license) {
                String fromImage = LicenseFields.positioned(cells,key);
                if (!fromImage.isEmpty()) usedImage = true;
                // When the actual table was read, an empty physical cell must stay empty.
                String resolved = fromImage;
                if (resolved.isEmpty() && (cells == null || !cells.has(key) || key.equals("Authority")))
                    resolved = LicenseFields.fromLabels(licenseLabels,key,label,displayText);
                rows.add(new Row(label,resolved,Double.NaN));
                continue;
            }
            if (nhi) {
                if (local == null) local = OcrRows.from(reading.type, displayText);
                Row candidate = uniqueRow(local, label);
                if (candidate != null) {
                    rows.add(candidate);
                    continue;
                }
            }
            if (reading.fields != null || nhi) missing.add(label);
        }
        if (!nhi && !license && rows.isEmpty()) {
            rows = OcrRows.from(reading.type, displayText);
            // Resolve these labels below, even when cloud extraction returned other fields.
            if (front) rows.removeIf(row -> "性別".equals(row.label) || row.label.equals(sexLabel)
                    || "統一編號".equals(row.label) || "身分證字號".equals(row.label) || row.label.equals(idLabel));
            if (back) rows.removeIf(row -> "役別".equals(row.label) || row.label.equals(serviceLabel)
                    || row.label.equals(serialLabel) || backBarcodeLabel(row.label)
                    || row.label.equals(reading.type.fieldLabel("IdNumber")) || row.label.equals(reading.type.fieldLabel("Barcode")));
            for (Row row : rows) missing.remove(row.label);
        }

        if (sexLabel != null && uniqueRow(rows, sexLabel) == null) {
            String sex = IdCardDetails.printedSex(displayText);
            if (sex.isEmpty()) {
                sex = IdCardDetails.sexValue(reading.local.optString("Sex"));
                usedImage = !sex.isEmpty();
            }
            if (sex.isEmpty()) {
                if (!missing.contains(sexLabel)) missing.add(sexLabel);
            } else {
                rows.add(new Row(sexLabel, sex, Double.NaN));
                missing.remove(sexLabel);
            }
        }
        if (idLabel != null && uniqueRow(rows, idLabel) == null) {
            String id = IdCardDetails.idNumber(displayText);
            if (id.isEmpty()) {
                id = IdCardDetails.idNumber(reading.local.optString("IdNumber"));
                usedImage |= !id.isEmpty();
            }
            rows.add(new Row(idLabel, id.isEmpty() ? null : id, Double.NaN));
            missing.remove(idLabel);
        }
        if (back) {
            String barcode = reading.local.isNull("Barcode") ? "" : reading.local.optString("Barcode");
            int at = 0;
            while (at < rows.size() && !rows.get(at).label.equals(serviceLabel) && !rows.get(at).label.equals(serialLabel)) at++;
            rows.add(at, new Row(IdCardDetails.BARCODE_LABEL, barcode.isEmpty() ? null : barcode, Double.NaN));
            if (!barcode.isEmpty()) {
                usedImage = true;
            } else if (reading.local.has("Barcode")) {
                deviceNote = "左下角條碼未能解碼，請近拍同一張證件，讓完整條紋與兩側留白一起入框。";
            } else {
                deviceNote = "這筆紀錄沒有條碼資料，可補掃同一張證件的左下角。";
            }
        }
        if (serviceLabel != null && uniqueRow(rows, serviceLabel) == null) {
            String service = IdCardDetails.militaryService(displayText);
            if (service.isEmpty()) {
                service = IdCardDetails.serviceValue(reading.local.optString("MilitaryService"));
                usedImage |= !service.isEmpty();
            }
            rows.add(new Row(serviceLabel, service, Double.NaN));
            missing.remove(serviceLabel);
        }
        if (serialLabel != null && uniqueRow(rows, serialLabel) == null) {
            String serial = IdCardDetails.serial(displayText);
            if (serial.isEmpty()) {
                serial = IdCardDetails.serial(reading.local.optString("Serial"));
                usedImage |= !serial.isEmpty();
            }
            if (!serial.isEmpty()) {
                rows.add(new Row(serialLabel, serial, Double.NaN));
                missing.remove(serialLabel);
            }
        }

        LinearLayout card = ui.panel();
        page.addView(card);
        LinearLayout header = ui.row();
        card.addView(header);
        TextView heading = ui.text("欄位結果", 17, GREEN, true);
        heading.setAccessibilityHeading(true);
        ui.weight(header, heading);
        int filled = 0;
        for (Row row : rows) if (row.value != null && !row.value.isEmpty()) filled++;
        if (cloudValues > 0 || nhi || license) {
            header.addView(ui.text("已讀取 " + filled + " / " + (definedFields + (back ? 1 : 0)), 13, MUTED, false));
        }
        ui.gap(card, 8);
        if (rows.isEmpty()) {
            String message = reading.text.isEmpty() ? "這張影像未讀到文字。" : "未辨識出可對應的欄位，可展開下方 JSON 查看原文。";
            if (!reading.text.isEmpty() && displayText.trim().isEmpty()) message = "沒有其他可顯示的文字。";
            card.addView(ui.text(message, 14, MUTED, false));
        } else if (filled > cloudValues) {
            String source = cloudValues == 0 ? "依原文整理" : "部分欄位依原文整理";
            card.addView(ui.text(usedImage ? "含從影像讀取的欄位" : source, 13, MUTED, false));
            ui.gap(card, 6);
        }
        for (Row row : rows) {
            ui.line(card);
            addRow(card, row);
        }
        if (!missing.isEmpty()) {
            ui.line(card);
            ui.gap(card, 10);
            card.addView(ui.text("未讀取", 13, MUTED, true));
            ui.gap(card, 4);
            card.addView(ui.text(String.join("、", missing), 14, MUTED, false));
        }
        if (deviceNote != null) {
            ui.line(card);
            ui.gap(card, 8);
            card.addView(ui.text(deviceNote, 14, MUTED, false));
            if (rescanBarcode != null) {
                ui.gap(card, 8);
                card.addView(ui.action("補掃左下角條碼", false, rescanBarcode));
            }
        }
        ui.gap(page, 12);
        addTranscript(reading.type.label, displayText, rows);
    }

    private static boolean backBarcodeLabel(String label) {
        return IdCardDetails.BARCODE_LABEL.equals(label) || IdCardDetails.ID_LABEL.equals(label) || "左下角條碼".equals(label);
    }

    private static Row uniqueRow(List<Row> rows, String label) {
        Row match = null;
        for (Row row : rows) {
            if (!label.equals(row.label)) continue;
            if (match != null && !java.util.Objects.equals(match.value, row.value)) return null;
            match = row;
        }
        return match;
    }

    static final class Row {
        final String label, value;
        final double confidence;
        Row(String label, String value, double confidence) {
            this.label = label;
            this.value = value;
            this.confidence = confidence;
        }
    }

    private void addRow(LinearLayout card, Row data) {
        Configuration config = ui.c.getResources().getConfiguration();
        boolean stacked = config.fontScale >= 1.4f || (config.screenWidthDp > 0 && config.screenWidthDp < 340);
        LinearLayout row = stacked ? ui.col() : ui.row();
        row.setGravity(Gravity.TOP);
        ui.pad(row, 0, 11);
        card.addView(row);
        TextView label = ui.text(data.label, 14, MUTED, false);
        LinearLayout values = ui.col();
        if (stacked) {
            row.addView(label);
            ui.gap(row, 4);
            row.addView(values);
        } else {
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 0.27f));
            ui.gap(row, 12);
            row.addView(values, new LinearLayout.LayoutParams(0, -2, 0.73f));
        }
        TextView value = data.value == null ? ui.text("未讀取", 15, MUTED, false) : ui.text(data.value, 17, INK, true);
        value.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_BALANCED);
        value.setTextIsSelectable(true);
        values.addView(value);
        if (data.confidence >= 0 && data.confidence <= 1) {
            ui.gap(values, 3);
            values.addView(ui.text("信心 " + Math.round(data.confidence * 100) + "%", 12, MUTED, false));
        }
    }

    private static String transcriptJson(String typeLabel, String displayText, List<Row> rows) {
        try {
            JSONObject values = new JSONObject();
            for (Row row : rows) values.accumulate(row.label, row.value == null ? JSONObject.NULL : row.value);
            JSONArray lines = new JSONArray();
            if (!displayText.isEmpty()) {
                for (String line : displayText.split("\\r\\n|\\r|\\n", -1)) lines.put(line);
            }
            return CardCatalog.obj("文件類型", typeLabel, "欄位", values, "原文逐行", lines).toString(2);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private void copyJson(String json) {
        ClipboardManager clipboard = (ClipboardManager) ui.c.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("TextReader JSON", json);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
        clip.getDescription().setExtras(extras);
        try {
            clipboard.setPrimaryClip(clip);
            if (android.os.Build.VERSION.SDK_INT < 33)
                Toast.makeText(ui.c, "已複製 JSON", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Toast.makeText(ui.c, "無法複製這筆 JSON，可從首頁匯出備份。", Toast.LENGTH_LONG).show();
        }
    }

    private void addTranscript(String typeLabel, String displayText, List<Row> rows) {
        LinearLayout panel = ui.panel();
        page.addView(panel);
        LinearLayout header = ui.row();
        panel.addView(header);
        TextView title = ui.text("辨識原文（JSON）", 16, INK, true);
        title.setAccessibilityHeading(true);
        ui.weight(header, title);
        String json = transcriptJson(typeLabel, displayText, rows);
        LinearLayout details = ui.col();
        Button toggle = ui.action("展開", false, v -> {
            boolean expand = details.getVisibility() == View.GONE;
            details.setVisibility(expand ? View.VISIBLE : View.GONE);
            Button button = (Button) v;
            button.setText(expand ? "收合" : "展開");
            button.setContentDescription(expand ? "收合辨識原文 JSON" : "展開辨識原文 JSON");
        });
        toggle.setContentDescription("展開辨識原文 JSON");
        header.addView(toggle);
        details.setVisibility(View.GONE);
        panel.addView(details);
        ui.gap(details, 10);
        TextView body = ui.text(json, 14, INK, false);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setTag("transcript-json");
        details.addView(body);
        ui.gap(details, 10);
        details.addView(ui.action("複製 JSON", false, () -> copyJson(json)));
    }
}
