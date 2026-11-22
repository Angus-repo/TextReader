package tw.codeviva.cardreader;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class CardCatalog {
    static final String OTHER = "other";
    final LinkedHashMap<String, Card> cards = new LinkedHashMap<>();
    private final LinkedHashMap<String, Card> defaults = new LinkedHashMap<>();
    private final SharedPreferences preferences;

    static final class Card {
        final String code, label, criteria;
        final JSONArray fields;
        final boolean autoFields;
        private final boolean legacySchema;

        private Card(JSONObject json, Card original, boolean builtIn) throws JSONException {
            code = json.getString("code");
            label = json.getString("label");
            criteria = json.getString("criteria");
            fields = json.getJSONArray("fields");
            autoFields = json.optBoolean("autoFields") && hasFields();
            legacySchema = builtIn || (original != null && original.label.equals(label)
                && original.fields.toString().equals(fields.toString()));
        }

        boolean hasFields() {
            return fields.length() > 0;
        }

        String textAnalyzer() {
            return code.equals("receipt") || code.equals("form") ? "prebuilt-layout" : "prebuilt-read";
        }

        JSONObject schema(String model) throws JSONException {
            JSONObject definitions = new JSONObject();
            for (int i = 0; i < fields.length(); i++) {
                JSONObject field = fields.getJSONObject(i);
                String description = field.getString("description");
                if (legacySchema) {
                    description += " 逐字擷取本面可見值，保留原始紀年與前導零。空白或無法辨讀請省略，不推測。";
                } else {
                    description = field.getString("label") + "。" + description + " 空白或無法辨讀請省略，不推測。";
                }
                definitions.put(field.getString("key"), obj("type", "string", "method", "extract",
                    "description", description));
            }
            return obj("baseAnalyzerId", "prebuilt-document", "description", legacySchema ? "台灣" + label : label,
                "models", obj("completion", model, "embedding", "text-embedding-3-large"),
                "config", obj("enableOcr", true, "returnDetails", true, "estimateFieldSourceAndConfidence", true),
                "fieldSchema", obj("name", legacySchema ? "TaiwanCard" : "DocumentFields", "fields", definitions));
        }

        String analyzerId(String model) throws JSONException {
            if (legacySchema) return "twcard-v1-" + code + "-" + model;
            // Azure analyzers are immutable here. Identical extraction rules share the same analyzer.
            try {
                byte[] bytes = (code + "\n" + schema(model).toString()).getBytes(StandardCharsets.UTF_8);
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
                StringBuilder id = new StringBuilder("textreader-v2-");
                for (int i = 0; i < 16; i++) id.append(String.format(Locale.ROOT, "%02x", digest[i] & 255));
                return id.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new AssertionError(e);
            }
        }
    }

    CardCatalog(Context context) throws Exception {
        preferences = context.getSharedPreferences("document_rules_v1", Context.MODE_PRIVATE);
        try (InputStream in = context.getAssets().open("document-types.json")) {
            JSONArray rows = new JSONArray(read(in, 100000));
            for (int i = 0; i < rows.length(); i++) {
                Card card = new Card(rows.getJSONObject(i), null, true);
                defaults.put(card.code, card);
            }
        }
        reload();
    }

    void reload() throws JSONException {
        LinkedHashMap<String, Card> next = new LinkedHashMap<>(defaults);
        for (String code : new TreeSet<>(preferences.getAll().keySet())) {
            JSONObject json = new JSONObject(preferences.getString(code, ""));
            Card card = new Card(json, defaults.get(code), false);
            next.put(code, card);
        }
        Card fallback = next.remove(OTHER);
        next.put(OTHER, fallback);
        cards.clear();
        cards.putAll(next);
    }

    boolean builtIn(String code) {
        return defaults.containsKey(code);
    }

    boolean customized(String code) {
        return preferences.contains(code);
    }

    void save(String code, String label, String criteria, JSONArray fields, boolean autoFields) throws Exception {
        label = label.trim();
        criteria = criteria.trim();
        if (label.isEmpty() || criteria.isEmpty())
            throw new IllegalArgumentException("請填寫類型名稱及分類描述。");
        for (Card card : cards.values()) {
            if (!card.code.equals(code) && card.label.equalsIgnoreCase(label))
                throw new IllegalArgumentException("類型名稱已存在，請改用其他名稱。");
        }
        Set<String> labels = new HashSet<>();
        for (int i = 0; i < fields.length(); i++) {
            JSONObject field = fields.getJSONObject(i);
            String name = field.getString("label").trim();
            String description = field.getString("description").trim();
            if (name.isEmpty() || description.isEmpty())
                throw new IllegalArgumentException("第 " + (i + 1) + " 個欄位缺少名稱或讀取描述。");
            if (!labels.add(name.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("欄位名稱重複：" + name);
            field.put("label", name);
            field.put("description", description);
        }
        JSONObject json = obj("code", code, "label", label, "criteria", criteria,
            "fields", fields, "autoFields", autoFields && fields.length() > 0);
        if (!preferences.edit().putString(code, json.toString()).commit()) throw new IOException("無法儲存辨識規則。");
        reload();
    }

    void reset(String code) throws Exception {
        if (!preferences.edit().remove(code).commit()) throw new IOException("無法更新辨識規則。");
        reload();
    }

    static JSONObject obj(Object... pairs) throws JSONException {
        JSONObject result = new JSONObject();
        for (int i=0; i<pairs.length; i+=2) result.put((String)pairs[i], pairs[i+1]);
        return result;
    }
    static String read(InputStream in, int max) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n=in.read(buf))!=-1;) {
            if (out.size()+n > max) throw new IOException("服務回應過大。");
            out.write(buf,0,n);
        }
        return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }
}
