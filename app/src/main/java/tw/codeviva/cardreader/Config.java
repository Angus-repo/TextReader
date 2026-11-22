package tw.codeviva.cardreader;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.*;
import android.util.Base64;
import org.json.JSONObject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Locale;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

final class Config {
    static final String MS = "/providers/microsoft/v1/systemone", OA = "/openai/v1/decisions";
    boolean openAI;
    ExtractionMode extractionOverride; // null follows the individual document rules.
    JSONObject pageRates = new JSONObject();
    String decisionRate = "", completionInput = "", completionOutput = "", completionCached = "";
    String decisionRoot="", cuRoot="", model="Microsoft-Decision-1.1", cuModel="gpt-5.2", decisionKey="", cuKey="";
    static String endpoint(String raw, String suffix) {
        try {
            URI u = new URI(raw.trim());
            String host = u.getHost().toLowerCase(Locale.ROOT);
            String path = u.getPath().replaceAll("/+$","");
            boolean azure = host.endsWith(".services.ai.azure.com") || host.endsWith(".cognitiveservices.azure.com") || host.endsWith(".openai.azure.com");
            if (!"https".equalsIgnoreCase(u.getScheme()) || !azure || u.getPort()!=-1 || u.getUserInfo()!=null ||
                u.getRawQuery()!=null || u.getRawFragment()!=null || !(path.isEmpty() || path.equals(suffix) || (suffix.equals(OA)&&path.equals("/openai/v1"))))
                throw new IllegalArgumentException();
            return "https://" + host;
        } catch (Exception e) { throw new IllegalArgumentException("請填 Azure 公有雲 HTTPS 資源端點，例如 https://資源名稱.services.ai.azure.com。"); }
    }
    static boolean validRate(String rate) {
        return rate.isEmpty() || rate.matches("(?:0|[1-9][0-9]{0,5})(?:\\.[0-9]{1,9})?");
    }
    void validate() {
        java.util.List<String> rates = new java.util.ArrayList<>(java.util.Arrays.asList(decisionRate, completionInput, completionOutput, completionCached));
        for (ExtractionMode mode : ExtractionMode.values()) rates.add(pageRates.optString(mode.label));
        for (String rate : rates) {
            if (!validRate(rate))
                throw new IllegalArgumentException("單價請輸入非負美元數字，例如 0.042；留空可使用參考價。");
        }
        decisionRoot = endpoint(decisionRoot,openAI?OA:MS);
        cuRoot = endpoint(cuRoot,"/contentunderstanding");
        if (model.isEmpty() || decisionKey.isEmpty() || cuKey.isEmpty()) throw new IllegalArgumentException("請填寫部署名稱與兩個服務的 API 金鑰。");
        if (!cuModel.matches("[a-z0-9][a-z0-9._-]{0,32}")) throw new IllegalArgumentException("請填寫 Content Understanding 支援的基礎模型名稱。");
    }
    boolean ready() { return !decisionRoot.isEmpty() && !cuRoot.isEmpty(); }
    private static final String ALIAS = "tw-card-reader-config-v1";
    private static SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance("AES","AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    static void forget(Context c) {
        c.getSharedPreferences("connection",0).edit().clear().apply();
    }
    JSONObject toJson() throws org.json.JSONException {
        return CardCatalog.obj("openAI",openAI,"decisionRoot",decisionRoot,"cuRoot",cuRoot,"model",model,
            "extractionOverride",extractionOverride == null ? null : extractionOverride.label,
            "pageRates",pageRates,"decisionRate",decisionRate,"completionInput",completionInput,"completionOutput",completionOutput,"completionCached",completionCached,
            "cuModel",cuModel,"decisionKey",decisionKey,"cuKey",cuKey);
    }
    void save(Context c) throws Exception {
        validate();
        byte[] plain=toJson().toString().getBytes(StandardCharsets.UTF_8);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,key());
        String payload=Base64.encodeToString(cipher.doFinal(plain),Base64.NO_WRAP);
        String iv=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP);
        if (!c.getSharedPreferences("connection",0).edit().putString("iv",iv).putString("data",payload).commit())
            throw new java.io.IOException("無法儲存連線設定。");
    }
    static Config load(Context c) throws Exception {
        SharedPreferences p=c.getSharedPreferences("connection",0);
        if (!p.contains("data")) return new Config();
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(p.getString("iv",""),0)));
        JSONObject j=new JSONObject(new String(cipher.doFinal(Base64.decode(p.getString("data",""),0)),StandardCharsets.UTF_8));
        return fromJson(j);
    }
    static Config fromJson(JSONObject j) throws org.json.JSONException {
        Config cfg=new Config();
        cfg.openAI=j.getBoolean("openAI"); cfg.decisionRoot=j.getString("decisionRoot"); cfg.cuRoot=j.getString("cuRoot");
        cfg.model=j.getString("model"); cfg.cuModel=j.getString("cuModel");
        cfg.decisionKey=j.getString("decisionKey"); cfg.cuKey=j.getString("cuKey");
        cfg.extractionOverride = ExtractionMode.fromLabel(j.optString("extractionOverride"));
        if (j.optJSONObject("pageRates") != null) cfg.pageRates = j.getJSONObject("pageRates");
        cfg.decisionRate=j.optString("decisionRate"); cfg.completionInput=j.optString("completionInput");
        cfg.completionOutput=j.optString("completionOutput"); cfg.completionCached=j.optString("completionCached");
        cfg.validate();
        return cfg;
    }
}
