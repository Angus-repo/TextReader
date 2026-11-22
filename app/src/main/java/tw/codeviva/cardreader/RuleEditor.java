package tw.codeviva.cardreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
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
    private final LinearLayout fieldList;
    private final ArrayList<FieldRow> rows = new ArrayList<>();
    private final TextView error;
    private final String initial;

    RuleEditor(Activity activity, ReaderScreen ui, LinearLayout body, CardCatalog catalog,
               String existingCode, Runnable close) {
        this.activity = activity;
        this.ui = ui;
        this.catalog = catalog;
        this.close = close;
        code = existingCode == null ? "doc_" + identifier() : existingCode;
        CardCatalog.Card card = catalog.cards.get(code);
        body.setFocusableInTouchMode(true);
        LinearLayout section = ui.panel();
        body.addView(section);
        name = input(section, "類型名稱", card == null ? "" : card.label, "例如：員工識別證正面", false);
        ui.gap(section, 12);
        criteria = input(section, "分類描述", card == null ? "" : card.criteria,
            "描述足以辨認此類型的標題、版面、標誌，或與反面的差別。", true);
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
                "automatic", automatic.isChecked(), "fields", fields()).toString();
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
                fields(), automatic.isChecked());
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
