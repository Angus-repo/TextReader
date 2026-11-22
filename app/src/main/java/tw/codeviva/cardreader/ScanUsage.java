package tw.codeviva.cardreader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Iterator;
import java.util.Locale;

import static tw.codeviva.cardreader.CardCatalog.obj;

/** One image owns all its submitted operations, including retries. Rates are USD per million tokens or per thousand pages. */
final class ScanUsage {
    static final String LEGACY = "Token、耗時與費用未記錄";
    private final JSONArray operations = new JSONArray();
    static final String DECISIONS = "Decisions 分類", CONTENT = "Content Understanding 讀取";

    /** The initial POST and every status poll share one monotonic interval. */
    static final class ApiWindow {
        private final JSONObject operation;
        private long firstSend = -1;

        ApiWindow(JSONObject operation) { this.operation = operation; }

        void sending() {
            if (firstSend < 0) firstSend = android.os.SystemClock.elapsedRealtime();
        }

        void received() throws JSONException {
            operation.put("apiElapsedMs", android.os.SystemClock.elapsedRealtime() - firstSend);
        }
    }

    JSONObject begin(String name) throws JSONException {
        JSONObject op = obj("stage", name);
        operations.put(op);
        return op;
    }

    static void decision(JSONObject op, JSONObject body, Config cfg) throws JSONException {
        JSONObject u = body.optJSONObject("usage");
        if (u == null) return;
        long input = u.optLong("input_tokens", u.optLong("prompt_tokens", -1));
        long output = u.optLong("output_tokens", u.optLong("completion_tokens", -1));
        long total = u.optLong("total_tokens", -1);
        if (total < 0 && input >= 0 && (output >= 0 || !cfg.openAI)) total = input + Math.max(0, output);
        if (total >= 0) op.put("tokens", total);
        if (input >= 0) op.put("input", input);
        if (output >= 0) op.put("output", output);
        if (input < 0) return;
        JSONObject details = u.optJSONObject("input_tokens_details");
        long cached = cfg.openAI && details != null ? details.optLong("cached_tokens", 0) : 0;
        long written = cfg.openAI && details != null ? details.optLong("cache_write_tokens", 0) : 0;
        if (cached < 0 || written < 0 || cached + written > input) return;
        BigDecimal ordinary = cfg.decisionRate.isEmpty() ? new BigDecimal(cfg.openAI ? "0.10" : "0.042") : new BigDecimal(cfg.decisionRate);
        BigDecimal cost = charge(input - cached - written, ordinary, 6);
        cost = cost.add(charge(cached, new BigDecimal("0.01"), 6)).add(charge(written, new BigDecimal("0.125"), 6));
        op.put("model", cfg.model).put("inputRate", ordinary.toPlainString());
        op.put("usd", cost.stripTrailingZeros().toPlainString()).put("priced", true);
    }

    private static BigDecimal charge(long units, BigDecimal rate, int places) {
        return rate.multiply(BigDecimal.valueOf(units)).movePointLeft(places);
    }

    static void document(JSONObject op, JSONObject u, boolean structured, String analyzer, Config cfg) throws JSONException {
        JSONArray pageCharges = new JSONArray();
        BigDecimal cost = BigDecimal.ZERO;
        long pages = 0;
        boolean reported = false;
        for (ExtractionMode mode : ExtractionMode.values()) {
            for (boolean inline : new boolean[]{false, true}) {
                long units = count(u, mode.meter() + (inline ? "Inline" : ""));
                if (units < 0) continue;
                reported = true;
                pages += units;
                if (units > 0) cost = cost.add(pageCharge(pageCharges, mode, units, inline, cfg));
            }
        }
        boolean knownCharge = reported;
        if (!reported) {
            // A JPEG sent to Read/Layout is exactly one input page. TXT uses 3,000 characters per page.
            for (ExtractionMode mode : ExtractionMode.values()) {
                if (!mode.analyzer.equals(analyzer)) continue;
                pages = mode == ExtractionMode.MINIMAL ? op.optLong("textPageEstimate", 0) : 1;
                if (pages > 0) {
                    cost = pageCharge(pageCharges, mode, pages, false, cfg);
                    knownCharge = true;
                    op.put("inferredPages", true);
                }
            }
        }
        if (knownCharge) op.put("pages", pages).put("pageCharges", pageCharges)
                .put("pageUsd", cost.stripTrailingZeros().toPlainString());
        boolean priced = knownCharge;
        long context = count(u, "contextualizationTokens");
        long advanced = count(u, "advancedContextualizationTokens");
        long tokens = Math.max(0,context) + Math.max(0,advanced);
        if (context >= 0) op.put("contextTokens", context);
        if (advanced >= 0) op.put("advancedContextTokens", advanced);
        if (context >= 0 || advanced >= 0) knownCharge = true;
        cost = cost.add(charge(Math.max(0,context), BigDecimal.ONE, 6))
                .add(charge(Math.max(0,advanced), new BigDecimal("3"), 6));
        JSONObject modelUsage = u == null ? null : u.optJSONObject("tokens");
        boolean hasModelTokens = false;
        if (modelUsage != null) {
            JSONObject counts = new JSONObject();
            for (Iterator<String> it = modelUsage.keys(); it.hasNext();) {
                String key = it.next();
                long n = count(modelUsage, key);
                if (n < 0) { priced = false; continue; }
                hasModelTokens = true;
                counts.put(key, n);
                tokens += n;
                BigDecimal rate = modelRate(key, cfg);
                if (rate == null && n > 0) priced = false;
                if (rate != null) {
                    knownCharge = true;
                    cost = cost.add(charge(n, rate, 6));
                }
            }
            op.put("modelTokens", counts);
        }
        if (!structured || context >= 0 || advanced >= 0 || hasModelTokens) op.put("tokens", tokens);
        boolean completeTokens = !structured || (hasModelTokens && context >= 0);
        op.put("partialTokens", !completeTokens);
        if (knownCharge) op.put("usd", cost.stripTrailingZeros().toPlainString());
        op.put("priced", priced && completeTokens);
    }

