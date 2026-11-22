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
    @Test public void sumsAzureDecisionAndDocumentMetersWithoutCountingCacheOrPagesAsExtraModelTokens() throws Exception {
        ScanUsage use = new ScanUsage();
        config.openAI = true;
        ScanUsage.decision(use.begin(ScanUsage.DECISIONS).put("apiElapsedMs", 800), obj("usage", obj("input_tokens",1000,"output_tokens",0,"total_tokens",1000,
                "input_tokens_details",obj("cached_tokens",200,"cache_write_tokens",100))), config);
        ScanUsage.document(use.begin(ScanUsage.CONTENT).put("apiElapsedMs", 2000), obj("documentPagesStandard",1,"contextualizationTokens",1000,
                "tokens",obj("gpt-5.2-input",1000,"gpt-5.2-output",100,"text-embedding-3-large",1000)), true,"custom",config);
        JSONObject saved = use.snapshot();
        assertEquals(4100, saved.getLong("tokens"));
        // Classification 0.0000845, page 0.005, context 0.001, generation 0.00315, embedding 0.00013.
        assertEquals("0.0093645", saved.getString("usd"));
        assertFalse(saved.getBoolean("partialCost"));
        assertFalse(saved.getBoolean("partialTokens"));
        assertTrue(ScanUsage.summary(saved).contains("2.80 秒"));
        assertTrue(visibleText(new UsagePanel(new ReaderScreen(RuntimeEnvironment.getApplication())).show(saved)).contains("gpt-5.2-output"));
        config.decisionRate = "0.05";
        config.openAI = false;
        JSONObject another = new JSONObject();
        ScanUsage.decision(another, obj("usage",obj("input_tokens",1000)), config);
        assertEquals(0, new java.math.BigDecimal("0.00005").compareTo(new java.math.BigDecimal(another.getString("usd"))));
    }

    @Test public void savedRuleRoutesAllThreeFullTextModesAndCustomSchemas() throws Exception {
        CardCatalog.Card original = catalog.cards.get("id_back");
        Set<String> analyzerIds = new HashSet<>();
        for (ExtractionMode mode : ExtractionMode.values()) {
            catalog.save(original.code, original.label, original.criteria, original.fields, true, mode);
            CardCatalog.Card saved = new CardCatalog(RuntimeEnvironment.getApplication()).cards.get(original.code);
            assertEquals(mode, saved.extraction);
            boolean minimal = mode == ExtractionMode.MINIMAL;
            JSONObject schema = saved.schema(config.cuModel).getJSONObject("config");
            assertEquals(!minimal, schema.getBoolean("enableOcr"));
            assertEquals(mode == ExtractionMode.STANDARD, schema.getBoolean("enableLayout"));
            assertEquals(!minimal, schema.optBoolean("enableBarcode"));
            assertTrue(analyzerIds.add(saved.analyzerId(config.cuModel)));
            final byte[] payload = minimal ? "測試文字".getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[]{1, 2};
            for (boolean structured : new boolean[]{false, true}) {
                AzureReader reader = new AzureReader(catalog, config, (method, url, header, key, request) -> {
                    if (method.equals("GET")) return reply(200, null, obj("status", "ready"));
                    assertTrue(url.contains("/" + (structured ? saved.analyzerId(config.cuModel) : mode.analyzer) + ":analyze"));
                    JSONObject input = request.getJSONArray("inputs").getJSONObject(0);
                    assertEquals(minimal ? "text/plain" : "image/jpeg", input.getString("mimeType"));
                    assertArrayEquals(payload, android.util.Base64.decode(input.getString("data"), android.util.Base64.DEFAULT));
                    return reply(200, null, obj("usage", obj(mode.meter(), 1),
                            "result", obj("contents", new JSONArray().put(obj("markdown", "文字")))));
                }, ms -> {});
                reader.read(saved, payload, structured, msg -> {});
                JSONObject op = reader.usage.snapshot().getJSONArray("operations").getJSONObject(0);
                assertEquals(mode.label, op.getString("requestedMode"));
                assertEquals(mode.referenceRate.movePointLeft(3).stripTrailingZeros().toPlainString(), op.getString("pageUsd"));
            }
        }
        catalog.reset(original.code);
        assertEquals(ExtractionMode.BASIC, catalog.cards.get(original.code).extraction);
        assertFalse(catalog.cards.get(original.code).explicitExtraction);
    }

    @Test public void reportedMetersOverrideSelectionAndRatesStayWithTheReading() throws Exception {
        ScanUsage usage = new ScanUsage();
        JSONObject op = usage.begin(ScanUsage.CONTENT).put("requestedMode", "Standard");
        config.pageRates.put("Basic", "0.80");
        ScanUsage.document(op, obj("documentPagesMinimal", 1, "documentPagesBasic", 2,
                "documentPagesStandard", 0, "documentPagesStandardInline", 1), false, "prebuilt-layout", config);
        assertEquals("0.00911", op.getString("usd"));
        assertEquals(4, op.getInt("pages"));
        assertFalse(op.optBoolean("inferredPages"));
        config.pageRates.put("Basic", "3");
        JSONObject snapshot = usage.snapshot();
        String display = visibleText(new UsagePanel(new ReaderScreen(RuntimeEnvironment.getApplication())).show(snapshot));
        assertTrue(display.contains("規則選用 Standard"));
        assertTrue(display.contains("Azure 回報 Basic 2 頁 · US$0.0016"));
        assertTrue(display.contains("Azure 回報 Standard 同步 1 頁 · US$0.0075"));
        assertTrue(display.contains("Azure 回報 Minimal 1 頁 · US$0.00001"));
        ScanUsage missing = new ScanUsage();
        JSONObject digital = missing.begin(ScanUsage.CONTENT).put("requestedMode", "Minimal").put("textPageEstimate", 2);
        ScanUsage.document(digital, null, false, "prebuilt-digitalParse", config);
        assertEquals("0.00002", digital.getString("usd"));
        assertTrue(ScanUsage.summary(missing.snapshot()).contains("含推估頁數"));
        JSONObject zero = new JSONObject();
        ScanUsage.document(zero, obj("documentPagesMinimal", 0), false, "prebuilt-read", config);
        assertEquals("0", zero.getString("usd"));
        assertFalse(zero.optBoolean("inferredPages"));
    }

    @Test public void changeOnlyExtractionModeThroughRuleEditor() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = controller.get().findViewById(android.R.id.content);
            find(root, "辨識規則").performClick();
            String label = catalog.cards.get("id_front").label;
            ((View) find(root, label).getParent().getParent()).performClick();
            View title = find(root, "內容擷取強度");
            Spinner selector = root.findViewById(title.getLabelFor());
            assertEquals(3, selector.getCount());
            assertEquals(ExtractionMode.BASIC, selector.getSelectedItem());
            selector.setSelection(ExtractionMode.MINIMAL.ordinal());
            find(root, "儲存辨識規則").performClick();
            assertEquals(ExtractionMode.MINIMAL, new CardCatalog(RuntimeEnvironment.getApplication()).cards.get("id_front").extraction);
            ((View) find(root, label).getParent().getParent()).performClick();
            selector = root.findViewById(find(root, "內容擷取強度").getLabelFor());
            assertEquals(ExtractionMode.MINIMAL, selector.getSelectedItem());
        }
    }

    @Test public void missingMetersAndPricesKeepKnownSubtotalAndCompletedRetries() throws Exception {
        ScanUsage use = new ScanUsage();
        use.begin("前次未回報的分類");
        ScanUsage.decision(use.begin("重試分類"),obj("usage",obj("input_tokens",1000,"output_tokens",0)),config);
        ScanUsage.document(use.begin("讀取"),obj("documentPagesStandard",1,"contextualizationTokens",1000,
                "tokens",obj("new-model-output",17)),true,"custom",config);
        JSONObject u = use.snapshot();
        assertEquals(2017, u.getLong("tokens"));
        assertEquals("0.006042",u.getString("usd"));
        assertTrue(u.getBoolean("partialTokens"));
        assertTrue(u.getBoolean("partialCost"));
        assertTrue(ScanUsage.summary(u).contains("已知小計"));
        ScanUsage plain = new ScanUsage();
        ScanUsage.document(plain.begin("讀取"),null,false,"prebuilt-read",config);
        JSONObject result = plain.snapshot();
        assertEquals("0.001",result.getString("usd"));
        assertEquals(0,result.getLong("tokens"));
        assertTrue(visibleText(new UsagePanel(new ReaderScreen(RuntimeEnvironment.getApplication())).show(result)).contains("依上傳影像估計"));
        config.cuModel="new-model"; config.completionOutput="2";
        JSONObject op = new JSONObject();
        ScanUsage.document(op,obj("documentPagesBasic",1,"contextualizationTokens",1000,"tokens",obj("new-model-output",500)),true,"custom",config);
        assertTrue(op.getBoolean("priced"));
        assertEquals(0, new java.math.BigDecimal("0.003").compareTo(new java.math.BigDecimal(op.getString("usd"))));
    }

    @Test public void versionOneHistoryUpgradesAndUsageSurvivesBackupAndReopen() throws Exception {
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        String id = UUID.randomUUID().toString();
        JSONObject old = obj("id",id,"createdAt",1,"type",obj("code","other","label","舊紀錄","criteria","其他","fields",new JSONArray()),"text","舊原文");
        try (android.database.sqlite.SQLiteDatabase db = ctx.openOrCreateDatabase("reading-history.db",0,null)) {
            db.execSQL("CREATE TABLE readings(id TEXT PRIMARY KEY NOT NULL, created INTEGER NOT NULL, label TEXT NOT NULL, record TEXT NOT NULL)");
            db.execSQL("INSERT INTO readings VALUES(?,?,?,?)",new Object[]{id,1,"舊紀錄",old.toString()});
            db.setVersion(1);
        }
        try (ReadingHistory store = new ReadingHistory(ctx)) {
            assertEquals(ScanUsage.LEGACY,store.list().get(0).usageSummary);
            assertEquals("舊原文",store.get(id).text);
            ScanUsage use = new ScanUsage();
            ScanUsage.document(use.begin(ScanUsage.CONTENT).put("apiElapsedMs",3320),obj("documentPagesBasic",1),false,"prebuilt-read",config);
            AzureReader.Reading reading = new AzureReader.Reading(catalog.cards.get("other"),"新的原文",null);
            reading.usage = use.snapshot();
            String added = store.add(reading);
            String summary = store.list().get(0).usageSummary;
            assertTrue(summary.contains("3.32 秒")); assertTrue(summary.contains("US$0.001"));
            java.io.ByteArrayOutputStream file = new java.io.ByteArrayOutputStream();
            store.exportTo(file); store.clear(); store.importFrom(new java.io.ByteArrayInputStream(file.toByteArray()));
            assertEquals(summary, store.list().get(0).usageSummary);
            assertEquals(reading.usage.toString(),store.get(added).usage.toString());
            assertNull(store.get(id).usage);
        }
    }

    @Test public void onlyPositionedLinearAzureBarcodesOnTheUprightLowerLeftAreUsed() throws Exception {
        JSONObject bar = obj("kind","Code39","value","C012300007","source","D(1,10,65,56,65,56,77,10,77)");
        JSONObject distractor = obj("kind","Code39","value","0000000105","source","D(1,70,67,99,67,99,78,70,78)");
        JSONObject qr = new JSONObject(bar.toString()).put("kind","QRCode").put("value","different");
        JSONArray list = new JSONArray().put(qr).put(distractor).put(bar);
        JSONObject page = obj("width",100,"height",100,"pageNumber",1,"barcodes",list);
        JSONObject result = obj("contents",new JSONArray().put(obj("pages",new JSONArray().put(page))));
        AzureReader api = new AzureReader(catalog,config,(m,u,h,k,b) -> m.equals("POST") ?
                reply(202,OP,obj()) : reply(200,null,obj("status","Succeeded","result",result)), ms -> {});
        AzureReader.Reading read = api.read(catalog.cards.get("id_back"),new byte[]{1},false,msg -> {});
        IdCardDetails.readImage(RuntimeEnvironment.getApplication(), read, new byte[]{0});
        assertEquals("C012300007",read.local.getString("Barcode"));
        assertTrue(catalog.cards.get("id_back").schema(config.cuModel).getJSONObject("config").getBoolean("enableBarcode"));
        assertEquals("",PageBarcode.lowerLeft(page.put("angle",180)));
        page.put("angle", 90);
        bar.put("source","D(1,12,20,35,20,35,42,12,42)");
        assertEquals("C012300007",PageBarcode.lowerLeft(page));
        list.put(new JSONObject(bar.toString()).put("value","B123000000"));
        assertEquals("",PageBarcode.lowerLeft(page));
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void lowContrastBarsUnderUnevenLightPreserveTheExactContent() throws Exception {
        String payload = "F012340065";
        int w = 640, h = 70;
        com.google.zxing.common.BitMatrix bars = new com.google.zxing.oned.Code39Writer().encode(
                payload, com.google.zxing.BarcodeFormat.CODE_39, w, h);
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int gray = 128 + x / 13 - (bars.get(x,y) ? 20 : 0);
            pixels[y*w+x] = android.graphics.Color.rgb(gray,gray,gray);
        }
        android.graphics.Bitmap strip = android.graphics.Bitmap.createBitmap(pixels,w,h,android.graphics.Bitmap.Config.ARGB_8888);
        try { assertEquals(payload,IdCardDetails.decodeBars(strip)); }
        finally { strip.recycle(); }
    }

    @Test public void closeupUpdatesOnlyTheSelectedHistoryEntryAndKeepsUsageAndRawPayload() throws Exception {
        try (ReadingHistory store = new ReadingHistory(RuntimeEnvironment.getApplication())) {
            AzureReader.Reading back = new AzureReader.Reading(catalog.cards.get("id_back"),"役別\n0000000105",null);
            back.usage = new ScanUsage().snapshot();
            String old = store.add(back), target = store.add(back);
            try (ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup()) {
                MainActivity activity = ctl.get();
                View root = activity.findViewById(android.R.id.content);
                View label = find(root, "身分證反面");
                ((View) label.getParent().getParent()).performClick();
                find(root,"補掃左下角條碼").performClick();
                org.robolectric.shadows.ShadowActivity.IntentForResult req =
                        Shadows.shadowOf(activity).getNextStartedActivityForResult();
                assertTrue(req.intent.getBooleanExtra(LiveScanActivity.BARCODE_ONLY,false));
                activity.onActivityResult(req.requestCode,Activity.RESULT_CANCELED,null);
                assertNull(store.get(target).local.opt("Barcode"));
                find(root,"補掃左下角條碼").performClick();
                activity.onActivityResult(req.requestCode,Activity.RESULT_OK,
                        new Intent().putExtra(LiveScanActivity.BARCODE_VALUE,"R012340000"));
                assertNull(find(root,"補掃左下角條碼"));
                JSONObject fields = new JSONObject(((TextView)root.findViewWithTag("transcript-json"))
                        .getText().toString()).getJSONObject("欄位");
                assertFalse(fields.has("身分證字號"));
                assertEquals("R012340000",fields.getString(IdCardDetails.BARCODE_LABEL));
                assertEquals("0000000105",fields.getString("證件流水號"));
                assertEquals(back.usage.toString(),store.get(target).usage.toString());
                store.completeBarcode(target,"S112340000");
                assertEquals("R012340000",store.get(target).local.getString("Barcode"));
                assertFalse(store.get(old).local.has("Barcode"));
                assertEquals(2,store.list().size());
            }
        }
    }

    private static void advanceMs(long ms) {
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(ms));
    }

    @Test public void timeStartsAtInferenceDispatchAndEndsAtFinalPollForEachAttempt() throws Exception {
        int[] decisionCalls = {0}, polls = {0};
        AzureReader api = new AzureReader(catalog,config,(method,url,header,key,body) -> {
            if (url.endsWith(tw.codeviva.cardreader.Config.MS)) {
                if (decisionCalls[0]++ == 0) { advanceMs(250); throw new IOException("timeout"); }
                advanceMs(750);
                return reply(200,null,obj("answers",obj(AzureReader.QUESTION,obj("type","choice","choice","id_front"))));
            }
            if (method.equals("GET") && !url.equals(OP)) {
                advanceMs(15000); // Checking the custom analyzer is outside the inference window.
                return reply(200,null,obj("status","ready"));
            }
            if (method.equals("POST")) { advanceMs(300); return reply(202,OP,obj()); }
            advanceMs(100);
            if (polls[0]++ == 0) return reply(429,null,obj());
            return reply(200,null,obj("status","Succeeded","result",obj("contents",new JSONArray().put(obj("markdown","文字")))));
        }, CardReaderTest::advanceMs);
        android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(10,10,android.graphics.Bitmap.Config.ARGB_8888);
        try {
            try { api.classify(image); fail(); } catch (IOException expected) { assertEquals("timeout",expected.getMessage()); }
            advanceMs(5000);
            assertEquals("id_front",api.classify(image));
            advanceMs(60000);
            api.read(catalog.cards.get("id_front"),new byte[]{1},true,msg -> advanceMs(5000));
            advanceMs(10000); // Local work after the response.
            JSONObject usage = api.usage.snapshot();
            JSONArray ops = usage.getJSONArray("operations");
            assertEquals(250,ops.getJSONObject(0).getLong("apiElapsedMs"));
            assertEquals(750,ops.getJSONObject(1).getLong("apiElapsedMs"));
            // Analyze POST (300), two one-second waits and two GETs (100 each).
            assertEquals(2500,ops.getJSONObject(2).getLong("apiElapsedMs"));
            assertEquals(3500,usage.getLong("apiElapsedMs"));
            assertEquals(ScanUsage.CONTENT,ops.getJSONObject(2).getString("stage"));
            assertEquals(config.cuModel,ops.getJSONObject(2).getString("model"));
        } finally { image.recycle(); }
    }

    @Test public void usageArrowsIndependentlyExpandAndCollapseBothServices() throws Exception {
        JSONObject decision = obj("stage",ScanUsage.DECISIONS,"model","my-decision","input",1071,"output",1,
                "tokens",1072,"apiElapsedMs",1000,"usd","0.000044982");
        JSONObject read = obj("stage",ScanUsage.CONTENT,"analyzer","prebuilt-read","tokens",0,
                "pages",1,"apiElapsedMs",4420,"usd","0.001");
        JSONObject usage = obj("tokens",1072,"apiElapsedMs",5420,"usd","0.001044982","partialCost",true,
                "operations",new JSONArray().put(decision).put(read),"rateBasis","參考單價");
        View card = new UsagePanel(new ReaderScreen(RuntimeEnvironment.getApplication())).show(usage);
        View tokens = card.findViewWithTag("usage-tokens-details"), time = card.findViewWithTag("usage-time-details");
        View tokenButton = card.findViewWithTag("usage-tokens-toggle"), timeButton = card.findViewWithTag("usage-time-toggle");
        assertEquals(View.GONE,tokens.getVisibility()); assertEquals(View.GONE,time.getVisibility());
        assertNotNull(find(card,"5.42 秒")); assertNotNull(find(card,"已知費用小計"));
        tokenButton.performClick();
        assertEquals(View.VISIBLE,tokens.getVisibility()); assertEquals(View.GONE,time.getVisibility());
        for (String text : new String[]{ScanUsage.DECISIONS,ScanUsage.CONTENT,"my-decision","prebuilt-read",
                "輸入 1,071  ·  輸出 1","按頁計量","1 頁","預估費用 US$0.001"}) assertNotNull(text,find(tokens,text));
        timeButton.performClick();
        assertEquals(View.VISIBLE,time.getVisibility());
        assertNotNull(find(time,"1.00 秒")); assertNotNull(find(time,"4.42 秒"));
        assertNotNull(find(time,ScanUsage.DECISIONS)); assertNotNull(find(time,ScanUsage.CONTENT));
        tokenButton.performClick();
        assertEquals(View.GONE,tokens.getVisibility()); assertEquals(View.VISIBLE,time.getVisibility());
        timeButton.performClick(); assertEquals(View.GONE,time.getVisibility());
    }

    @Test public void historicalOverallTimeDoesNotBecomeApiTimeWhenOpenedOrImported() throws Exception {
        JSONObject old = obj("elapsedMs",5090,"tokens",1072,"operations",new JSONArray()
                .put(obj("stage",ScanUsage.DECISIONS,"tokens",1072,"input",1071,"output",1)));
        AzureReader.Reading reading = new AzureReader.Reading(catalog.cards.get("other"),"舊資料",null);
        reading.usage = old;
        try (ReadingHistory db = new ReadingHistory(RuntimeEnvironment.getApplication())) {
            String id = db.add(reading);
            db.getWritableDatabase().execSQL("UPDATE readings SET usage_summary=? WHERE id=?",
                    new Object[]{"1,072 tokens · 5.09 秒 · 估計 US$0.001",id});
            assertTrue(db.list().get(0).usageSummary.contains("舊版處理 5.09 秒"));
            java.io.ByteArrayOutputStream backup = new java.io.ByteArrayOutputStream();
            db.exportTo(backup); db.clear(); db.importFrom(new java.io.ByteArrayInputStream(backup.toByteArray()));
            JSONObject restored = db.get(id).usage;
            assertFalse(restored.has("apiElapsedMs"));
            View card = new UsagePanel(new ReaderScreen(RuntimeEnvironment.getApplication())).show(restored);
            assertNotNull(find(card,"舊版處理時間")); assertNotNull(find(card,"5.09 秒"));
            card.findViewWithTag("usage-time-toggle").performClick();
            assertNotNull(find(card.findViewWithTag("usage-time-details"),"這筆紀錄只保存整體處理時間，各 API 耗時未記錄。"));
            assertNull(find(card.findViewWithTag("usage-time-details"),"5.09 秒"));
        }
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
                    obj("NhiCardNumber", obj("valueString","0000樣　本00012345","confidence",0.94))));
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
    @Test public void savedUnlabeledNhiShowsFourFieldsAboveMatchingJson() throws Exception {
        String[] lines = {"全民健康保險", "NATIONAL  HEALTH  INSURANCE", "林以安", "A200000001",
            "089/01/02", "0000 1234 0007", "樣 本"};
        try (ReadingHistory store = new ReadingHistory(RuntimeEnvironment.getApplication())) {
            store.add(new AzureReader.Reading(catalog.cards.get("nhi_front"), String.join("\n", lines), null));
        }
        try (ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = ctl.get();
            View root = activity.findViewById(android.R.id.content);
            View listLabel = find(root, "健保卡正面");
            ((View) ((View) listLabel.getParent()).getParent()).performClick();
            LinearLayout fieldPanel = (LinearLayout) find(root, "欄位結果").getParent().getParent();
            LinearLayout jsonPanel = (LinearLayout) find(root, "辨識原文（JSON）").getParent().getParent();
            LinearLayout page = (LinearLayout) fieldPanel.getParent();
            assertSame(page, jsonPanel.getParent());
            assertTrue(page.indexOfChild(fieldPanel) < page.indexOfChild(jsonPanel));
            assertNotNull(find(fieldPanel, "已讀取 4 / 5"));
            String[] labels = {"姓名", "身分證／居留證號", "出生日期", "健保卡卡號"};
            String[] values = {"林以安", "A200000001", "089/01/02", "000012340007"};
            for (int i = 0; i < labels.length; i++) {
                assertNotNull(find(fieldPanel, labels[i]));
                assertNotNull(find(fieldPanel, values[i]));
            }
            TextView body = jsonPanel.findViewWithTag("transcript-json");
            assertEquals(View.GONE, ((View)body.getParent()).getVisibility());
            find(jsonPanel, "展開").performClick();
            assertEquals(View.VISIBLE, ((View)body.getParent()).getVisibility());
            JSONObject json = new JSONObject(body.getText().toString());
            JSONObject fields = json.getJSONObject("欄位");
            assertEquals(4, fields.length());
            for (int i = 0; i < labels.length; i++) assertEquals(values[i], fields.getString(labels[i]));
            assertFalse(fields.has("性別"));
            assertEquals(Arrays.asList(lines).subList(0, lines.length - 1), jsonStrings(json.getJSONArray("原文逐行")));
        }
    }

    @Test public void nhiKeepsExtractedValuesAndSkipsAmbiguousOrMissingData() throws Exception {
        try (ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup()) {
            ReaderScreen ui = new ReaderScreen(ctl.get());
            LinearLayout root = ui.col();
            String raw = "NATIONAL HEALTH INSURANCE\nA200000001\n89/01/01\n90/01/01\n0000 0000 1010\n樣 本";
            new ResultLayout(ui, root).show(new AzureReader.Reading(catalog.cards.get("nhi_front"), raw,
                obj("NhiCardNumber", obj("valueString", "000000001111", "confidence", 0.95))));
            TextView body = root.findViewWithTag("transcript-json");
            JSONObject fields = new JSONObject(body.getText().toString()).getJSONObject("欄位");
            assertEquals("000000001111", fields.getString("健保卡卡號"));
            assertNotNull(find(root, "信心 95%"));
            assertEquals("A200000001", fields.getString("身分證／居留證號"));
            assertFalse(fields.has("姓名"));
            assertFalse(fields.has("出生日期"));
            assertFalse(fields.has("性別"));
        }
    }

    @Test public void plainIdOcrAlignsPrintedFieldsAndOmitsOnlyTheWatermark() throws Exception {
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
            assertEquals(Arrays.asList(raw.replace("樣\n本\n", "").split("\\n")), jsonStrings(lines));
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
            assertNotNull(find(root, "欄位結果"));
            assertNotNull(find(root, "展開"));
            assertEquals(View.GONE, ((View) root.findViewWithTag("transcript-json").getParent()).getVisibility());
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

    @Test public void instantScanOpensInternalScannerWithPrivateOutput() throws Exception{
        try(ActivityController<MainActivity> ctl=Robolectric.buildActivity(MainActivity.class).setup()){
            Activity a=ctl.get();find(a.findViewById(android.R.id.content),"即時辨識").performClick();
            Intent intent=Shadows.shadowOf(a).getNextStartedActivityForResult().intent;
            assertEquals(LiveScanActivity.class.getName(), intent.getComponent().getClassName());
            Uri uri=intent.getData();assertTrue(CaptureProvider.file(a,uri).isFile());
            assertEquals(0, intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            ((MainActivity)a).onActivityResult(31, Activity.RESULT_CANCELED, null);
            assertFalse(CaptureProvider.file(a, uri).exists());
        }
    }
    @Test public void watermarkFormattingPreservesOtherWordsAndLineBreaks() {
        assertEquals("林以安", ResultText.forDisplay("林樣　本以安"));
        assertEquals("編號：0007", ResultText.forDisplay("樣本編號：0007样本"));
        assertEquals("父\n林甲\n\n母 李乙", ResultText.forDisplay("父\r\n  樣\r\n本　\r\n林甲\r\n\r\n母 李乙"));
        assertEquals("前段\n後段", ResultText.forDisplay("前段樣\n本後段"));
        assertEquals("", ResultText.forDisplay("　樣\u200B本　"));
        assertEquals("本人、同樣、正本與樣張", ResultText.forDisplay("本人、同樣、正本與樣張"));
    }

    @Test public void frontIdUsesCompletePrintedDigitsWithOrWithoutLabelAndWithPartialCloudResults() throws Exception {
        assertEquals("B212300007", IdCardDetails.idNumber("統一編號：ｂ２１２　３００\n００７"));
        assertEquals("", IdCardDetails.idNumber("B21230O007"));
        assertEquals("", IdCardDetails.idNumber("B2123000078"));
        assertEquals("", IdCardDetails.idNumber("B212300007\nC212300007"));
        try (ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup()) {
            AzureReader.Reading front = new AzureReader.Reading(catalog.cards.get("id_front"),
                    "發證日期\nB 2123\n00007", obj("Sex", obj("valueString", "女")));
            LinearLayout page = new ReaderScreen(ctl.get()).col();
            new ResultLayout(new ReaderScreen(ctl.get()), page).show(front);
            assertNotNull(find(page, "身分證字號"));
            assertNotNull(find(page, "B212300007"));
            JSONObject fields = new JSONObject(((TextView)page.findViewWithTag("transcript-json")).getText().toString()).getJSONObject("欄位");
            assertEquals("B212300007", fields.getString("身分證字號"));
            assertEquals("女", fields.getString("性別"));

            front = new AzureReader.Reading(catalog.cards.get("id_front"), "漏讀字號", null);
            front.local.put("IdNumber", "B212300007");
            try (ReadingHistory db = new ReadingHistory(ctl.get())) {
                page = new ReaderScreen(ctl.get()).col();
                new ResultLayout(new ReaderScreen(ctl.get()), page).show(db.get(db.add(front)));
                assertNotNull(find(page, "B212300007"));
            }
        }
    }

    private static JSONObject licenseJson(CardCatalog.Card type, AzureReader.Reading reading) throws Exception {
        ReaderScreen ui = new ReaderScreen(RuntimeEnvironment.getApplication());
        LinearLayout page = ui.col();
        new ResultLayout(ui,page).show(reading);
        JSONObject fields = new JSONObject(((TextView)page.findViewWithTag("transcript-json")).getText().toString()).getJSONObject("欄位");
        assertEquals(type.fields.length(),fields.length());
        List<String> order = new ArrayList<>();
        fields.keys().forEachRemaining(order::add);
        for (int i=0; i<type.fields.length(); i++) {
            String name = type.fields.getJSONObject(i).getString("label");
            assertNotNull(name,find(page,name));
            assertEquals(name,order.get(i));
            assertTrue(fields.get(name) instanceof String);
        }
        return fields;
    }

    @Test public void licensesKeepAllConfiguredFieldsBlankAcrossTextStructuredAndHistoricalResults() throws Exception {
        for (String code : new String[]{"dl_front","dl_back"}) {
            CardCatalog.Card type = catalog.cards.get(code);
            assertEquals(code.equals("dl_front") ? 12 : 16,type.fields.length());
            for (JSONObject extraction : new JSONObject[]{null,new JSONObject()}) {
                AzureReader.Reading read = new AzureReader.Reading(type,"",extraction);
                JSONObject fields = licenseJson(type,read);
                for (Iterator<String> it=fields.keys(); it.hasNext();) assertEquals("",fields.getString(it.next()));
            }
        }
        CardCatalog.Card type = catalog.cards.get("dl_front");
        AzureReader.Reading read = new AzureReader.Reading(type,"駕照號碼 Q123400067\n性別 女\n發照日期 099.01.03\n審驗日期",
                obj("Name",obj("valueString","林甲")));
        try (ReadingHistory db = new ReadingHistory(RuntimeEnvironment.getApplication())) {
            JSONObject fields = licenseJson(type,db.get(db.add(read)));
            assertEquals("林甲",fields.getString("姓名"));
            assertEquals("Q123400067",fields.getString("駕照號碼"));
            assertEquals("099.01.03",fields.getString("發照日期"));
            assertEquals("",fields.getString("審驗日期"));
        }
        assertEquals("",LicenseFields.clean("Conditions","C 加註條件： 【樣 張】"));
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void rotatedLicenseBackUsesWordGeometryAndKeepsEachBlankRowInPlace() throws Exception {
        int w=900,h=630,l=60,t=50,gw=750,gh=525;
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);
        b.eraseColor(android.graphics.Color.WHITE);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(); p.setColor(android.graphics.Color.BLACK); p.setStrokeWidth(3);
        for (double y : new double[]{0,.153,.249,.447,.543,.638,.722,.815,.907,1})
            c.drawLine(l,t+(float)y*gh,l+gw,t+(float)y*gh,p);
        c.drawLine(l,t,l,t+gh,p); c.drawLine(l+gw,t,l+gw,t+gh,p);
        Map<String,String> printed = new LinkedHashMap<>();
        printed.put("PrintNumber","000321"); printed.put("ConditionA","A無限制"); printed.put("ConditionB","B自動排檔");
        printed.put("Conditions","C加註條件：樣張"); printed.put("AddressChange2","測試路二號");
        printed.put("AddressStamp2","甲所"); printed.put("ReviewRecord2","112.07.10"); printed.put("ReviewStamp2","乙所");
        JSONArray words = new JSONArray();
        for (LicenseFields.Cell cell : LicenseFields.BACK) {
            String v = printed.get(cell.key); if (v == null) continue;
            int x=l+Math.round(gw*(cell.l+cell.r)/2), y=t+Math.round(gh*(cell.t+cell.b)/2);
            c.drawRect(x-9,y-5,x+9,y+5,p);
            String box = String.format(Locale.ROOT,"D(1,%d,%d,%d,%d,%d,%d,%d,%d)",
                    h-y+5,x-9,h-y+5,x+9,h-y-5,x+9,h-y-5,x-9);
            words.put(obj("content",v,"source",box));
        }
        android.graphics.Matrix matrix = new android.graphics.Matrix(); matrix.setRotate(90);
        android.graphics.Bitmap rotated = android.graphics.Bitmap.createBitmap(b,0,0,w,h,matrix,true);
        try (ReadingHistory db = new ReadingHistory(RuntimeEnvironment.getApplication())) {
            JSONObject page = obj("pageNumber",1,"angle",90,"width",h,"height",w,"words",words);
            JSONObject body = obj("result",obj("contents",new JSONArray().put(obj("pages",new JSONArray().put(page),
                    "fields",obj("Mobilization",obj("valueString","000321"))))));
            AzureReader api = new AzureReader(catalog,config,(m,u,hdr,k,data)->reply(200,null,body),ms->{});
            CardCatalog.Card type = catalog.cards.get("dl_back");
            byte[] photo = CardPhoto.encoded(rotated,android.graphics.Bitmap.CompressFormat.JPEG,94);
            AzureReader.Reading read = api.read(type,photo,true,msg->{});
            assertEquals("000321", read.fields.getJSONObject("Mobilization").getString("valueString"));
            LicenseFields.readImage(RuntimeEnvironment.getApplication(),read,photo);
            JSONObject result = licenseJson(type,db.get(db.add(read)));
            assertEquals("000321",result.getString("印製號碼"));
            assertEquals("",result.getString("動員編號"));
            assertEquals("無限制",result.getString("持照條件 A"));
            assertEquals("自動排檔",result.getString("持照條件 B"));
            assertEquals("",result.getString("C 加註條件"));
            assertEquals("",result.getString("住址變更 1"));
            assertEquals("測試路二號",result.getString("住址變更 2"));
            assertEquals("甲所",result.getString("住址變更 2 登記機關章戳"));
            assertEquals("",result.getString("住址變更 3"));
            assertEquals("",result.getString("職業駕駛審驗 1"));
            assertEquals("112.07.10",result.getString("職業駕駛審驗 2"));
            assertEquals("乙所",result.getString("職業駕駛審驗 2 登記機關章戳"));
            assertEquals("",result.getString("備註"));
        } finally { rotated.recycle(); b.recycle(); }
    }

    @Test public void licenseTranscriptRecoversFieldsFromSplitAndMisreadHeadingsInHistory() throws Exception {
        CardCatalog.Card type = catalog.cards.get("dl_front");
        String text = String.join("\n", "中", "年間", "國", "交通部製發汽車駕駛執照",
                "駕照", "號碼", "Q123400067", "性別", "女", "姓", "名", "林甲乙", "樣張",
                "出生", "日期", "061.02.03", "等無", "住量", "普通小型車", "訂井", "B",
                "住", "址", "測試市測試路1號", "IV", "有效", "日期", "139.02.03",
                "管格", "編號", "0010907031002", "日期", "101.07.17", "客軟", "日有");
        AzureReader.Reading reading = new AzureReader.Reading(type,text,null);
        // Old scans can contain an empty crop and a false negative ink test.
        reading.local.put("LicenseCells",obj("LicenseClass","","ConditionCode","","IssueDate",""));
        reading.local.put("LicenseBlankCells",obj("ExpiryDate",true,"ControlNumber",true));
        try (ReadingHistory db = new ReadingHistory(RuntimeEnvironment.getApplication())) {
            JSONObject fields = licenseJson(type,db.get(db.add(reading)));
            assertEquals("普通小型車",fields.getString("駕照種類"));
            assertEquals("B",fields.getString("持照條件代碼"));
            assertEquals("061.02.03",fields.getString("出生日期"));
            assertEquals("139.02.03",fields.getString("有效日期"));
            assertEquals("0010907031002",fields.getString("管轄／管制編號"));
            assertEquals("101.07.17",fields.getString("發照日期"));
            assertEquals("",fields.getString("審驗日期"));
            assertEquals("交通部",fields.getString("發照機關"));
        }
    }

    @Test public void licenseBackReadsNumberAndReorderedLegendsWithoutFillingEmptyRecords() throws Exception {
        CardCatalog.Card type = catalog.cards.get("dl_back");
        String text = String.join("\n", "印製", "號碼", "（甲）", "00357291", "動員", "編號",
                "B寓秋自動排檔車", "A典尿制", "C加註條件:", "樣", "張", "登", "記", "機", "關",
                "期罰款·逾別", "一年本照经销", "位", "址", "史", "更");
        JSONObject fields = licenseJson(type,new AzureReader.Reading(type,text,null));
        assertEquals("00357291",fields.getString("印製號碼").replaceAll("[^0-9]",""));
        assertTrue(fields.getString("印製號碼").contains("甲"));
        assertEquals("無限制",fields.getString("持照條件 A"));
        assertEquals("駕駛自動排檔車",fields.getString("持照條件 B"));
        for (Iterator<String> it=fields.keys();it.hasNext();) {
            String label=it.next();
            if (!label.equals("印製號碼") && !label.startsWith("持照條件")) assertEquals(label,"",fields.getString(label));
        }
    }

    @Test public void licenseTranscriptLeavesAmbiguousDatesNumbersAndPersonalConditionsAlone() throws Exception {
        CardCatalog.Card front = catalog.cards.get("dl_front");
        String text = "職業大客車\n住址\n測試市一區\n有效\n日期\n130.01.15\n管制編號\n001234567890\n審驗日期\n106.01.02";
        JSONObject fields = licenseJson(front,new AzureReader.Reading(front,text,null));
        assertEquals("職業大客車",fields.getString("駕照種類"));
        assertEquals("",fields.getString("持照條件代碼"));
        assertEquals("",fields.getString("發照日期"));
        assertEquals("106.01.02",fields.getString("審驗日期"));
        CardCatalog.Card back = catalog.cards.get("dl_back");
        fields = licenseJson(back,new AzureReader.Reading(back,"印製\n號碼\n動員\n編號\n0000198\nB限駕特製車\nC加註條件：須戴眼鏡",null));
        assertEquals("",fields.getString("印製號碼"));
        assertEquals("0000198",fields.getString("動員編號"));
        assertEquals("限駕特製車",fields.getString("持照條件 B"));
        assertEquals("",fields.getString("持照條件 A"));
        assertEquals("須戴眼鏡",fields.getString("C 加註條件"));
    }


    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void frontGridUsesOuterEdgesWhenReflectionsInterruptLongRules() {
        android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(650,450,android.graphics.Bitmap.Config.ARGB_8888);
        image.eraseColor(android.graphics.Color.WHITE);
        android.graphics.Canvas canvas = new android.graphics.Canvas(image);
        android.graphics.Paint pen = new android.graphics.Paint();
        pen.setColor(android.graphics.Color.BLACK);
        pen.setStrokeWidth(2);
        int top = 40, height = 375, left = 32, right = 575;
        double[] rows = {0,.267,.356,.535,.625,.832,.919,1};
        int[] starts = {left,195,left,185,185,110,left,left};
        int[] ends = {365,395,291,550,right,right,455,540};
        for (int i=0; i<rows.length; i++) {
            float y = top + (float)rows[i]*height;
            canvas.drawLine(starts[i],y,ends[i],y,pen);
        }
        for (int edge : new int[]{left,69,right}) canvas.drawLine(edge,top,edge,top+height,pen);
        LicenseGrid grid = LicenseGrid.find(image,true);
        assertNotNull(grid);
        android.graphics.Rect bounds = grid.region(image,0,0,1,1);
        assertEquals(left,bounds.left,5);
        assertEquals(right,bounds.right,5);
        image.recycle();
    }

    @Test public void upgradesUntouchedDriverFieldTemplatesAndPreservesEditedDefinitions() throws Exception {
        JSONArray saved = new JSONArray();
        saved.put(obj("key", "PrintNumber", "label", "印製號碼", "description", "印製號碼，包含括號及前綴。"));
        saved.put(obj("key", "Mobilization", "label", "動員編號", "description", "已填寫的動員編號。"));
        saved.put(obj("key", "Conditions", "label", "持照條件加註", "description", "僅擷取已填寫、勾選或加註的個別條件，勿將通用 A/B/C 圖例當成個人限制。"));
        saved.put(obj("key", "ChangeOfAddress", "label", "住所變更", "description", "實際登記的住所變更。"));
        saved.put(obj("key", "Registration", "label", "登記機關及日期", "description", "已填寫的登記機關與年月日。"));
        saved.put(obj("key", "Records", "label", "審驗或紀錄", "description", "職業駕駛審驗或其他已填寫紀錄。"));
        saved.put(obj("key", "Notes", "label", "備註", "description", "已填寫的個人備註。"));
        catalog.save("dl_back", "自訂背面", "我的分類描述", saved, true);
        CardCatalog.Card upgraded = new CardCatalog(RuntimeEnvironment.getApplication()).cards.get("dl_back");
        assertEquals(16, upgraded.fields.length());
        assertEquals("我的分類描述", upgraded.criteria);
        assertEquals("自訂背面", upgraded.label);
        assertTrue(upgraded.autoFields);
        saved.getJSONObject(0).put("description", "自訂讀取說明");
        catalog.save("dl_back", upgraded.label, upgraded.criteria, saved, true);
        assertEquals(saved.toString(), catalog.cards.get("dl_back").fields.toString());
    }

    @Test public void renamedLicenseFieldsAndOldCombinedRulesRetainTheirKeysAndValues() throws Exception {
        JSONArray definitions = new JSONArray().put(obj("key","Records","label","我的審驗紀錄","description","登載日期"))
                .put(obj("key","Notes","label","備註","description","附註"));
        CardCatalog.Card type = new CardCatalog.Card(obj("code","dl_back","label","駕照背面","criteria","測試","fields",definitions));
        AzureReader.Reading read = new AzureReader.Reading(type,"",null);
        read.local.put("LicenseCells",obj("ReviewRecord1","110.01.02","ReviewRecord2","111.01.03"));
        JSONObject fields = licenseJson(type,read);
        assertEquals("110.01.02\n111.01.03",fields.getString("我的審驗紀錄"));
        assertEquals("",fields.getString("備註"));
    }

    @Test public void backUsesSingleRawBarcodeAndSeparateSerialAndKeepsBlankMilitaryFieldInHistoryAndJson() throws Exception {
        assertEquals("常備兵役", IdCardDetails.militaryService("配偶 張甲　役 別：常備兵役\n出生地 臺中市"));
        assertEquals("替代役", IdCardDetails.militaryService("役\n別\n替代役\n住址"));
        assertEquals("", IdCardDetails.militaryService("役別　樣本\n出生地 臺中市"));
        assertEquals("", IdCardDetails.militaryService("役別\n臺中市"));
        try (ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
             ReadingHistory db = new ReadingHistory(ctl.get())) {
            for (String barcode : new String[]{"B212300007", "0000000105", "579246", "000043", ""}) {
                AzureReader.Reading back = new AzureReader.Reading(catalog.cards.get("id_back"),
                        "配偶 張甲　役別\n出生地 臺中市\n0000000105", obj("Spouse", obj("valueString", "張甲")));
                back.local.put("Barcode", barcode);
                ReaderScreen ui = new ReaderScreen(ctl.get());
                LinearLayout page = ui.col();
                new ResultLayout(ui, page).show(db.get(db.add(back)));
                assertNull(find(page, "身分證字號"));
                assertNull(find(page, "左下角條碼"));
                assertNotNull(find(page, "役別"));
                JSONObject fields = new JSONObject(((TextView)page.findViewWithTag("transcript-json")).getText().toString()).getJSONObject("欄位");
                assertEquals("", fields.getString("役別"));
                assertEquals("0000000105", fields.getString("證件流水號"));
                assertTrue(fields.has(IdCardDetails.BARCODE_LABEL));
                if (barcode.isEmpty()) assertTrue(fields.isNull(IdCardDetails.BARCODE_LABEL));
                else assertEquals(barcode, fields.getString(IdCardDetails.BARCODE_LABEL));
                assertFalse(fields.has("身分證字號"));
                assertFalse(fields.has("左下角條碼"));
            }
            AzureReader.Reading old = new AzureReader.Reading(catalog.cards.get("id_back"), "役別 免役", null);
            LinearLayout page = new ReaderScreen(ctl.get()).col();
            new ResultLayout(new ReaderScreen(ctl.get()), page).show(old);
            assertNotNull(find(page, "免役"));
        }
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void liveOutlineShowsAllFourColoredEdgesAndKeepsTheInteriorClear() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        LiveScanActivity.Outline overlay = new LiveScanActivity.Outline(context);
        int w = 320, h = 200;
        float density = context.getResources().getDisplayMetrics().density;
        int[] gray = new int[w * h];
        overlay.layout(0, 0, w, h);
        for (int margin : new int[]{34, 8}) {
            Arrays.fill(gray, 25);
            for (int y = margin; y < h - margin; y++) Arrays.fill(gray, y*w + margin, (y+1)*w - margin, 220);
            DocumentFrame frame = DocumentFrame.detect(gray, w, h, null);
            assertNotNull(frame);
            overlay.show(frame);
            android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(image);
            overlay.draw(canvas);
            assertEquals(0, image.getPixel(w/2,h/2));
            int shade = android.graphics.Color.alpha(image.getPixel(0,0));
            assertTrue(shade > 0 && shade < 100);
            int accent = margin == 34 ? LiveScanActivity.Outline.AMBER : LiveScanActivity.Outline.MINT;
            for (int side = 0; side < 4; side++) {
                int strokePixels = 0, accentPixels = 0;
                int length = side % 2 == 0 ? h : w;
                for (int offset = 0; offset < length/2; offset++) {
                    int pos = side < 2 ? offset : length - 1 - offset;
                    int pixel = side % 2 == 0 ? image.getPixel(w/2,pos) : image.getPixel(pos,h/2);
                    if (android.graphics.Color.alpha(pixel) > shade) strokePixels++;
                    if (pixel == accent) accentPixels++;
                }
                assertTrue("continuous colored edge " + side, accentPixels >= Math.max(1,(int)(2.6f*density)-1));
                assertTrue("contrasting edge " + side, strokePixels >= Math.floor(4.4f*density)-1);
                assertTrue("bounded stroke " + side, strokePixels <= Math.ceil(4.4f*density)+2);
            }
            for (int i=0; i<8; i+=2) {
                int cx=Math.round(frame.corners[i]*w), cy=Math.round(frame.corners[i+1]*h);
                boolean found = false;
                int reach = (int)Math.ceil(10*density);
                for (int y=Math.max(0,cy-reach); y<Math.min(h,cy+reach); y++)
                    for (int x=Math.max(0,cx-reach); x<Math.min(w,cx+reach); x++)
                        found |= image.getPixel(x,y) == accent;
                assertTrue("missing corner accent " + i, found);
            }
            overlay.show(null);
            image.eraseColor(android.graphics.Color.TRANSPARENT);
            overlay.draw(canvas);
            assertEquals(0, image.getPixel(w/2,h/2));
            int idleShade = android.graphics.Color.alpha(image.getPixel(0,0));
            for (int y=0; y<h/2; y++)
                assertTrue(android.graphics.Color.alpha(image.getPixel(w/2,y)) <= idleShade);
            image.recycle();
        }
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void slightlyTiltedShortBarcodeSuppliesBackIdFromPixels() throws Exception {
        String number = "B212300007";
        int w = 450, h = 36;
        com.google.zxing.common.BitMatrix bars = new com.google.zxing.oned.Code128Writer().encode(
                number, com.google.zxing.BarcodeFormat.CODE_128, w, h);
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
            pixels[y*w+x] = bars.get(x, y) ? android.graphics.Color.BLACK : android.graphics.Color.WHITE;
        android.graphics.Bitmap strip = android.graphics.Bitmap.createBitmap(pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Bitmap photo = android.graphics.Bitmap.createBitmap(900, 560, android.graphics.Bitmap.Config.ARGB_8888);
        photo.eraseColor(android.graphics.Color.WHITE);
        android.graphics.Canvas c = new android.graphics.Canvas(photo);
        c.rotate(15, 310, 445);
        c.drawBitmap(strip, 85, 427, null);
        try (ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup()) {
            AzureReader.Reading reading = new AzureReader.Reading(catalog.cards.get("id_back"), "役別 常備兵役\n0000000105", null);
            IdCardDetails.readImage(ctl.get(), reading, CardPhoto.encoded(photo, android.graphics.Bitmap.CompressFormat.JPEG, 94));
            ReaderScreen ui = new ReaderScreen(ctl.get());
            LinearLayout page = ui.col();
            new ResultLayout(ui, page).show(reading);
            JSONObject fields = new JSONObject(((TextView)page.findViewWithTag("transcript-json")).getText().toString()).getJSONObject("欄位");
            assertFalse(fields.has("身分證字號"));
            assertEquals(number, fields.getString(IdCardDetails.BARCODE_LABEL));
            assertEquals("常備兵役", fields.getString("役別"));
            assertEquals("0000000105", fields.getString("證件流水號"));
        } finally { photo.recycle(); strip.recycle(); }
    }

    @Test public void idFrontShowsPrintedSexWithSplitLabelsAndPartialCloudFields() throws Exception {
        assertEquals("女", IdCardDetails.printedSex("發證日期  性\n 別：女\n統一編號"));
        assertEquals("男", IdCardDetails.printedSex("性別男統一編號"));
        assertEquals("男", IdCardDetails.printedSex("性別\n其他欄位\n男"));
        assertEquals("女", IdCardDetails.printedSex("女\n統一編號"));
        assertEquals("", IdCardDetails.printedSex("姓名 林女士\n統一編號 A200000001"));
        assertEquals("", IdCardDetails.printedSex("性別\n女士姓名"));
        assertEquals("", IdCardDetails.printedSex("性別 男\n性別 女"));
        try (ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup()) {
            ReaderScreen ui = new ReaderScreen(ctl.get());
            LinearLayout page = ui.col();
            String raw = "性\n別　女\n統一編號";
            AzureReader.Reading reading = new AzureReader.Reading(catalog.cards.get("id_front"), raw,
                    obj("Name", obj("valueString", "林以安")));
            new ResultLayout(ui, page).show(reading);
            assertNotNull(find(page, "性別"));
            assertNotNull(find(page, "女"));
            JSONObject fields = new JSONObject(((TextView)page.findViewWithTag("transcript-json")).getText().toString()).getJSONObject("欄位");
            assertEquals("女", fields.getString("性別"));
            assertEquals("林以安", fields.getString("姓名"));
            reading = new AzureReader.Reading(catalog.cards.get("id_front"), "統一編號 A200000001", null);
            reading.local.put("Sex", "男");
            try (ReadingHistory db = new ReadingHistory(ctl.get())) {
                assertEquals("男", db.get(db.add(reading)).local.getString("Sex"));
            }
            page = ui.col();
            new ResultLayout(ui, page).show(reading);
            assertNotNull(find(page, "男"));
        }
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void idBackDecodesActualBarsAndKeepsThemSeparateFromSerialAcrossHistory() throws Exception {
        String payload = "00042A7";
        android.graphics.Bitmap photo = android.graphics.Bitmap.createBitmap(900, 560, android.graphics.Bitmap.Config.ARGB_8888);
        photo.eraseColor(android.graphics.Color.WHITE);
        com.google.zxing.common.BitMatrix bars = new com.google.zxing.oned.Code39Writer().encode(
                payload, com.google.zxing.BarcodeFormat.CODE_39, 380, 82);
        int[] pixels = new int[380 * 82];
        for (int y = 0; y < 82; y++) for (int x = 0; x < 380; x++)
            pixels[y * 380 + x] = bars.get(x, y) ? android.graphics.Color.BLACK : android.graphics.Color.WHITE;
        photo.setPixels(pixels, 0, 380, 70, 55, 380, 82);
        assertEquals("", IdCardDetails.barcode(photo));
        photo.setPixels(pixels, 0, 380, 70, 405, 380, 82);
        android.graphics.Paint ink = new android.graphics.Paint();
        ink.setTextSize(26);
        new android.graphics.Canvas(photo).drawText("0000000105", 695, 502, ink);
        try (ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
             ReadingHistory db = new ReadingHistory(ctl.get())) {
            for (int angle : new int[]{0, 90, 180, -90}) {
                android.graphics.Matrix matrix = new android.graphics.Matrix();
                matrix.setRotate(angle);
                android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(photo, 0, 0, 900, 560, matrix, true);
                byte[] jpeg = CardPhoto.encoded(image, android.graphics.Bitmap.CompressFormat.JPEG, 93);
                AzureReader.Reading reading = new AzureReader.Reading(catalog.cards.get("id_back"), "住址 測試路\n0000000105",
                        obj("Serial", obj("valueString", "0000000105")), new JSONObject(), angle);
                IdCardDetails.readImage(ctl.get(), reading, jpeg);
                assertEquals(payload, reading.local.getString("Barcode"));
                ReaderScreen ui = new ReaderScreen(ctl.get());
                LinearLayout page = ui.col();
                new ResultLayout(ui, page).show(db.get(db.add(reading)));
                assertNotNull(find(page, IdCardDetails.BARCODE_LABEL));
                JSONObject values = new JSONObject(((TextView)page.findViewWithTag("transcript-json")).getText().toString()).getJSONObject("欄位");
                assertEquals(payload, values.getString(IdCardDetails.BARCODE_LABEL));
                assertEquals("0000000105", values.getString("證件流水號"));
                if (image != photo) image.recycle();
            }
        } finally { photo.recycle(); }
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void liveHintIsProminentAndSixtyPercentIsTheCaptureBoundary() {
        assertFalse(LiveScanActivity.coversEnough(.599f));
        assertTrue(LiveScanActivity.coversEnough(.60f));
        assertTrue(LiveScanActivity.coversEnough(.75f));
        try (ActivityController<LiveScanActivity> ctl = Robolectric.buildActivity(LiveScanActivity.class).create()) {
            View root = ctl.get().findViewById(android.R.id.content);
            TextView hint = (TextView)find(root, "請將文件四角放入取景框");
            assertNotNull(hint);
            assertTrue(hint.getTextSize() >= 26 * ctl.get().getResources().getDisplayMetrics().scaledDensity);
            assertTrue(hint.getTypeface().isBold());
            assertNotNull(hint.getBackground());
            assertNotNull(find(root, "占取景框 60% 以上、文字清晰且穩定時，自動裁切並送往 Azure 辨識。"));
        }
    }

    @Test public void viewfinderUsesQuadrilateralAreaAndStableCorners() {
        int w = 320, h = 200;
        int[] gray = new int[w*h];
        DocumentFrame near = null;
        for (int margin : new int[]{15, 8, 1}) {
            Arrays.fill(gray, 25);
            for (int y = margin; y < h-margin; y++)
                Arrays.fill(gray, y*w + margin, (y+1)*w - margin, 220);
            DocumentFrame frame = DocumentFrame.detect(gray, w, h, null);
            assertNotNull(frame);
            if (margin == 15) assertTrue(frame.coverage < .8f);
            if (margin == 8) {
                assertTrue(frame.coverage >= .8f);
                assertFalse(frame.clipped);
                assertTrue(frame.closeTo(DocumentFrame.detect(gray, w, h, null)));
                near = frame;
            }
            if (margin == 1) {
                assertTrue(frame.clipped);
                assertFalse(frame.closeTo(near));
            }
        }
        Arrays.fill(gray, 180);
        assertNull(DocumentFrame.detect(gray, w, h, null));
        assertFalse(DeviceText.sharp(gray, w, h));
        for (int i = 0; i < gray.length; i++) gray[i] = (i % w / 3 % 2) * 150;
        assertTrue(DeviceText.sharp(gray, w, h));
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void tiltedDocumentCropContainsPaperAndRemovesOutsideBackground() {
        android.graphics.Bitmap original = android.graphics.Bitmap.createBitmap(480, 320, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(original);
        canvas.drawColor(android.graphics.Color.BLUE);
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(19, 22); path.lineTo(451, 10); path.lineTo(460, 304); path.lineTo(20, 302); path.close();
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(android.graphics.Color.WHITE);
        canvas.drawPath(path, paint);
        DocumentFrame doc = DocumentFrame.detect(original, new android.graphics.Rect(75, 50, 390, 250));
        assertNotNull(doc);
        assertTrue(doc.coverage + " " + Arrays.toString(doc.corners), doc.coverage >= .80f && !doc.clipped);
        android.graphics.Bitmap crop = doc.crop(original);
        assertTrue(crop.getWidth() < original.getWidth() && crop.getHeight() < original.getHeight());
        for (int x : new int[]{6, crop.getWidth()-7}) for (int y : new int[]{6, crop.getHeight()-7})
            assertTrue(x + "," + y + " " + Arrays.toString(doc.corners), android.graphics.Color.red(crop.getPixel(x,y)) > 200);
        crop.recycle(); original.recycle();
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void sharpBackgroundDoesNotPassForBlurryDocumentText() {
        android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(320, 180, android.graphics.Bitmap.Config.ARGB_8888);
        int[] pixels = new int[320*180];
        for (int i = 0; i < pixels.length; i++) pixels[i] = i % 320 / 3 % 2 == 0 ? 0xff303030 : 0xffeeeeee;
        image.setPixels(pixels, 0, 320, 0, 0, 320, 180);
        android.graphics.Rect box = new android.graphics.Rect(70, 70, 220, 100);
        java.util.List<android.graphics.Point> points = Arrays.asList(new android.graphics.Point(70,70),
                new android.graphics.Point(220,70), new android.graphics.Point(220,100), new android.graphics.Point(70,100));
        com.google.mlkit.vision.text.Text.Line line = new com.google.mlkit.vision.text.Text.Line(
                "測試文字", box, points, "zh", null, Collections.emptyList(), 1, 0);
        com.google.mlkit.vision.text.Text.TextBlock block = new com.google.mlkit.vision.text.Text.TextBlock(
                "測試文字", box, points, "zh", null, Collections.singletonList(line));
        com.google.mlkit.vision.text.Text recognized = new com.google.mlkit.vision.text.Text(
                "測試文字", Collections.singletonList(block));
        assertEquals(box, DeviceText.documentTextBounds(recognized, image));
        assertTrue(DeviceText.legible(recognized, image, null));
        android.graphics.Canvas canvas = new android.graphics.Canvas(image);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(0xffbbbbbb);
        canvas.drawRect(65,65,225,105,paint);
        assertFalse(DeviceText.legible(recognized, image, null));
        image.recycle();
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

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void liveAndGallerySamplesAreClassifiedReadAndSavedWithOriginalText() throws Exception {
        for (String source : new String[]{"live", "liveWithPreview", "gallery"}) {
            boolean gallery = source.equals("gallery");
            boolean review = !source.equals("live");
            try (ActivityController<OfflineActivity> ctl = Robolectric.buildActivity(OfflineActivity.class).setup()) {
                OfflineActivity activity = ctl.get();
                setField(activity, "config", config);
                List<String> calls = Collections.synchronizedList(new ArrayList<>());
                activity.reader = new AzureReader(catalog, config, (method, url, header, key, body) -> {
                    if (url.endsWith(tw.codeviva.cardreader.Config.MS)) {
                        calls.add("classify");
                        return reply(200,null,obj("answers",obj(AzureReader.QUESTION,obj("type","choice","choice","other"))));
                    }
                    byte[] bytes = android.util.Base64.decode(body.getJSONArray("inputs").getJSONObject(0).getString("data"), 0);
                    android.graphics.Bitmap uploaded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    assertEquals(600, uploaded.getWidth());
                    assertEquals(370, uploaded.getHeight());
                    uploaded.recycle();
                    calls.add("read");
                    return reply(200,null,obj("result",obj("contents",new JSONArray().put(obj("markdown","樣 本\n裁切文字")))));
                }, ms -> {});
                View root = activity.findViewById(android.R.id.content);
                find(root, gallery ? "從相簿選取" : "即時辨識").performClick();
                org.robolectric.shadows.ShadowActivity.IntentForResult req = Shadows.shadowOf(activity).getNextStartedActivityForResult();
                java.io.File file = gallery ? java.io.File.createTempFile("sample", ".jpg", activity.getCacheDir())
                        : CaptureProvider.file(activity, req.intent.getData());
                android.graphics.Bitmap crop = android.graphics.Bitmap.createBitmap(600,370,android.graphics.Bitmap.Config.ARGB_8888);
                crop.eraseColor(android.graphics.Color.WHITE);
                try (java.io.OutputStream out = new java.io.FileOutputStream(file)) {
                    assertTrue(crop.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out));
                }
                crop.recycle();
                Intent result = gallery ? new Intent().setData(Uri.fromFile(file)) : new Intent().putExtra(LiveScanActivity.REVIEW_CROP, review);
                activity.onActivityResult(req.requestCode, Activity.RESULT_OK, result);
                if (review) {
                    awaitText(root,"開始辨識");
                    assertTrue(calls.isEmpty());
                    find(root,"開始辨識").performClick();
                }
                awaitText(root,"辨識原文（JSON）");
                assertEquals(Arrays.asList("classify","read"), calls);
                TextView json = root.findViewWithTag("transcript-json");
                assertEquals(Collections.singletonList("裁切文字"),
                        jsonStrings(new JSONObject(json.getText().toString()).getJSONArray("原文逐行")));
                try (ReadingHistory store = new ReadingHistory(activity)) {
                    assertEquals("樣 本\n裁切文字", store.get(store.list().get(0).id).text);
                }
                if (gallery) assertTrue(file.delete());
                else assertFalse(file.exists());
            }
        }
    }

    @Test public void scanButtonClassifiesBeforeOcrAndCanPauseForReview() throws Exception {
        for (boolean reviewFirst : new boolean[]{false, true}) {
            try (ActivityController<OfflineActivity> controller = Robolectric.buildActivity(OfflineActivity.class).setup()) {
                OfflineActivity activity = controller.get();
                List<String> calls = Collections.synchronizedList(new ArrayList<>());
                activity.reader = new AzureReader(catalog, config, (method,url,header,key,body) -> {
                    if (url.endsWith(tw.codeviva.cardreader.Config.MS)) {
                        calls.add("classify");
                        return reply(200,null,obj("usage",obj("input_tokens",1000,"output_tokens",0),"answers",obj(AzureReader.QUESTION,obj("type","choice","choice","receipt"))));
                    }
                    if (!url.contains("prebuilt-layout:analyze")) throw new IOException("Wrong analyzer");
                    calls.add("read");
                    return reply(200,null,obj("usage",obj("documentPagesStandard",1),"result",obj("contents",new JSONArray().put(obj("markdown","合計 365")))));
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
                    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(10));
                    find(root, "讀取文字").performClick();
                }
                awaitText(root, "辨識原文（JSON）");
                TextView json = root.findViewWithTag("transcript-json");
                assertEquals("合計 365", new JSONObject(json.getText().toString()).getJSONArray("原文逐行").getString(0));
                assertEquals(Arrays.asList("classify","read"), calls);
                assertNotNull(find(root, "收據／發票"));
                assertNotNull(find(root, "本次辨識用量"));
                try (ReadingHistory db = new ReadingHistory(activity)) {
                    ReadingHistory.Entry entry = db.list().get(0);
                    JSONObject usage = db.get(entry.id).usage;
                    assertEquals(1000, usage.getLong("tokens"));
                    assertEquals("0.005042", usage.getString("usd"));
                    assertTrue(usage.getLong("apiElapsedMs") < 600000);
                    activity.onBackPressed();
                    assertNotNull(find(root, entry.usageSummary));
                }
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
        assertEquals(72, fieldCount);
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
        fail("Missing screen text " + text + ", current UI " + visibleText(root));
    }

    private static String visibleText(View root) {
        if (root instanceof TextView) return ((TextView)root).getText().toString();
        StringBuilder values = new StringBuilder();
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)root;
            for (int i = 0; i < group.getChildCount(); i++) values.append(visibleText(group.getChildAt(i))).append(' ');
        }
        return values.toString().trim();
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
