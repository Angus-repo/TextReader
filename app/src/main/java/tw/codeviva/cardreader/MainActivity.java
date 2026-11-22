package tw.codeviva.cardreader;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static tw.codeviva.cardreader.ReaderScreen.*;

public class MainActivity extends Activity {
    private static final int CAMERA=31,GALLERY=32,EXPORT=33,IMPORT=34,BARCODE=35;
    private String barcodeRecord;
    private ReaderScreen ui;
    private LinearLayout content;
    private ScrollView scroll;
    private Config config;
    private CardCatalog catalog;
    private ReadingHistory history;
    private int historyLimit = 30;
    private boolean confirmType;
    private int fieldMode; // 0: use type settings, 1: include fields, 2: text only
    private RuleEditor ruleEditor;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private CardPhoto photo;
    private String screen="home",selected=null,classified=null,status="",error="";
    private boolean busy;
    private int sequence;
    private Future<?> running;
    private Uri capture;

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::onBackPressed);
        ui=new ReaderScreen(this);
        history = new ReadingHistory(this);
        try {catalog=new CardCatalog(this);}catch(Exception e){throw new IllegalStateException(e);}
        try {config=Config.load(this);}catch(Exception e){config=new Config();error="無法解密連線設定，請重新設定 Azure 連線。";}
        if (saved != null) barcodeRecord = saved.getString("barcodeRecord");
        if(saved!=null && saved.getString("capture")!=null) capture=Uri.parse(saved.getString("capture"));
        File[] leftovers=CaptureProvider.dir(this).listFiles();
        if(leftovers!=null) for(File f:leftovers) if(capture==null || !f.equals(CaptureProvider.file(this,capture))) f.delete();
        home();
    }
    private void shell(String where,String title,Runnable back){
        screen=where;
        LinearLayout outer=ui.col();outer.setBackgroundColor(BG);
        if(Build.VERSION.SDK_INT>=30)outer.setOnApplyWindowInsetsListener((v,i)->{
            android.graphics.Insets a=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
            v.setPadding(a.left,a.top,a.right,a.bottom);return i;
        });
        LinearLayout bar=ui.row();ui.pad(bar,16,6);bar.setMinimumHeight(ui.dp(62));outer.addView(bar);
        if(back!=null){Button b=ui.action("‹ 返回",false,back);bar.addView(b);ui.gap(bar,12);}
        TextView heading=ui.text(title,19,INK,true);ui.weight(bar,heading);
        if (where.equals("home")) {
            bar.addView(ui.action("辨識規則", false, this::rules));
            ui.gap(bar, 4);
            bar.addView(ui.action("連線設定", false, this::settings));
        }
        ui.line(outer);
        scroll=new ScrollView(this);scroll.setFillViewport(true);outer.addView(scroll,-1,-1);
        content=ui.col();ui.pad(content,18,20);scroll.addView(content);setContentView(outer);
    }
    private void heading(LinearLayout p,String s){p.addView(ui.text(s,20,INK,true));}
    private void note(LinearLayout p,String s){p.addView(ui.text(s,14,MUTED,false));}
    private void showError(LinearLayout p){
        if(error.isEmpty())return;
        ui.gap(p,12);TextView t=ui.text(error,15,0xff863b24,false);t.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        t.setBackground(ui.bg(0xffffeae1,10,false));ui.pad(t,12,10);p.addView(t);
    }
    private void home(){
        shell("home","TextReader",null);
        LinearLayout hero=ui.col();ui.pad(hero,20,18);hero.setBackground(ui.bg(GREEN,21,false));content.addView(hero);
        LinearLayout top=ui.row();hero.addView(top);LinearLayout words=ui.col();ui.weight(top,words);
        words.addView(ui.text("讀取影像文字",27,WHITE,true));ui.gap(words,6);words.addView(ui.text("先辨識類型，再讀取全文",14,0xffd6eae1,false));
        top.addView(new ReaderScreen.PageArt(this),ui.dp(98),ui.dp(74));
        ui.gap(hero,14);LinearLayout actions=ui.row();hero.addView(actions);
        ui.weight(actions,ui.action("即時辨識",false,()->source(true)));ui.gap(actions,8);
        ui.weight(actions,ui.action("從相簿選取",false,()->source(false)));
        List<ReadingHistory.Entry> entries;
        try {
            entries = history.list();
        } catch (android.database.SQLException e) {
            entries = Collections.emptyList();
            error = "無法讀取手機上的辨識紀錄。";
        }
        showError(content);
        if(!config.ready()){ui.gap(content,12);Button setup=ui.action("設定 Azure 連線以開始辨識",false,this::settings);content.addView(setup);}
        ui.gap(content,24);LinearLayout label=ui.row();content.addView(label);ui.weight(label,ui.text("辨識紀錄",21,INK,true));
        label.addView(ui.text(entries.size() + " 筆", 14, GREEN, true));
        ui.gap(content, 12);
        LinearLayout list = ui.panel();
        content.addView(list);
        if (entries.isEmpty()) {
            note(list, "完成辨識後，結果會自動儲存在這支手機。");
        } else {
            java.text.DateFormat date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);
            for (int i = 0; i < Math.min(historyLimit, entries.size()); i++) {
                ReadingHistory.Entry entry = entries.get(i);
                if (i > 0) ui.line(list);
                ui.tile(list, entry.label, date.format(new Date(entry.time)), true, entry.usageSummary, () -> openReading(entry.id));
            }
            if (entries.size() > historyLimit)
                list.addView(ui.action("顯示更多紀錄", false, () -> { historyLimit += 30; home(); }));
        }
        ui.gap(content, 14);
        LinearLayout files = ui.row();
        content.addView(files);
        if (!entries.isEmpty()) {
            ui.weight(files, ui.action("匯出備份", false, () -> chooseBackup(true)));
            ui.gap(files, 8);
        }
        ui.weight(files, ui.action("匯入備份", false, () -> chooseBackup(false)));
        ui.gap(content, 8);
        note(content, "覆蓋更新會保留紀錄。解除安裝前可匯出備份，JSON 檔含明文辨識資料。");
        if (!entries.isEmpty()) {
            ui.gap(content,12);content.addView(ui.action("清除所有紀錄",false,this::confirmClear));
        }
    }
    private void confirmClear(){
        new AlertDialog.Builder(this).setTitle("清除所有辨識紀錄？").setMessage("將從這支手機刪除所有已儲存的辨識結果。")
            .setNegativeButton("取消",null).setPositiveButton("清除",(d,w)->{
                try {
                    history.clear();
                    historyLimit = 30;
                    error = "";
                } catch (android.database.SQLException e) {
                    error = "無法清除辨識紀錄。";
                }
                home();
            }).show();
    }

    private void chooseBackup(boolean export) {
        Intent intent = new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");
        if (export) intent.putExtra(Intent.EXTRA_TITLE, "TextReader-history.json");
        try {
            startActivityForResult(intent, export ? EXPORT : IMPORT);
        } catch (ActivityNotFoundException e) {
            error = "無法開啟系統檔案選擇器。";
            home();
        }
    }

    private void transferBackup(boolean export, Uri uri) {
        String action = export ? "匯出" : "匯入";
        shell("transfer", action + "備份", null);
        note(content, "正在" + action + "辨識紀錄…");
        running = worker.submit(() -> {
            try {
                int count;
                if (export) {
                    try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                        if (out == null) throw new IOException();
                        count = history.exportTo(out);
                    }
                } else {
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new IOException();
                        count = history.importFrom(in);
                    }
                }
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    error = "";
                    home();
                    String msg = export ? "已匯出 " + count + " 筆紀錄" : "已新增 " + count + " 筆紀錄，重複的紀錄已略過。";
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    error = export ? "匯出失敗，請確認目的地可寫入並重新匯出。" : "匯入失敗，請選取完整的 TextReader JSON 備份。";
                    home();
                });
            }
        });
    }
    private void source(boolean camera){
        error="";
        try {
            if(camera){
                File dir=CaptureProvider.dir(this);if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException();
                String name=UUID.randomUUID()+".jpg";capture=new Uri.Builder().scheme("content").authority(CaptureProvider.AUTHORITY).appendPath(name).build();
                if(!CaptureProvider.file(this,capture).createNewFile())throw new IOException();
                Intent i = new Intent(this, LiveScanActivity.class).setData(capture);
                startActivityForResult(i,CAMERA);
            }else{
                Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(i,GALLERY);
            }
        }catch(Exception e){clearCapture(capture);error=camera?"無法開啟相機，可改從相簿選取。":"無法開啟選圖工具。";home();}
    }
    private void clearCapture(Uri u){
        if(u==null)return;
        revokeUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        CaptureProvider.file(this,u).delete();if(u.equals(capture))capture=null;
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if (request == BARCODE) {
            String id = barcodeRecord;
            barcodeRecord = null;
            if (id == null) return;
            try {
                if (result == RESULT_OK && data != null) history.completeBarcode(id,
                        data.getStringExtra(LiveScanActivity.BARCODE_VALUE));
                openReading(id);
            } catch (JSONException | android.database.SQLException e) {
                error = "無法更新這筆紀錄的條碼。";
                home();
            }
            return;
        }
        if (request == EXPORT || request == IMPORT) {
            if (result == RESULT_OK && data != null && data.getData() != null)
                transferBackup(request == EXPORT, data.getData());
            return;
        }
        if(request!=CAMERA&&request!=GALLERY)return;
        final Uri temp=request==CAMERA?capture:null;
        Uri uri=request==CAMERA?temp:data==null?null:data.getData();
        if(result!=RESULT_OK||uri==null){clearCapture(temp);return;}
        final boolean autoRead = request == CAMERA && data != null &&
                !data.getBooleanExtra(LiveScanActivity.REVIEW_CROP, true);
        selected=null;classified=null;photo=null;error="";fieldMode=0;
        run("正在載入影像…",()->{
            try {
                return CardPhoto.load(this, uri);
            } finally { if(temp!=null)runOnUiThread(()->clearCapture(temp)); }
        }, item -> {
            photo = item;
            if (autoRead && config.ready()) classify();
            else preview();
        });
    }
    @Override protected void onSaveInstanceState(Bundle state){
        super.onSaveInstanceState(state);
        state.putString("barcodeRecord", barcodeRecord);
        if(capture!=null)state.putString("capture",capture.toString());
    }
    private interface Job<T>{T go()throws Exception;}
    private interface Done<T>{void accept(T value);}
    private <T> void run(String message,Job<T> task,Done<T> done){
        int token=++sequence;busy=true;status=message;error="";preview();
        running=worker.submit(()->{
            try{T value=task.go();runOnUiThread(()->{if(token!=sequence || isDestroyed())return;busy=false;done.accept(value);});}
            catch(Exception ex){runOnUiThread(()->{if(token!=sequence || isDestroyed())return;busy=false;
                if(ex instanceof java.net.SocketTimeoutException)error="連線逾時，請確認網路後再試。";
                else if(ex instanceof java.net.UnknownHostException)error="找不到 Azure 資源，請檢查端點與網路。";
                else if(ex instanceof JSONException)error="Azure 回傳的資料格式不符，請核對連線設定。";
                else error=ex.getMessage()==null?"操作失敗，請重新嘗試。":ex.getMessage();
                if(photo==null)home();else preview();});}
        });
    }
    private void abandon(){
        if(busy){new AlertDialog.Builder(this).setTitle("離開本次辨識？").setMessage("已送出的 Azure 作業可能繼續執行。")
            .setNegativeButton("繼續等待",null).setPositiveButton("離開",(d,w)->discard()).show();}
        else discard();
    }
    private void discard(){
        sequence++;if(running!=null)running.cancel(true);busy=false;photo=null;selected=null;classified=null;error="";home();
    }
    private void preview(){
        shell("preview","影像確認",this::abandon);
        if(photo!=null){
            ImageView image=new ImageView(this);image.setContentDescription("本次影像預覽");image.setImageBitmap(photo.preview);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setBackground(ui.bg(0xffe5e9e0,15,false));image.setClipToOutline(true);
            content.addView(image,-1,ui.dp(218));ui.gap(content,16);
        }
        LinearLayout box=ui.panel();content.addView(box);
        if(busy){
            LinearLayout row=ui.row();box.addView(row);ProgressBar spin=new ProgressBar(this);
            row.addView(spin,ui.dp(28),ui.dp(28));ui.gap(row,12);row.addView(ui.text(status,16,INK,true));return;
        }
        if(photo==null)return;
        if (selected == null) {
            heading(box, "確認照片");
            ui.gap(box, 8);
            note(box, "按下開始後，影像將傳送至設定的 Azure 資源進行分類與文字辨識。");
            ui.gap(box, 8);
            CheckBox review = new CheckBox(this);
            review.setText("讀取前讓我確認分類");
            review.setChecked(confirmType);
            review.setOnCheckedChangeListener((v, checked) -> confirmType = checked);
            box.addView(review);
        } else {
            heading(box, "文件類型");
            ui.gap(box, 6);
            note(box, classified == null ? "已手動選擇類型，可直接讀取這張影像。" : "Decisions 判定為" + catalog.cards.get(classified).label + "。");
            ui.gap(box, 8);
            box.addView(ui.action(catalog.cards.get(selected).label + "　⌄", false, this::selectType));
        }
        if (selected == null || catalog.cards.get(selected).hasFields()) {
            box.addView(ui.text("本次欄位讀取方式", 14, MUTED, true));
            Spinner mode = new Spinner(this);
            mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"依各類型設定", "全文及已設定的欄位", "只讀全文"}));
            mode.setSelection(fieldMode);
            mode.setContentDescription("本次欄位讀取方式");
            mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                public void onNothingSelected(AdapterView<?> parent) {}
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    fieldMode = position;
                }
            });
            box.addView(mode, -1, ui.dp(48));
        }
        ui.gap(box, 12);
        if (!config.ready()) box.addView(ui.action("設定 Azure 連線", true, this::settings));
        else if (selected == null) box.addView(ui.action(error.isEmpty() ? "開始辨識" : "重新分類", true, this::classify));
        else box.addView(ui.action("讀取文字", true, this::readText));
        showError(content);
        if (!error.isEmpty()) {
            if (selected == null && config.ready()) {
                ui.gap(content, 10);
                content.addView(ui.action("手動選擇類型", false, this::selectType));
            }
            ui.gap(content, 10);
            content.addView(ui.action("檢查連線設定", false, this::settings));
        }
    }
    AzureReader createReader() {
        return new AzureReader(catalog, config);
    }

    private void classify() {
        AzureReader api = createReader();
        Bitmap previewImage = photo.preview;
        api.usage = photo.usage;
        run("Decisions 正在辨識影像類型…", () -> api.classify(previewImage), code -> {
            classified = code;
            selected = code;
            if (confirmType) preview();
            else readText();
        });
    }

    private void selectType() {
        List<CardCatalog.Card> types = new ArrayList<>(catalog.cards.values());
        String[] labels = new String[types.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = types.get(i).label;
        new AlertDialog.Builder(this).setTitle("選擇影像類型").setItems(labels, (dialog, i) -> {
            selected = types.get(i).code;
            error = "";
            preview();
        }).show();
    }

    private void readText() {
        CardCatalog.Card type = catalog.cards.get(selected);
        AzureReader api = createReader();
        byte[] bytes = photo.jpeg;
        api.usage = photo.usage;
        boolean fields = fieldMode == 1 || (fieldMode == 0 && type.autoFields);
        int token = sequence + 1;
        run("正在讀取" + type.label + "的文字…", () -> {
            AzureReader.Reading reading = api.read(type, bytes, fields, message -> runOnUiThread(() -> {
                if (sequence == token && busy && !isDestroyed()) {
                    status = message;
                    preview();
                }
            }));
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (LicenseFields.supports(type.code)) LicenseFields.readImage(this, reading, bytes);
            else IdCardDetails.readImage(this, reading, bytes);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            reading.usage = api.usage.snapshot();
            try {
                return history.add(reading);
            } catch (android.database.SQLException e) {
                throw new IOException("無法儲存辨識紀錄，請確認手機可用空間。", e);
            }
        }, id -> {
            photo = null;
            selected = null;
            classified = null;
            openReading(id);
        });
    }

    private void openReading(String id) {
        try {
            detail(history.get(id), id);
        } catch (JSONException | android.database.SQLException e) {
            error = "無法開啟這筆辨識紀錄。";
            home();
        }
    }

    private void detail(AzureReader.Reading reading, String id) {
        shell("detail", "讀取結果", this::home);
        new ResultLayout(ui, content).show(reading, () -> {
            barcodeRecord = id;
            startActivityForResult(new Intent(this, LiveScanActivity.class)
                    .putExtra(LiveScanActivity.BARCODE_ONLY, true), BARCODE);
        });
        ui.gap(content, 12);
        content.addView(new UsagePanel(ui).show(reading.usage));
        ui.gap(content, 16);
        content.addView(ui.action("繼續掃描", true, this::home));
    }

    private EditText field(LinearLayout box,String title,String value,String hint,boolean secret){
        ui.gap(box,12);TextView label=ui.text(title,14,INK,true);box.addView(label);
        EditText input=new EditText(this);input.setSingleLine(true);input.setTextSize(15);input.setTextColor(INK);
        input.setHintTextColor(MUTED);input.setHint(hint);input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setInputType(InputType.TYPE_CLASS_TEXT|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        input.setText(value);input.setContentDescription(title);input.setSelectAllOnFocus(false);box.addView(input,-1,-2);return input;
    }
    private void leaveSettings(){error="";if(photo==null)home();else preview();}
    private void rules() {
        ruleEditor = null;
        shell("rules", "辨識規則", this::home);
        note(content, ResultText.RULE);
        ui.gap(content, 8);
        note(content, "選擇一個類型，編輯分類特徵與要讀取的欄位。");
        ui.gap(content, 12);
        content.addView(ui.action("新增自訂類型", false, () -> editRule(null)));
        ui.gap(content, 12);
        LinearLayout list = ui.panel();
        content.addView(list);
        for (CardCatalog.Card card : catalog.cards.values()) {
            if (list.getChildCount() > 0) ui.line(list);
            String status = card.hasFields() ? card.fields.length() + " 個欄位" : "全文";
            ui.tile(list, card.label, status, card.autoFields, card.criteria, () -> editRule(card.code));
        }
    }

    private void editRule(String code) {
        shell("editRule", code == null ? "新增自訂類型" : "編輯辨識規則", () -> ruleEditor.back());
        ruleEditor = new RuleEditor(this, ui, content, catalog, code, this::rules);
    }

    private void settings(){
        shell("settings","Azure 連線設定",this::leaveSettings);
        note(content,"請填寫 Azure 公有雲的資源端點與金鑰。設定會加密儲存在這支手機。");
        ui.gap(content,14);LinearLayout ms=ui.panel();content.addView(ms);heading(ms,"影像分類");
        ui.gap(ms,6);note(ms,"選擇已部署的 Decisions 模型服務。");ui.gap(ms,8);
        Spinner provider=new Spinner(this);String[] modes={"Microsoft Decision 1.1","Azure OpenAI Decisions"};
        ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,modes);provider.setAdapter(a);provider.setSelection(config.openAI?1:0);
        provider.setContentDescription("Decisions 服務");ms.addView(provider,-1,ui.dp(48));
        EditText dep=field(ms,"模型部署名稱",config.model,"Foundry 中的部署名稱",false);
        EditText de=field(ms,"Decisions 資源端點",config.decisionRoot,"https://資源名稱.services.ai.azure.com",false);
        EditText dk=field(ms,"Decisions API 金鑰",config.decisionKey,"輸入資源金鑰",true);
        provider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> p){}
            public void onItemSelected(AdapterView<?> p,View v,int n,long id){
                String current=dep.getText().toString();
                if(current.isEmpty()||current.equals("Microsoft-Decision-1.1")||current.equals("gpt-6-luna"))
                    dep.setText(n==1?"gpt-6-luna":"Microsoft-Decision-1.1");
                de.setHint(n==1?"https://資源名稱.openai.azure.com":"https://資源名稱.services.ai.azure.com");
            }
        });
        ui.gap(content,12);LinearLayout cu=ui.panel();content.addView(cu);heading(cu,"Content Understanding");
        ui.gap(cu,6);note(cu,"全文使用內建 Read 或 Layout 分析器。若啟用自訂欄位，才需另設擷取模型與 text-embedding-3-large 的部署對應，並允許建立分析器。");
        EditText ce=field(cu,"Content Understanding 資源端點",config.cuRoot,"https://資源名稱.services.ai.azure.com",false);
        EditText ck=field(cu,"Content Understanding API 金鑰",config.cuKey,"同一資源可填相同金鑰",true);
        EditText cm=field(cu,"自訂欄位用文字生成模型（completion）",config.cuModel,"例如 gpt-5.2",false);
        ui.gap(cu,6);note(cu,"只讀全文時可保留原設定。擷取欄位時填入已在 Azure 對應的文字生成模型名稱，例如 gpt-5.2。text-embedding-3-large 專供向量化。");
        ui.gap(content,12);
        LinearLayout rates = ui.panel();
        content.addView(rates);
        LinearLayout prices = ui.col();
        rates.addView(ui.action("估算費用單價（選填）", false, () -> prices.setVisibility(prices.getVisibility() == View.GONE ? View.VISIBLE : View.GONE)));
        rates.addView(prices);
        prices.setVisibility(View.GONE);
        note(prices, "每百萬 token 的美元價格。留空使用 Global Standard 參考價，各筆紀錄會保留當時的估算。");
        EditText decisionPrice = field(prices, "Decisions 一般輸入", config.decisionRate, "Microsoft 0.042，Azure OpenAI 0.10", false);
        EditText completionIn = field(prices, "欄位模型輸入", config.completionInput, "依部署模型", false);
        EditText completionOut = field(prices, "欄位模型輸出", config.completionOutput, "依部署模型", false);
        EditText completionCache = field(prices, "欄位模型快取輸入", config.completionCached, "依部署模型", false);
        for (EditText price : new EditText[]{decisionPrice, completionIn, completionOut, completionCache})
            price.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        ui.gap(content,12);TextView msg=ui.text("",14,0xff863b24,false);msg.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);content.addView(msg);
        ui.gap(content,4);content.addView(ui.action("儲存連線設定",true,()->{
            Config c=new Config();c.openAI=provider.getSelectedItemPosition()==1;
            c.model=dep.getText().toString().trim();c.decisionRoot=de.getText().toString().trim();c.decisionKey=dk.getText().toString().trim();
            c.cuRoot=ce.getText().toString().trim();c.cuKey=ck.getText().toString().trim();c.cuModel=cm.getText().toString().trim();
            c.decisionRate=decisionPrice.getText().toString().trim(); c.completionInput=completionIn.getText().toString().trim();
            c.completionOutput=completionOut.getText().toString().trim(); c.completionCached=completionCache.getText().toString().trim();
            try{c.save(this);config=c;leaveSettings();Toast.makeText(this,"已儲存連線設定",Toast.LENGTH_SHORT).show();}
            catch(Exception ex){msg.setText(ex instanceof IllegalArgumentException?ex.getMessage():"無法使用 Android Keystore 儲存設定。");}
        }));
        if(config.ready()){
            ui.gap(content,10);content.addView(ui.action("移除手機上的連線設定",false,()->
                new AlertDialog.Builder(this).setTitle("移除 Azure 連線設定？").setNegativeButton("取消",null)
                .setPositiveButton("移除",(d,w)->{Config.forget(this);config=new Config();classified=null;selected=null;leaveSettings();}).show()));
        }
    }
    @Override public void onBackPressed(){
        switch(screen){
            case "rules":home();return;
            case "editRule":ruleEditor.back();return;
            case "settings":leaveSettings();return;
            case "detail":home();return;
            case "preview":abandon();return;
            case "transfer":return;
            default:finish();return;
        }
    }
    @Override protected void onDestroy(){
        sequence++;
        if (running != null) running.cancel(true);
        worker.execute(history::close);
        worker.shutdown();
        if(isFinishing())clearCapture(capture);
        super.onDestroy();
    }
}
