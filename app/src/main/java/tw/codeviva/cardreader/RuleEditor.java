package tw.codeviva.cardreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import static tw.codeviva.cardreader.ReaderScreen.*;

final class RuleEditor {
    private final Activity activity;
    private final ReaderScreen ui;
    private final CardCatalog catalog;
    private final String code;
    private final Runnable close;
    private final EditText name;
    private final EditText criteria;
    private final CheckBox automatic;
    private final Spinner extraction;
    private final LinearLayout fieldList;
    private final ArrayList<FieldRow> rows = new ArrayList<>();
    private final TextView error;
    private final String initial;

    RuleEditor(Activity activity, ReaderScreen ui, LinearLayout body, CardCatalog catalog,
               String existingCode, Config config, Runnable close) {
        this.activity = activity;
        this.ui = ui;
        this.catalog = catalog;
        this.close = close;
        code = existingCode == null ? "doc_" + identifier() : existingCode;
        CardCatalog.Card card = catalog.cards.get(code);
        body.setFocusableInTouchMode(true);
        body.addView(ui.text(ResultText.RULE, 14, MUTED, false));
        ui.gap(body, 12);
        if ("id_back".equals(code)) {
            body.addView(ui.text("手機會解碼左下角條碼，從中讀取身分證字號。右下角的十位數字另列為證件流水號。役別欄無文字時，結果會保留空白。", 14, MUTED, false));
            ui.gap(body, 12);
        }
        if (card == null) {
            body.addView(ui.action("帶入內建範例", false, this::chooseTemplate));
            ui.gap(body, 12);
        }
        LinearLayout section = ui.panel();
        body.addView(section);
        name = input(section, "類型名稱", card == null ? "" : card.label, "例如：員工識別證正面", false);
        ui.gap(section, 12);
        criteria = input(section, "分類描述", card == null ? "" : card.criteria,
            "描述足以辨認此類型的標題、版面、標誌，或與反面的差別。", true);
        ui.gap(section, 12);
        TextView extractionLabel = ui.text("內容擷取強度", 14, MUTED, true);
        section.addView(extractionLabel);
        extraction = new Spinner(activity);
        extraction.setId(View.generateViewId());
        extractionLabel.setLabelFor(extraction.getId());
        extraction.setContentDescription("內容擷取強度");
        extraction.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, ExtractionMode.values()));
        extraction.setSelection((card == null ? ExtractionMode.BASIC : card.extraction).ordinal());
        section.addView(extraction, -1, ui.dp(48));
        TextView unitPrice = ui.text("", 14, GREEN, false);
        section.addView(unitPrice);
        extraction.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                java.math.BigDecimal rate = ExtractionMode.values()[position].asyncRate(config);
                unitPrice.setText("非同步擷取 US$" + rate.stripTrailingZeros().toPlainString()
                        + "／1,000 頁，每頁 US$" + rate.movePointLeft(3).stripTrailingZeros().toPlainString());
            }
        });
        section.addView(ui.text("Minimal 先由手機辨識，再將純文字送往 Azure，不含原圖版面。分類與自訂欄位另計費。", 13, MUTED, false));
        if (config.extractionOverride != null)
            section.addView(ui.text("連線設定目前統一使用 " + config.extractionOverride.label + "。選回「依辨識規則」後會使用此處的強度。", 13, MUTED, false));
        else if (card != null && !card.explicitExtraction)
            section.addView(ui.text("儲存後，全文與自訂欄位都會套用所選強度。", 13, MUTED, false));
        ui.gap(body, 18);
        body.addView(ui.text("擷取欄位", 19, INK, true));
        ui.gap(body, 6);
        automatic = new CheckBox(activity);
        automatic.setText("辨識為此類型時，自動讀取設定的欄位");
        automatic.setChecked(card == null || card.autoFields);
        body.addView(automatic);
        fieldList = ui.col();
        body.addView(fieldList);
        if (card != null) {
            for (int i = 0; i < card.fields.length(); i++) {
                JSONObject f = card.fields.optJSONObject(i);
                addRow(f.optString("key"), f.optString("label"), f.optString("description"));
            }
        }
        ui.gap(body, 12);
        body.addView(ui.action("新增欄位", false, () -> {
            FieldRow row = addRow("F" + identifier(), "", "");
            row.label.requestFocus();
        }));
        error = ui.text("", 14, 0xff863b24, false);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(error);
        ui.gap(body, 10);
        body.addView(ui.action("儲存辨識規則", true, this::save));
        if (card != null && catalog.customized(code)) {
            ui.gap(body, 10);
            boolean builtIn = catalog.builtIn(code);
            String action = builtIn ? "還原此類型的預設規則" : "刪除此自訂類型";
            body.addView(ui.action(action, false, () -> new AlertDialog.Builder(activity)
                .setTitle(action + "？").setMessage("將套用至後續掃描。")
                .setNegativeButton("取消", null).setPositiveButton(builtIn ? "還原" : "刪除", (dialog, which) -> reset()).show()));
        }
        initial = draft();
    }

    private void chooseTemplate() {
        ArrayList<CardCatalog.Card> templates = new ArrayList<>(catalog.builtInTemplates());
        String[] labels = new String[templates.size()];
        for (int i = 0; i < templates.size(); i++) labels[i] = templates.get(i).label;
        new AlertDialog.Builder(activity).setTitle("選擇要帶入的範例")
            .setItems(labels, (dialog, which) -> {
                CardCatalog.Card template = templates.get(which);
                if (criteria.length() == 0 && rows.isEmpty()) {
                    applyTemplate(template);
                } else {
                    new AlertDialog.Builder(activity).setTitle("取代草稿中的分類描述和欄位？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("帶入", (confirm, action) -> applyTemplate(template)).show();
                }
            }).show();
    }

    private void applyTemplate(CardCatalog.Card template) {
        criteria.setText(template.criteria);
        extraction.setSelection(template.extraction.ordinal());
        fieldList.removeAllViews();
        rows.clear();
        for (int i = 0; i < template.fields.length(); i++) {
            JSONObject field = template.fields.optJSONObject(i);
            addRow(field.optString("key"), field.optString("label"), field.optString("description"));
        }
        error.setText("");
        Toast.makeText(activity, "已帶入" + template.label + "範例，可依需求修改", Toast.LENGTH_SHORT).show();
    }

    private static String identifier() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private EditText input(LinearLayout panel, String label, String value, String hint, boolean multiline) {
        TextView title = ui.text(label, 14, MUTED, true);
        panel.addView(title);
        EditText input = new EditText(activity);
        input.setId(View.generateViewId());
        title.setLabelFor(input.getId());
        input.setContentDescription(label);
        input.setTextColor(INK);
        input.setTextSize(16);
        input.setSaveEnabled(false);
        input.setSingleLine(!multiline);
        if (multiline) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            input.setMinLines(2);
            input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        }
        input.setText(value);
        input.setHint(hint);
        panel.addView(input, -1, -2);
        return input;
    }

    private final class FieldRow {
        final String key;
        final LinearLayout panel;
        final EditText label;
        final EditText description;
        FieldRow(String key, String title, String text) {
            this.key = key;
            panel = ui.panel();
            label = input(panel, "欄位名稱", title, "例如：卡號", false);
            ui.gap(panel, 8);
            description = input(panel, "讀取描述", text, "例如：讀取晶片下方的 12 碼卡號，保留開頭的 0。", true);
            ui.gap(panel, 6);
            Button remove = ui.action("移除欄位", false, () -> {
                rows.remove(this);
                fieldList.removeView(panel);
            });
            panel.addView(remove);
        }
    }

    private FieldRow addRow(String key, String title, String text) {
        FieldRow row = new FieldRow(key, title, text);
        rows.add(row);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = ui.dp(10);
        fieldList.addView(row.panel, params);
        return row;
    }

    private JSONArray fields() throws JSONException {
        JSONArray result = new JSONArray();
        for (FieldRow row : rows) {
            result.put(CardCatalog.obj("key", row.key, "label", row.label.getText().toString(),
                "description", row.description.getText().toString()));
        }
        return result;
    }

    private String draft() {
        try {
            return CardCatalog.obj("name", name.getText().toString(), "criteria", criteria.getText().toString(),
                "automatic", automatic.isChecked(), "extractionMode", ((ExtractionMode) extraction.getSelectedItem()).label, "fields", fields()).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    void back() {
        if (initial.equals(draft())) {
            close.run();
            return;
        }
        new AlertDialog.Builder(activity).setTitle("放棄尚未儲存的變更？")
            .setNegativeButton("繼續編輯", null)
            .setPositiveButton("放棄", (dialog, which) -> close.run()).show();
    }

    private void save() {
        try {
            catalog.save(code, name.getText().toString(), criteria.getText().toString(),
                fields(), automatic.isChecked(), (ExtractionMode) extraction.getSelectedItem());
            Toast.makeText(activity, "辨識規則已儲存", Toast.LENGTH_SHORT).show();
            close.run();
        } catch (Exception e) {
            error.setText(e.getMessage());
        }
    }

    private void reset() {
        try {
            catalog.reset(code);
            close.run();
        } catch (Exception e) {
            error.setText(e.getMessage());
        }
    }
}
