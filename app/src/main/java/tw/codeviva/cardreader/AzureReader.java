package tw.codeviva.cardreader;

import android.graphics.Bitmap;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.LinkedHashSet;
import javax.net.ssl.HttpsURLConnection;
import static tw.codeviva.cardreader.CardCatalog.obj;

final class AzureReader {
    static final String VERSION="?api-version=2025-11-01";
    static final String QUESTION="document_type";
    final CardCatalog catalog;
    final Config config;
    final Transport transport;
    final Sleeper sleeper;
    interface Progress { void show(String message); }
    interface Sleeper { void pause(long ms) throws InterruptedException; }
    interface Transport { Reply call(String method,String url,String header,String key,JSONObject data) throws Exception; }
    static final class Reply {
        final int code; final String location; final int seconds; final JSONObject body;
        final String requestId;
        Reply(int code,String location,int seconds,JSONObject body) {
            this(code, location, seconds, body, null);
        }
        Reply(int code, String location, int seconds, JSONObject body, String requestId) {
            this.requestId = requestId;
            this.code=code;this.location=location;this.seconds=seconds;this.body=body;
        }
        void check(String stage) throws IOException {
            if(code<200 || code>=300) throw new IOException(stage+"（HTTP "+code+"）"+errorCode(body)+"\n"+hint(code));
        }
    }
    private static void collectErrorDetails(Object item, LinkedHashSet<String> parts, int depth) {
        if (parts.size() >= 5 || depth > 5) return;
        if (item instanceof String) {
            String value = ((String) item).trim();
            if (!value.isEmpty()) parts.add(value);
        } else if (item instanceof JSONArray) {
            collectErrorDetails(((JSONArray) item).opt(0), parts, depth + 1);
        } else if (item instanceof JSONObject) {
            JSONObject object = (JSONObject) item;
            for (String name : new String[]{"error", "code", "message", "msg", "innererror", "innerError", "detail", "details"}) {
                if (object.has(name)) collectErrorDetails(object.opt(name), parts, depth + 1);
            }
        }
    }

