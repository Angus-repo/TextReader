package tw.codeviva.cardreader;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class CardCatalog {
    final LinkedHashMap<String, Card> cards = new LinkedHashMap<>();
    static final String OTHER = "other";
    static final class Card {
        final String code, label, criteria;
        final JSONArray fields;
        Card(JSONObject json) throws JSONException {
            code = json.getString("code");
            label = json.getString("label");
            criteria = json.getString("criteria");
            fields = json.getJSONArray("fields");
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
                JSONObject f = fields.getJSONObject(i);
                definitions.put(f.getString("key"), obj("type","string","method","extract",
                    "description",f.getString("description") + " 逐字擷取本面可見值，保留原始紀年與前導零。空白或無法辨讀請省略，不推測。"));
            }
            return obj("baseAnalyzerId","prebuilt-document","description","台灣" + label,
                "models",obj("completion",model,"embedding","text-embedding-3-large"),
                "config",obj("enableOcr",true,"returnDetails",true,"estimateFieldSourceAndConfidence",true),
                "fieldSchema",obj("name","TaiwanCard","fields",definitions));
        }
        String analyzerId(String model) { return "twcard-v1-" + code + "-" + model; }
    }
    CardCatalog(Context context) throws Exception {
        try (InputStream in = context.getAssets().open("document-types.json")) {
            JSONArray rows = new JSONArray(read(in, 100000));
            for (int i=0; i<rows.length(); i++) {
                Card card = new Card(rows.getJSONObject(i));
                cards.put(card.code, card);
            }
        }
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
