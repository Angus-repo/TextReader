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

    void show(AzureReader.Reading reading) {
        TextView title = ui.text(reading.type.label, 26, INK, true);
        title.setAccessibilityHeading(true);
        page.addView(title);
        ui.gap(page, 16);

        List<Row> rows = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        if (reading.fields != null) {
            for (int i = 0; i < reading.type.fields.length(); i++) {
                JSONObject definition = reading.type.fields.optJSONObject(i);
                JSONObject field = reading.fields.optJSONObject(definition.optString("key"));
                String value = field == null || field.isNull("valueString") ? "" : field.optString("valueString").trim();
                double confidence = field == null ? Double.NaN : field.optDouble("confidence");
                String label = definition.optString("label");
                if (value.isEmpty()) missing.add(label);
                else rows.add(new Row(label, value, confidence));
            }
        }
        boolean extracted = !rows.isEmpty();
        if (!extracted) rows = OcrRows.from(reading.type, reading.text);

        if (rows.isEmpty()) addTranscript(reading, rows, false);
        if (!rows.isEmpty() || !missing.isEmpty()) {
            if (rows.isEmpty()) ui.gap(page, 12);
            LinearLayout card = ui.panel();
            page.addView(card);
            LinearLayout header = ui.row();
            card.addView(header);
            TextView heading = ui.text(extracted || rows.isEmpty() ? "擷取欄位" : "依原文整理", 17, GREEN, true);
            heading.setAccessibilityHeading(true);
            ui.weight(header, heading);
            if (extracted) header.addView(ui.text("已讀取 " + rows.size() + " / " + reading.type.fields.length(), 13, MUTED, false));
            ui.gap(card, 8);
            for (Row row : rows) {
                ui.line(card);
                addRow(card, row);
            }
            if (!missing.isEmpty()) {
                ui.line(card);
                ui.gap(card, 10);
                card.addView(ui.text(extracted ? "未讀取" : "欄位擷取未讀取", 13, MUTED, true));
                ui.gap(card, 4);
                card.addView(ui.text(String.join("、", missing), 14, MUTED, false));
            }
        }
        if (!rows.isEmpty()) {
            ui.gap(page, 12);
            addTranscript(reading, rows, true);
        }
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
        TextView value = ui.text(data.value, 17, INK, true);
        value.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_BALANCED);
        value.setTextIsSelectable(true);
        values.addView(value);
        if (data.confidence >= 0 && data.confidence <= 1) {
            ui.gap(values, 3);
            values.addView(ui.text("信心 " + Math.round(data.confidence * 100) + "%", 12, MUTED, false));
        }
    }

    static String transcriptJson(AzureReader.Reading reading, List<Row> rows) {
        try {
            JSONObject values = new JSONObject();
            for (Row row : rows) values.accumulate(row.label, row.value);
            JSONArray lines = new JSONArray();
            if (!reading.text.isEmpty()) {
                for (String line : reading.text.split("\\r\\n|\\r|\\n", -1)) lines.put(line);
            }
            return CardCatalog.obj("文件類型", reading.type.label, "欄位", values, "原文逐行", lines).toString(2);
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

    private void addTranscript(AzureReader.Reading reading, List<Row> rows, boolean collapsible) {
        LinearLayout panel = ui.panel();
        page.addView(panel);
        LinearLayout header = ui.row();
        panel.addView(header);
        TextView title = ui.text("辨識原文（JSON）", 16, INK, true);
        title.setAccessibilityHeading(true);
        ui.weight(header, title);
        String json = transcriptJson(reading, rows);
        LinearLayout details = ui.col();
        if (collapsible) {
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
        }
        panel.addView(details);
        ui.gap(details, 10);
        if (reading.text.isEmpty()) {
            details.addView(ui.text("這張影像未讀到文字。", 14, MUTED, false));
            ui.gap(details, 6);
        }
        TextView body = ui.text(json, 14, INK, false);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setTag("transcript-json");
        details.addView(body);
        ui.gap(details, 10);
        details.addView(ui.action("複製 JSON", false, () -> copyJson(json)));
    }
}
