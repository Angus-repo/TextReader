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
