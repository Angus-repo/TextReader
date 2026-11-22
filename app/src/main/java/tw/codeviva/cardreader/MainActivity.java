package tw.codeviva.cardreader;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static tw.codeviva.cardreader.ReaderScreen.*;

public class MainActivity extends Activity {
    private static final int CAMERA=31,GALLERY=32;
    private ReaderScreen ui;
    private LinearLayout content;
    private ScrollView scroll;
    private Config config;
    private CardCatalog catalog;
    private final List<AzureReader.Reading> results = new ArrayList<>();
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
        try {catalog=new CardCatalog(this);}catch(Exception e){throw new IllegalStateException(e);}
        try {config=Config.load(this);}catch(Exception e){config=new Config();error="無法解密連線設定，請重新設定 Azure 連線。";}
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
        ui.weight(actions,ui.action("拍照",false,()->source(true)));ui.gap(actions,8);
        ui.weight(actions,ui.action("從相簿選取",false,()->source(false)));
        showError(content);
        if(!config.ready()){ui.gap(content,12);Button setup=ui.action("設定 Azure 連線以開始辨識",false,this::settings);content.addView(setup);}
        ui.gap(content,24);LinearLayout label=ui.row();content.addView(label);ui.weight(label,ui.text("本次讀取",21,INK,true));
        label.addView(ui.text(results.size() + " 筆", 14, GREEN, true));
        ui.gap(content, 12);
        LinearLayout list = ui.panel();
        content.addView(list);
        if (results.isEmpty()) {
            note(list, "可讀取證件、收據、表單、名片及其他含文字的影像。");
        } else {
            for (int i = results.size() - 1; i >= 0; i--) {
                final int index = i;
                AzureReader.Reading reading = results.get(i);
                if (i < results.size() - 1) ui.line(list);
                ui.tile(list, reading.type.label, "第 " + (i + 1) + " 筆", true, null, () -> detail(index));
            }
        }
        ui.gap(content, 14);
        note(content, "本次結果會在結束 App 後清除。");
        if(!results.isEmpty()){
            ui.gap(content,12);content.addView(ui.action("清除本次資料",false,()->confirmClear()));
        }
    }
    private void confirmClear(){
        new AlertDialog.Builder(this).setTitle("清除本次讀取？").setMessage("本次所有影像的辨識結果都會清除。")
            .setNegativeButton("取消",null).setPositiveButton("清除",(d,w)->{results.clear();error="";home();}).show();
    }
    private void source(boolean camera){
        error="";
        try {
            if(camera){
                File dir=CaptureProvider.dir(this);if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException();
                String name=UUID.randomUUID()+".jpg";capture=new Uri.Builder().scheme("content").authority(CaptureProvider.AUTHORITY).appendPath(name).build();
                if(!CaptureProvider.file(this,capture).createNewFile())throw new IOException();
                Intent i=new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                i.putExtra(MediaStore.EXTRA_OUTPUT,capture);
                i.setClipData(ClipData.newRawUri("掃描照片",capture));i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
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
        super.onActivityResult(request,result,data);if(request!=CAMERA&&request!=GALLERY)return;
        final Uri temp=request==CAMERA?capture:null;
        Uri uri=request==CAMERA?temp:data==null?null:data.getData();
        if(result!=RESULT_OK||uri==null){clearCapture(temp);return;}
        selected=null;classified=null;photo=null;error="";fieldMode=0;
        run("正在載入影像…",()->{
            try{return CardPhoto.load(this,uri);}finally{if(temp!=null)runOnUiThread(()->clearCapture(temp));}
        },p->{photo=p;preview();});
    }
    @Override protected void onSaveInstanceState(Bundle state){
        super.onSaveInstanceState(state);
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
        if (classified == null) {
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
            note(box, "Decisions 判定為" + catalog.cards.get(classified).label + "。");
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
        else if (classified == null) box.addView(ui.action("開始辨識", true, this::classify));
        else box.addView(ui.action("讀取文字", true, this::readText));
        showError(content);
        if(!error.isEmpty()){ui.gap(content,10);content.addView(ui.action("檢查連線設定",false,this::settings));}
    }
    AzureReader createReader() {
        return new AzureReader(catalog, config);
    }

    private void classify() {
        AzureReader api = createReader();
        Bitmap previewImage = photo.preview;
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
        boolean fields = fieldMode == 1 || (fieldMode == 0 && type.autoFields);
        int token = sequence + 1;
        run("已辨識為" + type.label + "，正在讀取文字…", () -> api.read(type, bytes, fields,
            message -> runOnUiThread(() -> {
                if (sequence == token && busy && !isDestroyed()) {
                    status = message;
                    preview();
                }
            })), reading -> {
                results.add(reading);
                photo = null;
                selected = null;
                classified = null;
                detail(results.size() - 1);
            });
    }

    private void detail(int index) {
        AzureReader.Reading reading = results.get(index);
        shell("detail", "讀取結果", this::home);
        new ResultLayout(ui, content).show(reading);
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
        EditText cm=field(cu,"自訂欄位用基礎模型名稱",config.cuModel,"例如 gpt-5.2",false);
        ui.gap(cu,6);note(cu,"填 Azure 預設模型對應左側的模型名稱。");
        ui.gap(content,12);TextView msg=ui.text("",14,0xff863b24,false);msg.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);content.addView(msg);
        ui.gap(content,4);content.addView(ui.action("儲存連線設定",true,()->{
            Config c=new Config();c.openAI=provider.getSelectedItemPosition()==1;
            c.model=dep.getText().toString().trim();c.decisionRoot=de.getText().toString().trim();c.decisionKey=dk.getText().toString().trim();
            c.cuRoot=ce.getText().toString().trim();c.cuKey=ck.getText().toString().trim();c.cuModel=cm.getText().toString().trim();
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
            default:if(results.isEmpty()){finish();return;}
                new AlertDialog.Builder(this).setTitle("結束本次讀取？").setMessage("離開後會清除本次辨識結果。")
                    .setNegativeButton("取消",null).setPositiveButton("離開",(d,w)->{results.clear();finish();}).show();
        }
    }
    @Override protected void onDestroy(){
        sequence++;worker.shutdownNow();if(isFinishing())clearCapture(capture);
        super.onDestroy();
    }
}
