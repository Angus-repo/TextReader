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
        final ExtractionMode extraction;
        final boolean explicitExtraction;

        Card(JSONObject json) throws JSONException {
            code = json.getString("code");
            label = json.getString("label");
            explicitExtraction = json.has("extractionMode");
            extraction = ExtractionMode.forRule(json.optString("extractionMode"), code);
            criteria = json.getString("criteria");
            fields = json.getJSONArray("fields");
            autoFields = json.optBoolean("autoFields") && hasFields();
        }

        boolean hasFields() {
            return fields.length() > 0;
        }

        String fieldLabel(String key) {
            for (int i = 0; i < fields.length(); i++) {
                JSONObject field = fields.optJSONObject(i);
                if (key.equals(field.optString("key"))) return field.optString("label");
            }
            return null;
        }

        String textAnalyzer() {
            return extraction.analyzer;
        }

        JSONObject schema(String model) throws JSONException {
            JSONObject definitions = new JSONObject();
            for (int i = 0; i < fields.length(); i++) {
                JSONObject field = fields.getJSONObject(i);
                String description = field.getString("label") + "。" + field.getString("description")
                    + " 空白或無法辨讀請省略，不推測。";
                definitions.put(field.getString("key"), obj("type", "string", "method", "extract",
                    "description", description));
            }
            JSONObject config = obj("enableOcr", extraction != ExtractionMode.MINIMAL, "returnDetails", true, "estimateFieldSourceAndConfidence", true);
            if (explicitExtraction) config.put("enableLayout", extraction == ExtractionMode.STANDARD);
            if (code.equals("id_back") && extraction != ExtractionMode.MINIMAL) config.put("enableBarcode", true);
            return obj("baseAnalyzerId", "prebuilt-document", "description", label,
                "models", obj("completion", model, "embedding", "text-embedding-3-large"),
                "config", config,
                "fieldSchema", obj("name", "DocumentFields", "fields", definitions));
        }

        String analyzerId(String model) throws JSONException {
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
                Card card = new Card(rows.getJSONObject(i));
                defaults.put(card.code, card);
            }
        }
        reload();
    }

    void reload() throws JSONException {
        LinkedHashMap<String, Card> next = new LinkedHashMap<>(defaults);
        for (String code : new TreeSet<>(preferences.getAll().keySet())) {
            JSONObject json = new JSONObject(preferences.getString(code, ""));
            if (LicenseFields.supports(code) && previousFactoryFields(json.getJSONArray("fields")))
                json.put("fields", defaults.get(code).fields);
            next.put(code, new Card(json));
        }
        Card fallback = next.remove(OTHER);
        next.put(OTHER, fallback);
        cards.clear();
        cards.putAll(next);
    }

    // Older releases also saved all default fields when only the name, classifier or auto-read
    // switch changed. Match the exact shipped keys, labels, descriptions and order before upgrading.
    private static boolean previousFactoryFields(JSONArray fields) throws JSONException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < fields.length(); i++) {
                JSONObject field = fields.getJSONObject(i);
                for (String property : Arrays.asList("key", "label", "description")) {
                    digest.update(field.getString(property).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                }
            }
            String fingerprint = String.format(Locale.ROOT, "%064x", new java.math.BigInteger(1, digest.digest()));
            // The three previously released revisions of document-types.json, front / back.
            return Arrays.asList(
                "33ddd625b007818d09849da37a35f0f3ff78b4f0d85e8e0248e4a77a5d8e7758",
                "845250922eaa57e51027aba8a160d8448c1b03509ee7df1087f0ac3e58203f66",
                "e0d19e513adab8df56deef25681607f67ce3ef55adee58d04764123b61a53b56",
                "d243d416117c5a953a4d83cfa82dd64b5bb0371c375cedbfde21d0631c82d294",
                "e5ffb4dba29e4e787c11e2571363d160fbc851e98a219151c5188cded79fd126",
                "ac6eee9228c4079231426ed37c89c41a567a04e5faa65f5ea42beb351a24fc70").contains(fingerprint);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new AssertionError(unavailable);
        }
    }

    Collection<Card> builtInTemplates() {
        return Collections.unmodifiableCollection(defaults.values());
    }

    boolean builtIn(String code) {
        return defaults.containsKey(code);
    }

    boolean customized(String code) {
        return preferences.contains(code);
    }

    void save(String code, String label, String criteria, JSONArray fields, boolean autoFields) throws Exception {
        Card previous = cards.get(code);
        save(code, label, criteria, fields, autoFields, previous != null && previous.explicitExtraction ? previous.extraction : null);
    }

    void save(String code, String label, String criteria, JSONArray fields, boolean autoFields, ExtractionMode extraction) throws Exception {
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
            "fields", fields, "autoFields", autoFields && fields.length() > 0, "extractionMode", extraction == null ? null : extraction.label);
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
