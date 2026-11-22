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
                assertEquals("AQID",req.getJSONObject("assets").getJSONObject("card.jpg").getString("data_base64"));
                assertEquals(11,req.getJSONObject("questions").getJSONObject(AzureReader.QUESTION).getJSONObject("criteria").length());
            }
            for(String typ:catalog.cards.keySet()){
                JSONObject a=obj("type","choice","name",AzureReader.QUESTION,"choice",typ);
                assertEquals(typ,api.readChoice(obj("answers",open?new JSONArray().put(a):obj(AzureReader.QUESTION,a))));
            }
        }
    }
    @Test public void createPollAnalyzeAndReadAllPresetTypes() throws Exception {
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
            String id;
            try (ReadingHistory store = new ReadingHistory(a)) {
                id = store.add(new AzureReader.Reading(catalog.cards.get("nhi_front"),"測試輸出",
                    obj("NhiCardNumber", obj("valueString","000000012345","confidence",0.94))));
            }
            java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod("openReading",String.class);
            m.setAccessible(true);m.invoke(a,id);
            View root=a.findViewById(android.R.id.content);assertNotNull(find(root,"000000012345"));assertNotNull(find(root,"信心 94%"));assertNotNull(find(root,"未讀取"));
            assertNotNull(find(root,"已讀取 1 / 5"));
            assertEquals(View.GONE, ((View)root.findViewWithTag("transcript-json").getParent()).getVisibility());
            find(root,"展開").performClick();
            TextView body = root.findViewWithTag("transcript-json");
            JSONObject json = new JSONObject(body.getText().toString());
            assertEquals("000000012345", json.getJSONObject("欄位").getString("健保卡卡號"));
            assertEquals("測試輸出", json.getJSONArray("原文逐行").getString(0));
            find(root,"複製 JSON").performClick();
            android.content.ClipboardManager clipboard = a.getSystemService(android.content.ClipboardManager.class);
            assertEquals(body.getText().toString(), clipboard.getPrimaryClip().getItemAt(0).getText().toString());
            a.onBackPressed();assertNotNull(find(root,"1 筆"));
        }
    }
    @Test public void plainIdOcrAlignsPrintedFieldsAndKeepsExactOriginal() throws Exception {
        String raw = "中華民國國民身分證\n樣\n本\n4\n姓名\n林\n以\n安\n出生\n年月日\n民國 80 年 2 月 3 日"
            + "\n發證日期\n民國 110 年 5 月 6 日(北市)補發\n性別 女\n統一編號\nA000000001";
        try (ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup()) {
            ReaderScreen ui = new ReaderScreen(ctl.get());
            LinearLayout root = ui.col();
            new ResultLayout(ui, root).show(new AzureReader.Reading(catalog.cards.get("id_front"), raw, null));
            assertNotNull(find(root, "依原文整理"));
            assertNotNull(find(root, "林以安"));
            assertNotNull(find(root, "出生年月日"));
            assertNotNull(find(root, "民國 80 年 2 月 3 日"));
            assertNotNull(find(root, "民國 110 年 5 月 6 日(北市)補發"));
            assertNotNull(find(root, "女"));
            assertNotNull(find(root, "A000000001"));
            assertNull(find(root, "4"));
            TextView transcript = root.findViewWithTag("transcript-json");
            View container = (View) transcript.getParent();
            JSONArray lines = new JSONObject(transcript.getText().toString()).getJSONArray("原文逐行");
            assertEquals(Arrays.asList(raw.split("\\n")), jsonStrings(lines));
            assertEquals(View.GONE, container.getVisibility());
            find(root, "展開").performClick();
            assertEquals(View.VISIBLE, container.getVisibility());
            assertTrue(transcript.isTextSelectable());
            find(root, "收合").performClick();
            assertEquals(View.GONE, container.getVisibility());
        }
    }

    @Test public void textLayoutPreservesUnlabeledProseAndMultilineAddresses() throws Exception {
        try (ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup()) {
            ReaderScreen ui = new ReaderScreen(ctl.get());
            LinearLayout root = ui.col();
            String prose = "第一段內文。\n\n第二段提及文件編號的用途。";
            new ResultLayout(ui, root).show(new AzureReader.Reading(catalog.cards.get("document"), prose, null));
            assertNull(find(root, "展開"));
            TextView json = root.findViewWithTag("transcript-json");
            assertEquals(Arrays.asList(prose.split("\\n")), jsonStrings(new JSONObject(json.getText().toString()).getJSONArray("原文逐行")));
            List<ResultLayout.Row> address = OcrRows.from(catalog.cards.get("id_back"), "住址\n測試市一區\n測試路1號\n00123456");
            assertEquals(1, address.size());
            assertEquals("測試市一區測試路1號", address.get(0).value);
            List<ResultLayout.Row> inline = OcrRows.from(catalog.cards.get("id_back"), "父 林甲　母 林乙\n配偶\n役別");
            assertEquals(2, inline.size());
            assertEquals("林甲", inline.get(0).value);
            assertEquals("林乙", inline.get(1).value);
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
            AzureReader.Reading result = api.read(catalog.cards.get("other"),new byte[]{1},false,msg -> {});
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
                awaitText(root, "辨識原文（JSON）");
                TextView json = root.findViewWithTag("transcript-json");
                assertEquals("合計 365", new JSONObject(json.getText().toString()).getJSONArray("原文逐行").getString(0));
                assertEquals(Arrays.asList("classify","read"), calls);
                assertNotNull(find(root, "收據／發票"));
            }
        }
    }

    @Test public void failedClassificationRetainsPhotoAndAllowsManualBackSideOcr() throws Exception {
        config.cuModel = "text-embedding-3-large";
        config.decisionKey = "private-test-credential";
        try (ActivityController<OfflineActivity> controller = Robolectric.buildActivity(OfflineActivity.class).setup()) {
            OfflineActivity activity = controller.get();
            List<String> calls = Collections.synchronizedList(new ArrayList<>());
            activity.reader = new AzureReader(catalog, config, (method, url, header, key, body) -> {
                if (url.endsWith(tw.codeviva.cardreader.Config.MS)) {
                    calls.add("classify");
                    return new AzureReader.Reply(400, null, 0, obj("detail", new JSONArray().put(
                        obj("msg", "Unsupported image", "input", key))), "request-123");
                }
                calls.add("ocr");
                assertTrue(url.contains("/prebuilt-read:analyze"));
                assertEquals("AQID", body.getJSONArray("inputs").getJSONObject(0).getString("data"));
                return reply(200, null, obj("result", obj("contents", new JSONArray().put(obj("markdown", "測試背面文字")))));
            }, ms -> {});
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(256, 164, android.graphics.Bitmap.Config.ARGB_8888);
            setField(activity, "config", config);
            setField(activity, "photo", new CardPhoto(bitmap, new byte[]{1,2,3}));
            java.lang.reflect.Method preview = MainActivity.class.getDeclaredMethod("preview");
            preview.setAccessible(true);
            preview.invoke(activity);
            View root = activity.findViewById(android.R.id.content);
            find(root, "開始辨識").performClick();
            awaitText(root, "手動選擇類型");
            assertNotNull(find(root, "Decisions 影像分類失敗（HTTP 400）\nUnsupported image\n請求 ID：request-123\n可重新分類，或手動選擇這張影像的類型。"));
            assertNotNull(find(root, "重新分類"));
            find(root, "手動選擇類型").performClick();
            android.app.AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            ListView list = dialog.getListView();
            int back = new ArrayList<>(catalog.cards.keySet()).indexOf("id_back");
            list.performItemClick(null, back, list.getAdapter().getItemId(back));
            assertEquals(Collections.singletonList("classify"), calls);
            assertNotNull(find(root, "已手動選擇類型，可直接讀取這張影像。"));
            find(root, "讀取文字").performClick();
            awaitText(root, "辨識原文（JSON）");
            TextView json = root.findViewWithTag("transcript-json");
            assertEquals("測試背面文字", new JSONObject(json.getText().toString()).getJSONArray("原文逐行").getString(0));
            assertNotNull(find(root, "身分證反面"));
            assertEquals(Arrays.asList("classify", "ocr"), calls);
        }
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void microsoftDecisionSendsDecodableJpegWithinImageBounds() throws Exception {
        for (int[] size : new int[][]{{160, 100}, {960, 615}, {960, 960}, {16, 950}, {920, 14}}) {
            android.graphics.Bitmap original = android.graphics.Bitmap.createBitmap(size[0], size[1],
                android.graphics.Bitmap.Config.ARGB_8888);
            original.eraseColor(android.graphics.Color.BLUE);
            AzureReader reader = new AzureReader(catalog, config, (method, url, header, key, body) -> {
                JSONObject asset = body.getJSONObject("assets").getJSONObject("card.jpg");
                assertTrue(body.getString("state").contains("image:card.jpg"));
                assertEquals("jpeg", asset.getString("format"));
                byte[] bytes = android.util.Base64.decode(asset.getString("data_base64"), android.util.Base64.DEFAULT);
                android.graphics.Bitmap decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                assertNotNull(decoded);
                int w = decoded.getWidth(), h = decoded.getHeight();
                assertTrue(w >= 280 && h >= 280 && w <= 1568 && h <= 1568);
                assertTrue((long) w*h <= 645120);
                assertTrue(android.graphics.Color.blue(decoded.getPixel(w/2,h/2)) > 230);
                if (size[0] == 16) assertTrue(android.graphics.Color.red(decoded.getPixel(0,h/2)) > 230);
                assertEquals(0xff, bytes[0] & 255);
                assertEquals(0xd8, bytes[1] & 255);
                return reply(200, null, obj("answers",obj(AzureReader.QUESTION,obj("type","choice","choice","id_back"))));
            }, ms -> {});
            assertEquals("id_back", reader.classify(original));
            assertFalse(original.isRecycled());
            original.recycle();
        }
    }

    @Test public void embeddingModelGivesActionableErrorOnlyForFieldExtraction() throws Exception {
        config.cuModel = "text-embedding-3-large";
        AzureReader reader = new AzureReader(catalog, config, (method, url, header, key, body) -> {
            throw new AssertionError("An embedding model is not a completion model");
        }, ms -> {});
        try {
            reader.read(catalog.cards.get("id_back"), new byte[]{1}, true, msg -> {});
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("只讀全文"));
            assertTrue(expected.getMessage().contains("文字生成模型"));
        }
    }

    @Test public void editingCriteriaReusesAnalyzerButChangedFieldsGetNewSchema() throws Exception {
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

    @Test public void everyPresetShowsEditableDescriptionsAndUsesThemForExtraction() throws Exception {
        int fieldCount = 0;
        for (CardCatalog.Card type : catalog.cards.values()) {
            assertFalse(type.criteria.trim().isEmpty());
            assertTrue(type.label, type.hasFields());
            assertFalse(type.autoFields);
            assertFalse(type.analyzerId(config.cuModel).startsWith("twcard-v1-"));
            JSONObject fields = type.schema(config.cuModel).getJSONObject("fieldSchema").getJSONObject("fields");
            for (int i = 0; i < type.fields.length(); i++) {
                JSONObject field = type.fields.getJSONObject(i);
                String description = field.getString("description");
                assertFalse(description.trim().isEmpty());
                assertTrue(fields.getJSONObject(field.getString("key")).getString("description").contains(description));
                fieldCount++;
            }
        }
        assertEquals(63, fieldCount);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = controller.get().findViewById(android.R.id.content);
            find(root, "辨識規則").performClick();
            CardCatalog.Card receipt = catalog.cards.get("receipt");
            View preview = find(root, receipt.criteria);
            assertNotNull(preview);
            ((View)preview.getParent()).performClick();
            assertEquals(receipt.criteria, inputByLabel(root, "分類描述").getText().toString());
            String firstDescription = receipt.fields.getJSONObject(0).getString("description");
            assertEquals(firstDescription, inputByLabel(root, "讀取描述").getText().toString());
        }
    }

    @Test public void upgradeKeepsSavedRulesEvenWhenOldRuleHasNoFields() throws Exception {
        CardCatalog.Card preset = catalog.cards.get("receipt");
        JSONObject saved = obj("code", "receipt", "label", "我的收據", "criteria", "櫃檯列印的交易憑證",
            "fields", new JSONArray(), "autoFields", false);
        RuntimeEnvironment.getApplication().getSharedPreferences("document_rules_v1", 0).edit()
            .putString("receipt", saved.toString()).commit();
        CardCatalog loaded = new CardCatalog(RuntimeEnvironment.getApplication());
        assertEquals("櫃檯列印的交易憑證", loaded.cards.get("receipt").criteria);
        assertEquals(0, loaded.cards.get("receipt").fields.length());
        loaded.reset("receipt");
        assertEquals(preset.criteria, loaded.cards.get("receipt").criteria);
        assertEquals(preset.fields.toString(), loaded.cards.get("receipt").fields.toString());
    }

    @Test public void newTypeCanPrefillFromBuiltInWithoutOverwritingNameOrExistingRule() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = controller.get().findViewById(android.R.id.content);
            find(root, "辨識規則").performClick();
            find(root, "新增自訂類型").performClick();
            inputByLabel(root, "類型名稱").setText("供應商收據");
            find(root, "帶入內建範例").performClick();
            android.app.AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            android.widget.ListView list = dialog.getListView();
            int receipt = new ArrayList<>(catalog.cards.keySet()).indexOf("receipt");
            list.performItemClick(null, receipt, list.getAdapter().getItemId(receipt));
            assertEquals("供應商收據", inputByLabel(root, "類型名稱").getText().toString());
            CardCatalog.Card source = catalog.cards.get("receipt");
            assertEquals(source.criteria, inputByLabel(root, "分類描述").getText().toString());
            assertEquals("商家名稱", inputByLabel(root, "欄位名稱").getText().toString());
            inputByLabel(root, "分類描述").setText("列明供應商編號及應付總額");
            find(root, "儲存辨識規則").performClick();
            CardCatalog after = new CardCatalog(RuntimeEnvironment.getApplication());
            assertEquals(source.criteria, after.cards.get("receipt").criteria);
            assertEquals(12, after.cards.size());
        }
    }

    private static List<String> jsonStrings(JSONArray values) throws JSONException {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) result.add(values.getString(i));
        return result;
    }

    @Test public void historySurvivesReopenAndBackupRestoreWithoutOriginalRules() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        String raw = "00123\n\n測試\"引號\"\n";
        JSONArray defs = new JSONArray().put(obj("key", "Code", "label", "原編號", "description", "完整印字"));
        catalog.save("my_test", "舊類型", "測試分類", defs, true);
        String id;
        java.io.ByteArrayOutputStream backup = new java.io.ByteArrayOutputStream();
        try (ReadingHistory store = new ReadingHistory(context)) {
            id = store.add(new AzureReader.Reading(catalog.cards.get("my_test"), raw, obj("Code",obj("valueString","00123","confidence",0.9))));
            assertEquals(1, store.exportTo(backup));
        }
        catalog.reset("my_test");
        assertFalse(catalog.cards.containsKey("my_test"));
        try (ReadingHistory again = new ReadingHistory(context)) {
            assertEquals(id, again.list().get(0).id);
            AzureReader.Reading saved = again.get(id);
            assertEquals("舊類型", saved.type.label);
            assertEquals("原編號", saved.type.fields.getJSONObject(0).getString("label"));
            assertEquals(raw, saved.text);
            assertEquals("00123", saved.fields.getJSONObject("Code").getString("valueString"));
        }
        assertTrue(context.deleteDatabase("reading-history.db"));
        try (ReadingHistory cleanInstall = new ReadingHistory(context)) {
            assertEquals(0, cleanInstall.list().size());
            assertEquals(1, cleanInstall.importFrom(new java.io.ByteArrayInputStream(backup.toByteArray())));
            assertEquals(0, cleanInstall.importFrom(new java.io.ByteArrayInputStream(backup.toByteArray())));
            assertEquals(raw, cleanInstall.get(id).text);
            assertEquals(1, cleanInstall.list().size());
            cleanInstall.clear();
            assertTrue(cleanInstall.list().isEmpty());
        }
    }

    @Test public void homeBackupPickerRestoresHistoryAfterDataRemoval() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        try (ReadingHistory store = new ReadingHistory(context)) {
            store.add(new AzureReader.Reading(catalog.cards.get("id_back"), "背面測試", null));
        }
        java.io.File file = java.io.File.createTempFile("textreader-test", ".json");
        Intent picked = new Intent().setData(Uri.fromFile(file));
        try {
            try (ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup()) {
                MainActivity a = ctl.get();
                View root = a.findViewById(android.R.id.content);
                find(root, "匯出備份").performClick();
                org.robolectric.shadows.ShadowActivity.IntentForResult request = Shadows.shadowOf(a).getNextStartedActivityForResult();
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.getAction());
                a.onActivityResult(request.requestCode, Activity.RESULT_OK, picked);
                awaitText(root, "辨識紀錄");
                assertTrue(file.length() > 0);
                find(root, "清除所有紀錄").performClick();
                android.app.AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
                awaitText(root, "0 筆");
            }
            try (ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup()) {
                MainActivity a = ctl.get();
                View root = a.findViewById(android.R.id.content);
                assertNotNull(find(root, "0 筆"));
                find(root, "匯入備份").performClick();
                org.robolectric.shadows.ShadowActivity.IntentForResult request = Shadows.shadowOf(a).getNextStartedActivityForResult();
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.getAction());
                a.onActivityResult(request.requestCode, Activity.RESULT_OK, picked);
                awaitText(root, "1 筆");
                View title = find(root, "身分證反面");
                ((View) ((View) title.getParent()).getParent()).performClick();
                assertNotNull(find(root, "辨識原文（JSON）"));
                TextView body = root.findViewWithTag("transcript-json");
                assertEquals("背面測試", new JSONObject(body.getText().toString()).getJSONArray("原文逐行").getString(0));
            }
        } finally {
            file.delete();
        }
    }

    @Test public void damagedBackupRollsBackAllNewRecords() throws Exception {
        try (ReadingHistory store = new ReadingHistory(RuntimeEnvironment.getApplication())) {
            String keep = store.add(new AzureReader.Reading(catalog.cards.get("other"), "原有資料", null));
            String incoming = store.add(new AzureReader.Reading(catalog.cards.get("id_back"), "新資料", null));
            java.io.ByteArrayOutputStream backup = new java.io.ByteArrayOutputStream();
            store.exportTo(backup);
            JSONObject broken = new JSONObject(backup.toString("UTF-8"));
            JSONObject incomingRecord = null;
            JSONArray all = broken.getJSONArray("records");
            for (int i=0;i<all.length();i++)
                if (incoming.equals(all.getJSONObject(i).getString("id"))) incomingRecord = all.getJSONObject(i);
            assertNotNull(incomingRecord);
            broken.put("records", new JSONArray().put(incomingRecord.put("id", java.util.UUID.randomUUID().toString())).put(obj("bad",true)));
            try {
                store.importFrom(new java.io.ByteArrayInputStream(broken.toString().getBytes("UTF-8")));
                fail();
            } catch (JSONException expected) {
                assertEquals(2, store.list().size());
                assertEquals("原有資料", store.get(keep).text);
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
