package tw.codeviva.cardreader;

import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        if (usage.optBoolean("inferredPages"))
            total.addView(ui.text("含依上傳內容推估的頁數", 13, MUTED, false));
        JSONArray operations = usage.optJSONArray("operations");
        if (operations != null) {
            for (int i = 0; i < operations.length(); i++) {
                JSONObject op = operations.optJSONObject(i);
                if (op != null && op.has("requestedMode")) pageDetails(total, op);
            }
        }
        card.addView(total);
        ui.gap(card, 14);
        boolean apiTiming = usage.has("apiElapsedMs");
        LinearLayout tokens = disclosure(card, "Token 用量", tokenText(usage), "tokens");
        ui.line(card);
        LinearLayout time = disclosure(card, apiTiming ? "辨識時間" : "舊版處理時間", ScanUsage.timeText(usage), "time");
        if (!apiTiming) time.addView(ui.text("這筆紀錄只保存整體處理時間，各 API 耗時未記錄。", 14, MUTED, false));

        if (operations == null || operations.length() == 0) {
            tokens.addView(ui.text("沒有分項用量紀錄。", 14, MUTED, false));
            if (apiTiming) time.addView(ui.text("沒有 API 呼叫紀錄。", 14, MUTED, false));
        } else {
            Map<String, List<JSONObject>> byStage = new LinkedHashMap<>();
            byStage.put(ScanUsage.DECISIONS, new ArrayList<>());
            byStage.put(ScanUsage.CONTENT, new ArrayList<>());
            for (int i = 0; i < operations.length(); i++) {
                JSONObject op = operations.optJSONObject(i);
                if (op != null) byStage.computeIfAbsent(op.optString("stage"), key -> new ArrayList<>()).add(op);
            }
            for (Map.Entry<String, List<JSONObject>> stageCalls : byStage.entrySet()) {
                String stage = stageCalls.getKey();
                List<JSONObject> calls = stageCalls.getValue();
                if (calls.isEmpty()) {
                    entry(tokens, stage, apiTiming ? "未呼叫" : "未記錄");
                    entry(time, stage, apiTiming ? "未呼叫" : "未記錄");
                }
                for (int i = 0; i < calls.size(); i++) {
                    JSONObject op = calls.get(i);
                    String name = calls.size() > 1 ? stage + "（第 " + (i + 1) + " 次）" : stage;
                    entry(tokens, name, op.optLong("tokens", -1) == 0 && op.has("pages") && !op.optBoolean("partialTokens")
                            ? "按頁計量" : tokenText(op));
                    model(tokens, op);
                    tokenDetails(tokens, op);
                    entry(time, name, apiTiming ? ScanUsage.timeText(op) : "未記錄");
                    model(time, op);
                }
            }
        }
        ui.gap(card, 12);
        ui.line(card);
        ui.gap(card, 8);
        if (usage.optBoolean("partialCost")) card.addView(ui.text("小計僅含已知用量與單價。", 13, GREEN, true));
        card.addView(ui.text(usage.optString("rateBasis"), 12, MUTED, false));
        return card;
    }

    private void pageDetails(LinearLayout group, JSONObject op) {
        ui.gap(group, 8);
        group.addView(ui.text("內容擷取 · 規則選用 " + op.optString("requestedMode"), 14, GREEN, true));
        JSONArray charges = op.optJSONArray("pageCharges");
        if (charges == null) {
            group.addView(ui.text("Azure 未回報計費頁數，擷取費用待確認。", 13, MUTED, false));
            return;
        }
        String source = op.optBoolean("inferredPages") ? "推估" : "Azure 回報";
        if (charges.length() == 0) group.addView(ui.text(source + " 0 頁 · US$0", 13, MUTED, false));
        for (int i = 0; i < charges.length(); i++) {
            JSONObject line = charges.optJSONObject(i);
            if (line == null) continue;
            String mode = line.optString("mode") + (line.optBoolean("inline") ? " 同步" : "");
            group.addView(ui.text(source + " " + mode + " " + count(line, "pages") + " 頁 · "
                    + money(line) + "（US$" + line.optString("perThousandUsd") + "／千頁）", 13, MUTED, false));
        }
    }

    private LinearLayout disclosure(LinearLayout card, String label, String value, String key) {
        LinearLayout toggle = ui.row();
        toggle.setTag("usage-" + key + "-toggle");
        toggle.setMinimumHeight(ui.dp(56));
        toggle.setFocusable(true);
        toggle.setBackground(new RippleDrawable(ColorStateList.valueOf(0x241e8a73), ui.bg(WHITE, 8, false), null));
        ui.pad(toggle, 3, 5);
        TextView name = ui.text(label, 15, INK, true);
        ui.weight(toggle, name);
        TextView number = ui.text(value, 17, GREEN, true);
        toggle.addView(number);
        ui.gap(toggle, 10);
        TextView arrow = ui.text("▾", 20, GREEN, true);
        toggle.addView(arrow);
        card.addView(toggle);
        LinearLayout details = ui.col();
        details.setTag("usage-" + key + "-details");
        ui.pad(details, 8, 8);
        details.setVisibility(View.GONE);
        card.addView(details);
        String description = label + "，" + value + "，";
        toggle.setContentDescription(description + "展開明細");
        toggle.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        toggle.setOnClickListener(v -> {
            boolean expand = details.getVisibility() != View.VISIBLE;
            details.setVisibility(expand ? View.VISIBLE : View.GONE);
            arrow.setText(expand ? "▴" : "▾");
            toggle.setContentDescription(description + (expand ? "收合明細" : "展開明細"));
        });
        return details;
    }

    private static String tokenText(JSONObject op) {
        if (op.isNull("tokens") || (op.optBoolean("partialTokens") && op.optLong("tokens") == 0))
            return "未完整回報";
        return (op.optBoolean("partialTokens") ? "已知 " : "") + count(op, "tokens");
    }

    private void model(LinearLayout group, JSONObject op) {
        String name = op.optString("model", op.optString("analyzer"));
        if (!name.isEmpty()) group.addView(ui.text(name, 13, MUTED, false));
    }

    private static String money(JSONObject obj) {
        return obj.isNull("usd") ? "未取得" : "US$" + obj.optString("usd");
    }

    private static String count(JSONObject obj, String key) {
        return NumberFormat.getIntegerInstance(Locale.TAIWAN).format(obj.optLong(key));
    }

    private void entry(LinearLayout group, String stage, String value) {
        if (group.getChildCount() > 0) {
            ui.gap(group, 10);
            ui.line(group);
            ui.gap(group, 10);
        }
        LinearLayout heading = stacked ? ui.col() : ui.row();
        group.addView(heading);
        TextView name = ui.text(stage, 15, INK, true);
        if (stacked) heading.addView(name); else ui.weight(heading, name);
        TextView amount = ui.text(value, 15, INK, true);
        amount.setGravity(stacked ? Gravity.START : Gravity.END);
        heading.addView(amount);
    }

    private void tokenDetails(LinearLayout card, JSONObject op) {
        List<String> parts = new ArrayList<>();
        if (op.has("input")) parts.add("輸入 " + count(op,"input"));
        if (op.has("output")) parts.add("輸出 " + count(op,"output"));
        if (op.has("pages")) parts.add(count(op,"pages") + (op.optBoolean("inferredPages") ? (op.optString("requestedMode").equals("Minimal") ? " 頁（依文字長度估計）" : " 頁（依上傳影像估計）") : " 頁"));
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
        ui.gap(card, 4);
        String costLabel = op.has("usd") && !op.optBoolean("priced", true) ? "已知費用小計 " : "預估費用 ";
        card.addView(ui.text(costLabel + money(op), 13, MUTED, false));
    }
}