    private static String classificationError(Reply reply, String key) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        collectErrorDetails(reply.body, parts, 0);
        String detail = String.join(" · ", parts);
        if (!key.isEmpty()) detail = detail.replace(key, "…");
        detail = detail.replaceAll("[A-Za-z0-9+/=_-]{64,}", "…").replaceAll("[\\p{Cntrl}]+", " ").trim();
        if (detail.length() > 320) detail = detail.substring(0, 320) + "…";
        String message = "Decisions 影像分類失敗（HTTP " + reply.code + "）";
        if (!detail.isEmpty()) message += "\n" + detail;
        if (reply.requestId != null && reply.requestId.matches("[A-Za-z0-9._:-]{1,128}"))
            message += "\n請求 ID：" + reply.requestId;
        return message + "\n" + (reply.code == 400 ? "可重新分類，或手動選擇這張影像的類型。" : hint(reply.code));
    }

    static String errorCode(JSONObject j) {
        JSONObject error=j.optJSONObject("error");
        String code=error==null?"":error.optString("code","");
        return code.matches("[A-Za-z0-9_.-]{1,96}")?" · "+code:"";
    }
    static String hint(int status) {
        switch(status) {
            case 400:return "請核對部署的模型、資源支援區域與 Content Understanding 預設模型對應。";
            case 401:case 403:return "請確認金鑰屬於此端點、已開放金鑰驗證，以及資源的網路與存取權限。";
            case 404:return "請核對資源端點、服務路徑與模型部署名稱。";
            case 429:return "服務目前限流，請稍後再試。";
            default:return "請檢查 Azure 資源與網路狀態後再試。";
        }
    }
    static final class Network implements Transport {
        public Reply call(String method,String url,String header,String key,JSONObject data) throws Exception {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            HttpsURLConnection c=(HttpsURLConnection)new URL(url).openConnection();
            try {
                c.setInstanceFollowRedirects(false); c.setUseCaches(false);
                c.setConnectTimeout(15000); c.setReadTimeout(65000); c.setRequestMethod(method);
                c.setRequestProperty(header,key); c.setRequestProperty("Accept","application/json");
                if(data!=null) {
                    byte[] bytes=data.toString().getBytes(StandardCharsets.UTF_8);
                    c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json");
                    c.setFixedLengthStreamingMode(bytes.length);
                    try(OutputStream out=c.getOutputStream()){out.write(bytes);}
                }
                int status=c.getResponseCode(); String raw;
                try(InputStream in=status>=400?c.getErrorStream():c.getInputStream()){raw=CardCatalog.read(in,8*1024*1024);}
                JSONObject result=new JSONObject();
                if(!raw.trim().isEmpty()) {
                    try {result=new JSONObject(raw);} catch(JSONException e) {
                        if(status>=200 && status<300) throw new IOException("Azure 未傳回有效 JSON，請核對端點。");
                        if (c.getContentType() != null && c.getContentType().startsWith("text/plain"))
                            result.put("message", raw.trim());
                    }
                }
                String requestId = c.getHeaderField("apim-request-id");
                if (requestId == null) requestId = c.getHeaderField("x-ms-request-id");
                if (requestId == null) requestId = c.getHeaderField("x-request-id");
                if (requestId == null) requestId = c.getHeaderField("request-id");
                return new Reply(status, c.getHeaderField("Operation-Location"),
                    Math.max(1, c.getHeaderFieldInt("Retry-After", 2)), result, requestId);
            } finally {c.disconnect();}
        }
    }
    AzureReader(CardCatalog catalog,Config config) { this(catalog,config,new Network(),Thread::sleep); }
    AzureReader(CardCatalog catalog,Config config,Transport transport,Sleeper sleeper) {
        this.catalog=catalog;this.config=config;this.transport=transport;this.sleeper=sleeper;
    }
    JSONObject decisionBody(String imageBase64) throws JSONException {
        JSONObject criteria=new JSONObject();
        for(CardCatalog.Card card:catalog.cards.values()) criteria.put(card.code,card.label + "：" + card.criteria);
        String instruction="依照各類型的分類描述比對整張影像，選出最符合的類型，留意描述中的正反面特徵。無法判定時選 other。";
        if (!config.openAI) {
            return obj("model",config.model,"state","Document image: image:card.jpg",
                "assets",obj("card.jpg",obj("type","image","format","jpeg","data_base64",imageBase64)),
                "questions",obj(QUESTION,obj("type","choice","instructions",instruction,"criteria",criteria)));
        }
        JSONArray choices=new JSONArray();
        for(java.util.Iterator<String> it=criteria.keys();it.hasNext();) {
            String code=it.next();choices.put(obj("value",code,"description",criteria.getString(code)));
        }
        return obj("model",config.model,"input",new JSONArray().put(obj("role","user","content",new JSONArray()
            .put(obj("type","input_text","text","請判斷這張影像的類型。")).put(obj("type","input_image","image_url","data:image/png;base64,"+imageBase64)))),
            "questions",new JSONArray().put(obj("name",QUESTION,"type","choice","instructions",instruction,"choices",choices)));
    }
    String classify(Bitmap preview) throws Exception {
        byte[] image = config.openAI ? CardPhoto.encoded(preview, Bitmap.CompressFormat.PNG, 100) : CardPhoto.decisionJpeg(preview);
        Reply r=transport.call("POST",config.decisionRoot+(config.openAI?Config.OA:Config.MS),"api-key",config.decisionKey,
            decisionBody(Base64.encodeToString(image,Base64.NO_WRAP)));
        if (r.code < 200 || r.code >= 300) throw new IOException(classificationError(r, config.decisionKey));
        return readChoice(r.body);
    }
    String readChoice(JSONObject body) throws Exception {
        JSONObject answer=null;
        if (config.openAI) {
            JSONArray answers=body.optJSONArray("answers");
            if(answers!=null) for(int i=0;i<answers.length();i++) {
                JSONObject item=answers.optJSONObject(i);
                if(item!=null && QUESTION.equals(item.optString("name"))) {answer=item;break;}
            }
        } else {
            JSONObject answers=body.optJSONObject("answers");
            if(answers!=null) answer=answers.optJSONObject(QUESTION);
        }
        if(answer==null) throw new IOException("Decisions 回應缺少類型判定。");
        if("refusal".equals(answer.optString("type"))) return CardCatalog.OTHER;
        if(!"choice".equals(answer.optString("type"))) throw new IOException("Decisions 回應的判定類型不符。");
        String code=answer.optString("choice");
        if(!catalog.cards.containsKey(code)) throw new IOException("服務回傳未知的影像類別。");
        return code;
    }
    Reply cu(String method,String url,JSONObject data) throws Exception {
        return transport.call(method,url,"Ocp-Apim-Subscription-Key",config.cuKey,data);
    }
    String operationUrl(String location) throws Exception {
        URI root=new URI(config.cuRoot);
        if(location==null) throw new IOException("Azure 未提供作業查詢位置。");
        URI next=root.resolve(location).normalize();
        String host=next.getHost();
        boolean alias=host!=null && root.getHost().split("\\.")[0].equalsIgnoreCase(host.split("\\.")[0]) &&
            (host.endsWith(".services.ai.azure.com")||host.endsWith(".cognitiveservices.azure.com")) &&
            host.split("\\.").length==(host.endsWith(".services.ai.azure.com")?5:4);
        if(!"https".equalsIgnoreCase(next.getScheme()) || next.getUserInfo()!=null || next.getPort()!=-1 ||
            !(root.getHost().equalsIgnoreCase(host)||alias) || !next.getPath().startsWith("/contentunderstanding/"))
            throw new IOException("Azure 作業回傳的查詢位置不屬於設定的資源。");
        return next.toString();
    }
    JSONObject waitFor(Reply initial,String stage,boolean requireResult) throws Exception {
        initial.check(stage);
        if (initial.location==null && initial.code!=202 && (!requireResult || initial.body.has("result"))) return initial.body;
        String url=operationUrl(initial.location); Reply current=initial;
        long deadline=System.nanoTime()+300_000_000_000L;
        while(System.nanoTime()<deadline) {
            sleeper.pause(Math.min(current.seconds,60)*1000L);
            current=cu("GET",url,null);
            if(current.code==429 || current.code==502 || current.code==503 || current.code==504) continue;
            current.check(stage);
            switch(current.body.optString("status").toLowerCase(Locale.ROOT)) {
                case "succeeded":return current.body;
                case "running":case "notstarted":continue;
                case "failed":case "canceled":case "cancelled":throw new IOException(stage+errorCode(current.body)+"。請檢查模型部署與預設對應。");
                default:throw new IOException("Azure 回傳未知的作業狀態。");
            }
        }
        throw new IOException("等待 Azure 逾時。作業可能仍在雲端執行。");
    }
    void ensureAnalyzer(CardCatalog.Card card,String url,Progress p) throws Exception {
        Reply existing=cu("GET",url+VERSION,null);
        if(existing.code==404) {
            p.show("首次建立"+card.label+"分析器…");
            Reply create=cu("PUT",url+VERSION+"&allowReplace=false",card.schema(config.cuModel));
            if(create.code!=409) {waitFor(create,"建立分析器失敗",false);return;}
            existing=cu("GET",url+VERSION,null);
        }
        for(int i=0;i<60;i++) {
            existing.check("讀取分析器失敗");
            String state=existing.body.optString("status","ready");
            if(state.equalsIgnoreCase("ready") || state.equalsIgnoreCase("succeeded")) return;
            if(!(state.equalsIgnoreCase("creating") || state.equalsIgnoreCase("training")))
                throw new IOException("分析器無法使用，狀態為 "+state+"。");
            sleeper.pause(2000); existing=cu("GET",url+VERSION,null);
        }
        throw new IOException("等待分析器就緒逾時。");
    }
    static final class Reading {
        final CardCatalog.Card type;
        final String text;
        final JSONObject fields;

        Reading(CardCatalog.Card type, String text, JSONObject fields) {
            this.type = type;
            this.text = text;
            this.fields = fields;
        }
    }

    Reading read(CardCatalog.Card type, byte[] jpeg, boolean includeFields, Progress progress) throws Exception {
        String base = config.cuRoot + "/contentunderstanding/analyzers/";
        boolean structured = includeFields && type.hasFields();
        if (structured && config.cuModel.startsWith("text-embedding-")) {
            throw new IOException("自訂欄位需要文字生成模型。請在連線設定填入已對應的模型，例如 gpt-5.2，或在本次欄位讀取方式選「只讀全文」。");
        }
        String analyzer = structured ? type.analyzerId(config.cuModel) : type.textAnalyzer();
        if (structured) {
            ensureAnalyzer(type, base + analyzer, progress);
        }
        progress.show("正在讀取" + type.label + "的文字…");
        JSONObject input = obj("name", "scan.jpg", "mimeType", "image/jpeg",
            "data", Base64.encodeToString(jpeg, Base64.NO_WRAP));
        Reply start = cu("POST", base + analyzer + ":analyze" + VERSION,
            obj("inputs", new JSONArray().put(input)));
        JSONObject result = waitFor(start, "文字讀取失敗", true).getJSONObject("result");
        JSONArray contents = result.getJSONArray("contents");
        if (contents.length() == 0) throw new IOException("Azure 未傳回影像內容，請稍後重試。");

        ArrayList<String> blocks = new ArrayList<>();
        for (int i = 0; i < contents.length(); i++) {
            String block = contentText(contents.getJSONObject(i));
            if (!block.isEmpty()) blocks.add(block);
        }
        JSONObject fields = structured ? contents.getJSONObject(0).optJSONObject("fields") : null;
        return new Reading(type, String.join("\n\n", blocks), structured && fields == null ? new JSONObject() : fields);
    }

    private static String contentText(JSONObject content) {
        String markdown = content.isNull("markdown") ? "" : content.optString("markdown").trim();
        JSONArray pages = content.optJSONArray("pages");
        ArrayList<String> pageText = new ArrayList<>();
        if (pages != null) {
            for (int i = 0; i < pages.length(); i++) {
                JSONObject page = pages.optJSONObject(i);
                if (page == null) continue;
                String text = joinContent(page.optJSONArray("lines"), "\n");
                if (text.isEmpty()) text = joinContent(page.optJSONArray("words"), " ");
                if (!text.isEmpty()) pageText.add(text);
                else if (!markdown.isEmpty()) return markdown;
            }
        }
        if (!pageText.isEmpty()) return String.join("\n\n", pageText);
        String paragraphs = joinContent(content.optJSONArray("paragraphs"), "\n\n");
        if (!paragraphs.isEmpty()) return paragraphs;
        return markdown;
    }

    private static String joinContent(JSONArray elements, String separator) {
        ArrayList<String> pieces = new ArrayList<>();
        if (elements != null) {
            for (int i = 0; i < elements.length(); i++) {
                JSONObject item = elements.optJSONObject(i);
                if (item == null || item.isNull("content")) continue;
                String value = item.optString("content").trim();
                if (!value.isEmpty()) pieces.add(value);
            }
        }
        return String.join(separator, pieces);
    }
}
