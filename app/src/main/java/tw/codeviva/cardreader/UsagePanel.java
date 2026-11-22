package tw.codeviva.cardreader;

import android.content.res.Configuration;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import static tw.codeviva.cardreader.ReaderScreen.*;

/** Renders the immutable meter snapshot saved with this reading. */
final class UsagePanel {
    private final ReaderScreen ui;
    private final boolean stacked;

    UsagePanel(ReaderScreen ui) {
        this.ui = ui;
        Configuration config = ui.c.getResources().getConfiguration();
        stacked = config.fontScale >= 1.3f || (config.screenWidthDp > 0 && config.screenWidthDp < 360);
    }

    LinearLayout show(JSONObject usage) {
        LinearLayout card = ui.panel();
        TextView title = ui.text("本次辨識用量", 17, GREEN, true);
        title.setAccessibilityHeading(true);
        card.addView(title);
        ui.gap(card, 12);
        if (usage == null) {
            card.addView(ui.text(ScanUsage.LEGACY, 14, MUTED, false));
            return card;
        }
        LinearLayout total = ui.col();
        total.setBackground(ui.bg(PALE, 12, false));
        ui.pad(total, 14, 12);
        total.addView(ui.text(usage.optBoolean("partialCost") ? "已知費用小計" : "預估總費用", 13, GREEN, false));
        ui.gap(total, 4);
        TextView amount = ui.text(money(usage), 23, GREEN, true);
        amount.setTextIsSelectable(true);
        total.addView(amount);
        card.addView(total);
        ui.gap(card, 14);
        LinearLayout metrics = stacked ? ui.col() : ui.row();
        card.addView(metrics);
        metric(metrics, usage.optBoolean("partialTokens") ? "已回報 Token" : "Token 用量",
                usage.isNull("tokens") || (usage.optBoolean("partialTokens") && usage.optLong("tokens") == 0)
                        ? "未完整回報" : count(usage,"tokens"));
        ui.gap(metrics, 12);
        metric(metrics, "處理時間", ScanUsage.timeText(usage));
        JSONArray ops = usage.optJSONArray("operations");
        if (ops != null) for (int i=0; i<ops.length(); i++) {
            JSONObject op = ops.optJSONObject(i);
            if (op != null) operation(card, op);
        }
        ui.gap(card, 12);
        ui.line(card);
        ui.gap(card, 8);
        if (usage.optBoolean("partialCost")) card.addView(ui.text("小計僅含已知用量與單價。", 13, GREEN, true));
        card.addView(ui.text(usage.optString("rateBasis"), 12, MUTED, false));
        return card;
    }

    private void metric(LinearLayout group, String label, String value) {
        LinearLayout cell = ui.col();
        cell.addView(ui.text(label, 13, MUTED, false));
        ui.gap(cell, 3);
        cell.addView(ui.text(value, 18, INK, true));
        if (stacked) group.addView(cell); else ui.weight(group, cell);
    }

    private static String money(JSONObject obj) {
        return obj.isNull("usd") ? "未取得" : "US$" + obj.optString("usd");
    }

    private static String count(JSONObject obj, String key) {
        return NumberFormat.getIntegerInstance(Locale.TAIWAN).format(obj.optLong(key));
    }

    private void operation(LinearLayout card, JSONObject op) {
        ui.gap(card, 12);
        ui.line(card);
        ui.gap(card, 10);
        String stage = op.optString("stage");
        if ("Decisions 分類".equals(stage)) stage = "影像分類";
        else if ("Content Understanding 讀取".equals(stage)) stage = "文字與欄位讀取";
        LinearLayout heading = stacked ? ui.col() : ui.row();
        card.addView(heading);
        TextView name = ui.text(stage, 15, INK, true);
        if (stacked) heading.addView(name); else ui.weight(heading, name);
        TextView amount = ui.text(money(op), 15, INK, true);
        amount.setGravity(stacked ? Gravity.START : Gravity.END);
        heading.addView(amount);
        List<String> parts = new ArrayList<>();
        if (op.has("input")) parts.add("輸入 " + count(op,"input"));
        if (op.has("output")) parts.add("輸出 " + count(op,"output"));
        if (op.has("pages")) parts.add(count(op,"pages") + (op.optBoolean("inferredPages") ? " 頁（依上傳影像估計）" : " 頁"));
        if (op.has("contextTokens")) parts.add("情境 " + count(op,"contextTokens") + " tokens");
        if (op.has("advancedContextTokens")) parts.add("進階情境 " + count(op,"advancedContextTokens") + " tokens");
        if (!parts.isEmpty()) {
            ui.gap(card, 4);
            card.addView(ui.text(String.join("  ·  ", parts), 14, MUTED, false));
        }
        JSONObject models = op.optJSONObject("modelTokens");
        if (models != null) {
            List<String> names = new ArrayList<>();
            models.keys().forEachRemaining(names::add);
            Collections.sort(names);
            for (String key : names) card.addView(ui.text(key + "　" + count(models,key) + " tokens", 13, MUTED, false));
        }
    }
}
