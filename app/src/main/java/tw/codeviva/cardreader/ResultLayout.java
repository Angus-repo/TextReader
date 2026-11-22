package tw.codeviva.cardreader;

import android.content.res.Configuration;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
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

        if (rows.isEmpty()) addTranscript(reading.text, false);
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
            addTranscript(reading.text, true);
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

    private void addTranscript(String original, boolean collapsible) {
        LinearLayout panel = ui.panel();
        page.addView(panel);
        TextView body = ui.text(original.isEmpty() ? "這張影像未讀到文字。" : original,
            16, original.isEmpty() ? MUTED : INK, false);
        body.setLineSpacing(ui.dp(3), 1.08f);
        body.setTextIsSelectable(!original.isEmpty());
        if (collapsible && !original.isEmpty()) {
            LinearLayout header = ui.row();
            panel.addView(header);
            TextView label = ui.text("辨識原文", 16, INK, true);
            label.setAccessibilityHeading(true);
            ui.weight(header, label);
            Button toggle = ui.action("展開", false, v -> {
                boolean expand = body.getVisibility() == View.GONE;
                body.setVisibility(expand ? View.VISIBLE : View.GONE);
                Button button = (Button) v;
                button.setText(expand ? "收合" : "展開");
                button.setContentDescription(expand ? "收合辨識原文" : "展開辨識原文");
            });
            toggle.setContentDescription("展開辨識原文");
            header.addView(toggle);
            body.setPadding(0, ui.dp(14), 0, 0);
            body.setVisibility(View.GONE);
        } else {
            TextView header = ui.text("辨識全文", 17, GREEN, true);
            header.setAccessibilityHeading(true);
            panel.addView(header);
            ui.gap(panel, 10);
        }
        panel.addView(body);
    }
}
