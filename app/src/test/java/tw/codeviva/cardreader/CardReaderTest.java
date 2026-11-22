package tw.codeviva.cardreader;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;
import static tw.codeviva.cardreader.CardCatalog.obj;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class CardReaderTest {
    CardCatalog catalog;
    tw.codeviva.cardreader.Config config;
    static final String ROOT="https://example.services.ai.azure.com";
    static final String OP=ROOT+"/contentunderstanding/analyzerResults/example?api-version=2025-11-01";
    @Before public void setup() throws Exception {
        catalog=new CardCatalog(RuntimeEnvironment.getApplication());
        config=new tw.codeviva.cardreader.Config();config.cuRoot=config.decisionRoot=ROOT;
    }
    @Test public void bothDecisionContractsIncludeEveryType() throws Exception {
        for(boolean open:new boolean[]{false,true}){
            config.openAI=open;AzureReader api=new AzureReader(catalog,config);
            JSONObject req=api.decisionBody("AQID");
            if(open){
                assertEquals(11,req.getJSONArray("questions").getJSONObject(0).getJSONArray("choices").length());
                assertEquals("data:image/png;base64,AQID",req.getJSONArray("input").getJSONObject(0)
                    .getJSONArray("content").getJSONObject(1).getString("image_url"));
            }else{
                assertEquals("AQID",req.getJSONObject("assets").getJSONObject("card.png").getString("data_base64"));
                assertEquals(11,req.getJSONObject("questions").getJSONObject(AzureReader.QUESTION).getJSONObject("criteria").length());
            }
            for(String typ:catalog.cards.keySet()){
                JSONObject a=obj("type","choice","name",AzureReader.QUESTION,"choice",typ);
                assertEquals(typ,api.readChoice(obj("answers",open?new JSONArray().put(a):obj(AzureReader.QUESTION,a))));
            }
        }
    }
    @Test public void createPollAnalyzeAndReadAllFiveSides() throws Exception {
        for(CardCatalog.Card card:catalog.cards.values()){
            if (!card.hasFields()) continue;
            String url=ROOT+"/contentunderstanding/analyzers/"+card.analyzerId(config.cuModel);
            int[] step={0};
            AzureReader api=new AzureReader(catalog,config,(method,path,header,key,body)->{
                assertEquals("Ocp-Apim-Subscription-Key",header);
                switch(step[0]++){
                    case 0: assertEquals("GET",method);assertEquals(url+AzureReader.VERSION,path);return reply(404,null,obj());
                    case 1: assertEquals("PUT",method);assertTrue(path.endsWith("allowReplace=false"));
                        assertEquals("prebuilt-document",body.getString("baseAnalyzerId"));
                        assertEquals("text-embedding-3-large",body.getJSONObject("models").getString("embedding"));
                        assertEquals(card.fields.length(),body.getJSONObject("fieldSchema").getJSONObject("fields").length());
                        return reply(201,OP,obj("status","NotStarted"));
                    case 2: return reply(200,null,obj("status","Succeeded"));
                    case 3: assertEquals("POST",method);assertEquals(url+":analyze"+AzureReader.VERSION,path);
                        assertEquals("AQID",body.getJSONArray("inputs").getJSONObject(0).getString("data"));
                        return reply(202,OP,obj());
                    case 4: return reply(429,null,obj());
                    case 5: assertEquals(OP,path);return reply(200,null,obj("status","Running"));
                    case 6: return reply(200,null,obj("status","Succeeded","result",obj("contents",new JSONArray().put(
                        obj("fields",obj("Example",obj("valueString","0007","confidence",0.93)))))));
                    default:throw new AssertionError();
                }
            },ms->{});
            assertEquals("0007",api.read(card,new byte[]{1,2,3},true,msg->{}).fields.getJSONObject("Example").getString("valueString"));
            assertEquals(7,step[0]);
        }
    }
    @Test public void existingAnalyzerReusableAndCloudFailureVisible() throws Exception {
        int[] i={0};
        AzureReader api=new AzureReader(catalog,config,(m,u,h,k,b)->{
            if(i[0]++==0){assertEquals("GET",m);return reply(200,null,obj("status","ready"));}
            if(i[0]==2){assertEquals("POST",m);return reply(202,OP,obj());}
            return reply(200,null,obj("status","Failed","error",obj("code","ModelDeploymentMissing")));
        },ms->{});
        try{api.read(catalog.cards.get("id_front"),new byte[]{1},true,msg->{});fail();}catch(IOException e){assertTrue(e.getMessage().contains("ModelDeploymentMissing"));}
        assertEquals(3,i[0]);
    }
    @Test public void pollingStaysOnConfiguredResource() throws Exception {
        AzureReader api=new AzureReader(catalog,config);
        assertEquals(OP,api.operationUrl(OP));
        assertEquals(OP.replace(".services.ai.",".cognitiveservices."),api.operationUrl(OP.replace(".services.ai.",".cognitiveservices.")));
        for(String bad:new String[]{"http://example.services.ai.azure.com/contentunderstanding/a","https://other.services.ai.azure.com/contentunderstanding/a","https://example.services.ai.azure.com/foreign","https://example.services.ai.azure.com.evil.invalid/contentunderstanding/a"}){
            try{api.operationUrl(bad);fail();}catch(IOException expected){}
        }
    }
    @Test public void homeSettingsAndPhotoChooserOpen() throws Exception{
        try(ActivityController<MainActivity> ctl=Robolectric.buildActivity(MainActivity.class).setup()){
            Activity activity=ctl.get();View root=activity.findViewById(android.R.id.content);
            assertNotNull(find(root,"0 筆"));assertNotNull(find(root,"TextReader"));
            find(root,"連線設定").performClick();assertNotNull(find(root,"Content Understanding API 金鑰"));
            ctl.get().onBackPressed();find(root,"從相簿選取").performClick();
            assertEquals(Intent.ACTION_OPEN_DOCUMENT,Shadows.shadowOf(activity).getNextStartedActivityForResult().intent.getAction());
        }
    }
    @Test public void resultsShowValuesLeadingZerosMissingFieldsAndConfidence() throws Exception {
        try(ActivityController<MainActivity> ctl=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity a=ctl.get();
            java.lang.reflect.Field field=MainActivity.class.getDeclaredField("results");field.setAccessible(true);
            @SuppressWarnings("unchecked") List<AzureReader.Reading> data=(List<AzureReader.Reading>)field.get(a);
            data.add(new AzureReader.Reading(catalog.cards.get("nhi_front"),"測試輸出",
                obj("NhiCardNumber",obj("valueString","000000012345","confidence",0.94))));
            java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod("detail",int.class);
            m.setAccessible(true);m.invoke(a,0);
            View root=a.findViewById(android.R.id.content);assertNotNull(find(root,"000000012345"));assertNotNull(find(root,"信心 94%"));assertNotNull(find(root,"未讀取"));
            assertNotNull(find(root,"測試輸出"));
            a.onBackPressed();assertNotNull(find(root,"1 筆"));
        }
    }
    @Test public void captureGivesCameraFullResolutionUri() throws Exception{
        try(ActivityController<MainActivity> ctl=Robolectric.buildActivity(MainActivity.class).setup()){
            Activity a=ctl.get();find(a.findViewById(android.R.id.content),"拍照").performClick();
            Intent intent=Shadows.shadowOf(a).getNextStartedActivityForResult().intent;
            assertEquals(android.provider.MediaStore.ACTION_IMAGE_CAPTURE,intent.getAction());
            Uri uri=intent.getParcelableExtra(android.provider.MediaStore.EXTRA_OUTPUT);assertTrue(CaptureProvider.file(a,uri).isFile());
            assertTrue((intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0);
        }
    }
    @Test public void generalReaderRoutesAllTypesWithoutProvisioning() throws Exception {
        for (CardCatalog.Card type : catalog.cards.values()) {
            List<String> methods = new ArrayList<>();
            AzureReader api = new AzureReader(catalog, config, (method, path, header, key, body) -> {
                methods.add(method);
                if (method.equals("POST")) {
                    String analyzer = type.code.equals("receipt") || type.code.equals("form")
                        ? "prebuilt-layout" : "prebuilt-read";
                    assertEquals(ROOT + "/contentunderstanding/analyzers/" + analyzer + ":analyze" + AzureReader.VERSION, path);
                    return reply(202, OP, obj());
                }
                JSONArray lines = new JSONArray().put(obj("content","交易明細")).put(obj("content","000003"));
                JSONObject block = obj("pages",new JSONArray().put(obj("lines",lines)));
                return reply(200, null, obj("status","Succeeded","result",obj("contents",new JSONArray().put(block))));
            }, ms -> {});
            AzureReader.Reading reading = api.read(type, new byte[]{1}, false, msg -> {});
            assertEquals(Arrays.asList("POST","GET"), methods);
            assertEquals("交易明細\n000003", reading.text);
            assertNull(reading.fields);
        }
    }

    @Test public void unknownImageStillReadsMarkdownAndHandlesNoText() throws Exception {
        for (String content : Arrays.asList("未歸類文字", "")) {
            AzureReader api = new AzureReader(catalog, config, (m,u,h,k,b) ->
                reply(200, null, obj("result",obj("contents",new JSONArray().put(obj("markdown",content))))), ms -> {});
            AzureReader.Reading result = api.read(catalog.cards.get("other"),new byte[]{1},true,msg -> {});
            assertEquals(content, result.text);
            assertNull(result.fields);
        }
    }

    public static class OfflineActivity extends MainActivity {
        AzureReader reader;
        @Override AzureReader createReader() { return reader; }
    }

    @Test public void scanButtonClassifiesBeforeOcrAndCanPauseForReview() throws Exception {
        for (boolean reviewFirst : new boolean[]{false, true}) {
            try (ActivityController<OfflineActivity> controller = Robolectric.buildActivity(OfflineActivity.class).setup()) {
                OfflineActivity activity = controller.get();
                List<String> calls = Collections.synchronizedList(new ArrayList<>());
                activity.reader = new AzureReader(catalog, config, (method,url,header,key,body) -> {
                    if (url.endsWith(tw.codeviva.cardreader.Config.MS)) {
                        calls.add("classify");
                        return reply(200,null,obj("answers",obj(AzureReader.QUESTION,obj("type","choice","choice","receipt"))));
                    }
                    if (!url.contains("prebuilt-layout:analyze")) throw new IOException("Wrong analyzer");
                    calls.add("read");
                    return reply(200,null,obj("result",obj("contents",new JSONArray().put(obj("markdown","合計 365")))));
                }, ms -> {});
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(4,4,android.graphics.Bitmap.Config.ARGB_8888);
                setField(activity, "config", config);
                setField(activity, "photo", new CardPhoto(bitmap, new byte[]{1,2,3}));
                java.lang.reflect.Method preview = MainActivity.class.getDeclaredMethod("preview");
                preview.setAccessible(true);
                preview.invoke(activity);
                View root = activity.findViewById(android.R.id.content);
                if (reviewFirst) find(root, "讀取前讓我確認分類").performClick();
                find(root, "開始辨識").performClick();
                if (reviewFirst) {
                    awaitText(root, "讀取文字");
                    assertEquals(Collections.singletonList("classify"), calls);
                    find(root, "讀取文字").performClick();
                }
                awaitText(root, "合計 365");
                assertEquals(Arrays.asList("classify","read"), calls);
                assertNotNull(find(root, "收據／發票"));
            }
        }
    }

    @Test public void editingCriteriaDoesNotReplaceLegacyAnalyzerButChangedFieldsGetNewSchema() throws Exception {
        CardCatalog.Card original = catalog.cards.get("nhi_front");
        String oldId = original.analyzerId(config.cuModel);
        String criteria = "右側印有全民健康保險字樣。";
        JSONArray fields = new JSONArray(original.fields.toString());
        catalog.save(original.code, original.label, criteria, fields, true);
        assertEquals(oldId, catalog.cards.get(original.code).analyzerId(config.cuModel));
        fields.getJSONObject(0).put("description", "擷取右側姓名欄的完整姓名");
        catalog.save(original.code, original.label, criteria, fields, true);
        CardCatalog second = new CardCatalog(RuntimeEnvironment.getApplication());
        CardCatalog.Card edited = second.cards.get(original.code);
        String changedId = edited.analyzerId(config.cuModel);
        assertNotEquals(oldId, changedId);
        assertTrue(changedId.matches("[a-zA-Z0-9._-]{1,64}"));
        assertEquals(edited.analyzerId(config.cuModel), catalog.cards.get(original.code).analyzerId(config.cuModel));
        assertTrue(edited.autoFields);
        assertTrue(edited.schema(config.cuModel).getJSONObject("fieldSchema").getJSONObject("fields")
            .getJSONObject("Name").getString("description").contains("擷取右側姓名欄的完整姓名"));
        for (boolean open : new boolean[]{false,true}) {
            config.openAI = open;
            JSONObject payload = new AzureReader(second, config).decisionBody("AQID");
            if (open) {
                JSONArray choices = payload.getJSONArray("questions").getJSONObject(0).getJSONArray("choices");
                boolean found = false;
                for (int n = 0; n < choices.length(); n++) {
                    JSONObject choice = choices.getJSONObject(n);
                    if (choice.getString("value").equals(original.code)) found = choice.getString("description").contains(criteria);
                }
                assertTrue(found);
            } else {
                assertTrue(payload.getJSONObject("questions").getJSONObject(AzureReader.QUESTION)
                    .getJSONObject("criteria").getString(original.code).contains(criteria));
            }
        }
        second.reset(original.code);
        assertEquals(oldId, second.cards.get(original.code).analyzerId(config.cuModel));
        assertFalse(second.cards.get(original.code).autoFields);
    }

    @Test public void rulesEditorCreatesTypeThenScanUsesItsDescriptionAndReturnsNamedField() throws Exception {
        String label = "職員證正面", criterion = "公司標誌在正上方，左側有人像。", description = "讀取姓名下方的六位員工編號。";
        try (ActivityController<MainActivity> editor = Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = editor.get().findViewById(android.R.id.content);
            find(root, "辨識規則").performClick();
            find(root, "新增自訂類型").performClick();
            inputByLabel(root, "類型名稱").setText(label);
            inputByLabel(root, "分類描述").setText(criterion);
            find(root, "新增欄位").performClick();
            inputByLabel(root, "欄位名稱").setText("員工編號");
            inputByLabel(root, "讀取描述").setText(description);
            find(root, "儲存辨識規則").performClick();
            assertNotNull(find(root, label));
        }
        catalog = new CardCatalog(RuntimeEnvironment.getApplication());
        CardCatalog.Card custom = null;
        for (CardCatalog.Card type : catalog.cards.values()) if (type.label.equals(label)) custom = type;
        assertNotNull(custom);
        final CardCatalog.Card saved = custom;
        String fieldKey = saved.fields.getJSONObject(0).getString("key");
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        try (ActivityController<OfflineActivity> ctl = Robolectric.buildActivity(OfflineActivity.class).setup()) {
            OfflineActivity a = ctl.get();
            a.reader = new AzureReader(catalog, config, (method,url,header,key,body) -> {
                if (url.endsWith(tw.codeviva.cardreader.Config.MS)) {
                    assertTrue(body.getJSONObject("questions").getJSONObject(AzureReader.QUESTION)
                        .getJSONObject("criteria").getString(saved.code).contains(criterion));
                    calls.add("classify");
                    return reply(200,null,obj("answers",obj(AzureReader.QUESTION,obj("type","choice","choice",saved.code))));
                }
                calls.add(method);
                assertTrue(url.contains(saved.analyzerId(config.cuModel)));
                if (method.equals("GET")) return reply(404,null,obj());
                if (method.equals("PUT")) {
                    assertTrue(body.getJSONObject("fieldSchema").getJSONObject("fields")
                        .getJSONObject(fieldKey).getString("description").contains(description));
                    return reply(201,null,obj("status","ready"));
                }
                return reply(200,null,obj("result",obj("contents",new JSONArray().put(
                    obj("markdown","員工資料", "fields",obj(fieldKey,obj("valueString","000321")))))));
            }, ms -> {});
            android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(4,4,android.graphics.Bitmap.Config.ARGB_8888);
            setField(a, "config", config);
            setField(a, "photo", new CardPhoto(image, new byte[]{1,2,3}));
            java.lang.reflect.Method preview = MainActivity.class.getDeclaredMethod("preview");
            preview.setAccessible(true);
            preview.invoke(a);
            View root = a.findViewById(android.R.id.content);
            find(root, "開始辨識").performClick();
            awaitText(root, "000321");
            assertNotNull(find(root, "員工編號"));
            assertEquals(Arrays.asList("classify", "GET", "PUT", "POST"), calls);
            assertEquals(0, a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
        }
        catalog.reset(saved.code);
        assertFalse(new CardCatalog(RuntimeEnvironment.getApplication()).cards.containsKey(saved.code));
    }

    @Test public void duplicateOrIncompleteFieldsDoNotReplaceSavedRule() throws Exception {
        CardCatalog.Card original = catalog.cards.get("id_front");
        for (String label : new String[]{"", "姓名"}) {
            JSONArray fields = new JSONArray(original.fields.toString());
            fields.put(obj("key","NewField","label",label,"description","說明"));
            try {
                catalog.save(original.code, original.label, "暫存分類描述", fields, true);
                fail("Expected invalid fields");
            } catch (IllegalArgumentException expected) {
                CardCatalog reload = new CardCatalog(RuntimeEnvironment.getApplication());
                assertEquals(original.criteria, reload.cards.get(original.code).criteria);
            }
        }
    }

    private static EditText inputByLabel(View view, String description) {
        if (view instanceof EditText && description.contentEquals(String.valueOf(view.getContentDescription()))) return (EditText)view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText match = inputByLabel(group.getChildAt(i), description);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static void setField(MainActivity activity, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = MainActivity.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(activity, value);
    }

    private static void awaitText(View root, String text) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        do {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            if (find(root, text) != null) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("Missing screen text " + text);
    }

    static View find(View v,String text){
        if(v instanceof TextView && text.contentEquals(((TextView)v).getText()))return v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){
            View r=find(((ViewGroup)v).getChildAt(i),text);if(r!=null)return r;
        }
        return null;
    }
    static AzureReader.Reply reply(int code,String url,JSONObject body){return new AzureReader.Reply(code,url,1,body);}
}