    private static BigDecimal pageCharge(JSONArray charges, ExtractionMode mode, long pages, boolean inline,
                                         Config cfg) throws JSONException {
        BigDecimal rate = inline ? mode.referenceRate.multiply(new BigDecimal("1.5"))
                : mode.asyncRate(cfg);
        BigDecimal amount = charge(pages, rate, 3);
        charges.put(obj("mode", mode.label, "inline", inline, "pages", pages,
                "perThousandUsd", rate.stripTrailingZeros().toPlainString(), "usd", amount.stripTrailingZeros().toPlainString()));
        return amount;
    }

    private static long count(JSONObject u, String key) {
        return u == null || u.isNull(key) ? -1 : u.optLong(key, -1);
    }

    private static BigDecimal modelRate(String meter, Config cfg) {
        String key = meter.toLowerCase(Locale.ROOT);
        if (key.equals("text-embedding-3-large") || key.equals("text-embedding-3-large-input")) return new BigDecimal("0.13");
        boolean cached = key.endsWith("-cached-input") || key.endsWith("-cache-read");
        boolean input = key.endsWith("-input"), output = key.endsWith("-output");
        if (!cached && !input && !output) return null;
        String suffix = cached ? (key.endsWith("-cache-read") ? "-cache-read" : "-cached-input") : input ? "-input" : "-output";
        String model = key.substring(0, key.length()-suffix.length());
        if (model.equals(cfg.cuModel)) {
            String rate = cached ? cfg.completionCached : input ? cfg.completionInput : cfg.completionOutput;
            if (!rate.isEmpty()) return new BigDecimal(rate);
        }
        if (model.matches("gpt-5\\.2(?:-chat)?(?:-\\d{4}-\\d{2}-\\d{2})?"))
            return new BigDecimal(cached ? "0.175" : input ? "1.75" : "14");
        return null;
    }

    JSONObject snapshot() throws JSONException {
        long total = 0, apiMillis = 0;
        boolean anyTokens = false, incompleteTokens = false, anyCost = false, incompleteCost = false;
        BigDecimal usd = BigDecimal.ZERO;
        boolean inferredPages = false;
        for (int i=0; i<operations.length(); i++) {
            JSONObject op = operations.getJSONObject(i);
            apiMillis += op.optLong("apiElapsedMs");
            if (op.has("tokens")) { total += op.getLong("tokens"); anyTokens = true; }
            incompleteTokens |= !op.has("tokens") || op.optBoolean("partialTokens");
            if (op.has("usd")) { usd = usd.add(new BigDecimal(op.getString("usd"))); anyCost = true; }
            incompleteCost |= !op.optBoolean("priced");
            inferredPages |= op.optBoolean("inferredPages");
        }
        JSONObject result = obj("apiElapsedMs", apiMillis, "partialTokens", incompleteTokens, "partialCost", incompleteCost,
                "rateBasis", "依用量與美元單價換算（參考價 2026-11-22，可自訂）。實際扣款以 Azure 帳單為準。", "operations", new JSONArray(operations.toString()));
        if (inferredPages) result.put("inferredPages", true);
        if (anyTokens) result.put("tokens", total);
        if (anyCost) result.put("usd", usd.stripTrailingZeros().toPlainString());
        return result;
    }

    static String summary(JSONObject usage) {
        if (usage == null) return LEGACY;
        String tokens = usage.has("tokens") ? (usage.optBoolean("partialTokens") ? "已知 " : "")
                + NumberFormat.getIntegerInstance(Locale.TAIWAN).format(usage.optLong("tokens")) + " tokens" : "Token 未回報";
        if (usage.optBoolean("partialTokens") && usage.optLong("tokens", 0) == 0) tokens = "Token 未完整回報";
        String time = (usage.has("apiElapsedMs") ? "API " : "舊版處理 ") + timeText(usage);
        String price = usage.has("usd") ? (usage.optBoolean("partialCost") ? "已知小計 " : "估計 ")
                + "US$" + usage.optString("usd") : "費用未取得";
        return tokens + " · " + time + " · " + price + (usage.optBoolean("inferredPages") ? "（含推估頁數）" : "");
    }

    static String timeText(JSONObject usage) {
        long millis = usage.optLong("apiElapsedMs", usage.optLong("elapsedMs", -1));
        return millis < 0 ? "未記錄" : String.format(Locale.TAIWAN, "%.2f 秒", millis / 1000.0);
    }
}
