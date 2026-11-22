package tw.codeviva.cardreader;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import com.google.mlkit.vision.text.Text;
import java.io.*;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("deprecation")
public final class LiveScanActivity extends Activity implements TextureView.SurfaceTextureListener {
    static final int COVERAGE_PERCENT = 60;
    static final float MIN_COVERAGE = COVERAGE_PERCENT / 100f;
    static boolean coversEnough(float coverage) { return coverage >= MIN_COVERAGE; }
    private static boolean fitsWindow(DocumentFrame frame) {
        return !frame.clipped && frame.coverage <= .955f && coversEnough(frame.coverage);
    }
    static final String BARCODE_ONLY = "barcode_only", BARCODE_VALUE = "barcode_value";
    static final String BARCODE_GUIDE = "對準同一張身分證背面的左下角條碼";
    private boolean barcodeOnly;
    private String previousBarcode = "";
    static final String REVIEW_CROP = "review_crop";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ReaderScreen ui;
    private FrameLayout stage, window;
    private TextureView preview;
    private Outline outline;
    private TextView hint;
    private Button shape, permissionSettings;
    private CheckBox reviewCrop;
    private boolean vertical, resumed, finished;
    private int rotation, generation;
    private Camera camera;
    private Camera.Size size;
    private boolean singleAutofocus;
    private long lastFocus;
    private DeviceText reader; // Accessed only on the analysis worker.
    private DocumentFrame previous;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        barcodeOnly = getIntent().getBooleanExtra(BARCODE_ONLY, false);
        vertical = saved != null && saved.getBoolean("vertical");
        ui = new ReaderScreen(this);
        LinearLayout root = ui.col();
        root.setBackgroundColor(ReaderScreen.BG);
        if (android.os.Build.VERSION.SDK_INT >= 30) root.setOnApplyWindowInsetsListener((v, i) -> {
            android.graphics.Insets b = i.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return i;
        });
        LinearLayout bar = ui.row();
        ui.pad(bar, 12, 6);
        bar.addView(ui.action("‹ 返回", false, this::finish));
        ui.gap(bar, 12);
        ui.weight(bar, ui.text(barcodeOnly ? "補掃條碼" : "即時辨識", 19, ReaderScreen.INK, true));
        shape = ui.action("", false, () -> {
            vertical = !vertical;
            shapeLabel();
            stopCamera();
            layoutWindow();
            startCamera();
        });
        shapeLabel();
        if (!barcodeOnly) bar.addView(shape);
        root.addView(bar);
        stage = new FrameLayout(this);
        stage.setBackgroundColor(0xff172c29);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        window = new FrameLayout(this);
        window.setClipChildren(true);
        stage.addView(window, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        preview = new TextureView(this);
        window.addView(preview);
        preview.setSurfaceTextureListener(this);
        outline = new Outline(this);
        window.addView(outline, -1, -1);
        stage.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (r-l != or-ol || b-t != ob-ot) layoutWindow();
        });
        LinearLayout footer = ui.col();
        ui.pad(footer, 14, 10);
        root.addView(footer);
        hint = ui.text(barcodeOnly ? BARCODE_GUIDE : "請將文件四角放入取景框", 26, ReaderScreen.INK, true);
        hint.setTypeface(Typeface.DEFAULT_BOLD);
        hint.setGravity(Gravity.CENTER);
        hint.setBackground(ui.bg(ReaderScreen.PALE, 14, false));
        hint.setMinimumHeight(ui.dp(60));
        ui.pad(hint, 12, 10);
        hint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        footer.addView(hint);
        ui.gap(footer, 6);
        footer.addView(ui.text(barcodeOnly ? "將條紋水平置中，左右留白。手機解碼成功後會更新這筆紀錄。" : "占取景框 " + COVERAGE_PERCENT + "% 以上、文字清晰且穩定時，自動裁切並送往 Azure 辨識。", 16, ReaderScreen.MUTED, false));
        reviewCrop = new CheckBox(this);
        reviewCrop.setText("送出前先預覽裁切影像");
        reviewCrop.setChecked(saved != null && saved.getBoolean(REVIEW_CROP));
        if (!barcodeOnly) footer.addView(reviewCrop);
        permissionSettings = ui.action("開啟相機權限設定", false, () -> startActivity(new android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName()))));
        permissionSettings.setVisibility(View.GONE);
        footer.addView(permissionSettings);
        setContentView(root);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
    }

    private void shapeLabel() {
        shape.setText(vertical ? "改用橫框" : "改用直框");
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("vertical", vertical);
        state.putBoolean(REVIEW_CROP, reviewCrop.isChecked());
    }

    private float aspect() { return barcodeOnly ? 3.5f : vertical ? .7071f : 1.586f; }

    private void layoutWindow() {
        int w = Math.round(Math.min(stage.getWidth(), stage.getHeight() * aspect()));
        int h = Math.round(w / aspect());
        if (w < 2 || h < 2) return;
        window.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
        if (size == null) return;
        float raw = rotation % 180 == 0 ? (float) size.width / size.height : (float) size.height / size.width;
        int pw = Math.max(w, Math.round(h * raw)), ph = Math.max(h, Math.round(w / raw));
        preview.setLayoutParams(new FrameLayout.LayoutParams(pw, ph, Gravity.CENTER));
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != 1) return;
        if (grants.length == 0 || grants[0] != PackageManager.PERMISSION_GRANTED) {
            permissionSettings.setVisibility(View.VISIBLE);
            message("請允許相機權限，或返回首頁從相簿選取。");
            return;
        }
        startCamera();
    }

    @Override protected void onResume() { super.onResume(); resumed = true; startCamera(); }
    @Override protected void onPause() { resumed = false; stopCamera(); super.onPause(); }
    @Override protected void onDestroy() {
        worker.execute(() -> { if (reader != null) reader.close(); });
        worker.shutdown();
        super.onDestroy();
    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { startCamera(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { stopCamera(); return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}

    private void startCamera() {
        if (!resumed || finished || camera != null || !preview.isAvailable() ||
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        permissionSettings.setVisibility(View.GONE);
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            int id = 0;
            for (; id < Camera.getNumberOfCameras(); id++) {
                Camera.getCameraInfo(id, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) break;
            }
            if (id == Camera.getNumberOfCameras()) throw new IOException();
            camera = Camera.open(id);
            int display = getWindowManager().getDefaultDisplay().getRotation() * 90;
            rotation = (info.orientation - display + 360) % 360;
            Camera.Parameters params = camera.getParameters();
            size = chooseSize(params.getSupportedPreviewSizes());
            params.setPreviewSize(size.width, size.height);
            params.setPreviewFormat(ImageFormat.NV21);
            List<String> focus = params.getSupportedFocusModes();
            if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            else if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            singleAutofocus = Camera.Parameters.FOCUS_MODE_AUTO.equals(params.getFocusMode());
            camera.setParameters(params);
            camera.setDisplayOrientation(rotation);
            layoutWindow();
            camera.setPreviewTexture(preview.getSurfaceTexture());
            camera.setErrorCallback((err, c) -> {
                if (c == camera) { stopCamera(); message("相機連線中斷，請返回後重試。"); }
            });
            camera.startPreview();
            lastFocus = 0;
            focusIfNeeded();
            message(barcodeOnly ? BARCODE_GUIDE : "請將文件放在取景框中央，露出四角");
            next(generation, 180);
        } catch (RuntimeException | IOException e) {
            stopCamera();
            message("無法開啟後置相機，請返回後重試或從相簿選取。");
        }
    }

    private void focusIfNeeded() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!singleAutofocus || camera == null || now - lastFocus < 2000) return;
        lastFocus = now;
        try { camera.autoFocus((ok, cam) -> {}); } catch (RuntimeException ignored) {}
    }

    private Camera.Size chooseSize(List<Camera.Size> sizes) {
        // Prioritize the number of pixels inside the actual on-screen crop.
        return Collections.max(sizes, (a,b) -> Double.compare(sizeScore(a), sizeScore(b)));
    }

    private double sizeScore(Camera.Size s) {
        int longSide = Math.max(s.width, s.height);
        if (longSide > 1920) return -longSide;
        float target = rotation % 180 == 0 ? aspect() : 1 / aspect();
        double w = Math.min(s.width, s.height * target);
        return w * w / target;
    }

    private void stopCamera() {
        generation++;
        main.removeCallbacksAndMessages(null);
        previous = null;
        previousBarcode = "";
        if (outline != null) showFrame(null);
        if (camera != null) {
            Camera old = camera;
            camera = null;
            old.setPreviewCallback(null);
            try { old.stopPreview(); } catch (RuntimeException ignored) {}
            old.release();
        }
    }

    private void next(int token, long delay) {
        main.postDelayed(() -> {
            if (token != generation || camera == null || finished) return;
            camera.setOneShotPreviewCallback((data, c) -> {
                if (token != generation || c != camera || data == null) { next(token, 300); return; }
                int w = size.width, h = size.height, turn = rotation;
                float ratio = aspect();
                worker.execute(() -> analyze(data, w, h, turn, ratio, token));
            });
        }, delay);
    }

    // The JPEG crop and the visible TextureView use the same center-crop transform.
    static Bitmap viewfinder(byte[] data, int w, int h, int turn, float ratio) throws IOException {
        float sensorRatio = turn % 180 == 0 ? ratio : 1f / ratio;
        int cw = Math.round(Math.min(w, h * sensorRatio)) & ~1;
        int ch = Math.round(Math.min(h, w / sensorRatio)) & ~1;
        int x = ((w - cw) / 2) & ~1, y = ((h - ch) / 2) & ~1;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!new YuvImage(data, ImageFormat.NV21, w, h, null)
            .compressToJpeg(new Rect(x, y, x + cw, y + ch), 94, bytes)) throw new IOException();
        byte[] jpeg = bytes.toByteArray();
        Bitmap image = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (image == null) throw new IOException();
        if (turn == 0) return image;
        Matrix m = new Matrix();
        m.setRotate(turn);
        Bitmap rotated = Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(), m, true);
        image.recycle();
        return rotated;
    }

    private void analyze(byte[] data, int w, int h, int turn, float ratio, int token) {
        Bitmap image = null, cropped = null;
        try {
            image = viewfinder(data, w, h, turn, ratio);
            if (barcodeOnly) {
                reportBarcode(token, IdCardDetails.decodeBars(image));
                return;
            }
            if (reader == null) reader = new DeviceText(this);
            Text scene = reader.read(image);
            Rect words = DeviceText.documentTextBounds(scene, image);
            DocumentFrame doc = DocumentFrame.detect(image, words);
            if (doc == null) {
                String guidance = "請將文件置中，讓四角與背景分明";
                Rect bounds = new Rect();
                for (Text.TextBlock block : scene.getTextBlocks()) {
                    Rect box = block.getBoundingBox();
                    if (box != null) bounds.union(box);
                }
                if (bounds.isEmpty() || !DeviceText.legible(scene, image, null)) guidance = "請拿穩手機並對焦，讓文件四角入框";
                else if (bounds.left < image.getWidth() * .02 || bounds.right > image.getWidth() * .98 ||
                         bounds.top < image.getHeight() * .02 || bounds.bottom > image.getHeight() * .98)
                    guidance = "請移遠一點，讓文件四角完整入框";
                else if (bounds.width() < image.getWidth() * .55 && bounds.height() < image.getHeight() * .55)
                    guidance = "請靠近一點，讓文件填滿取景框";
                report(token, null, guidance, false, null);
                return;
            }
            if (doc.clipped || doc.coverage > .955f) {
                report(token, doc, "請移遠一點，讓四角與框邊留有空隙", false, null);
                return;
            }
            if (!coversEnough(doc.coverage)) {
                report(token, doc, "請靠近一點，目前占 " + (int) (doc.coverage * 100f) + "%", false, null);
                return;
            }
            if (!DeviceText.legible(scene, image, doc)) {
                report(token, doc, "文字模糊，請拿穩手機，等待對焦", false, null);
                return;
            }
            cropped = doc.crop(image);
            byte[] jpeg = CardPhoto.encoded(cropped, Bitmap.CompressFormat.JPEG, 95);
            report(token, doc, "請拿穩，正在確認穩定度", true, jpeg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            String detail = e.getMessage();
            if (!DeviceText.UNAVAILABLE.equals(detail) && !DeviceText.DOWNLOADING.equals(detail))
                detail = "無法分析畫面，請重新對準文件";
            report(token, null, detail, false, null);
        } catch (RuntimeException e) {
            report(token, null, "無法分析畫面，請確認 Google Play 服務可用後重試", false, null);
        } finally {
            if (cropped != null) cropped.recycle();
            if (image != null) image.recycle();
        }
    }

    private void reportBarcode(int token, String value) {
        main.post(() -> {
            if (token != generation || !resumed || finished) return;
            if (!value.isEmpty() && value.equals(previousBarcode)) {
                finished = true;
                setResult(RESULT_OK, new android.content.Intent().putExtra(BARCODE_VALUE, value));
                finish();
                return;
            }
            previousBarcode = value;
            message(value.isEmpty() ? "請拿穩手機，讓完整條紋與兩側留白入框" : "已讀到條碼，請保持穩定");
            focusIfNeeded();
            next(token, 180);
        });
    }

    private void report(int token, DocumentFrame doc, String status, boolean ready, byte[] jpeg) {
        main.post(() -> {
            if (token != generation || !resumed || finished) return;
            showFrame(doc);
            if (barcodeOnly) previousBarcode = "";
            if (ready && doc.closeTo(previous)) {
                try (OutputStream out = getContentResolver().openOutputStream(getIntent().getData(), "wt")) {
                    if (out == null) throw new IOException();
                    out.write(jpeg);
                } catch (RuntimeException | IOException e) {
                    message("無法儲存裁切影像，請返回後重試。");
                    return;
                }
                finished = true;
                setResult(RESULT_OK, new android.content.Intent().putExtra(REVIEW_CROP, reviewCrop.isChecked()));
                finish();
                return;
            }
            previous = ready ? doc : null;
            if (!ready) focusIfNeeded();
            message(status);
            next(token, 240);
        });
    }

    private void showFrame(DocumentFrame frame) {
        outline.show(frame);
        int background = frame == null ? ReaderScreen.PALE : fitsWindow(frame) ? 0xffd6f2e5 : 0xffffebc7;
        hint.getBackground().setTint(background);
    }

    private void message(String s) { if (!s.contentEquals(hint.getText())) hint.setText(s); }

    static final class Outline extends View {
        static final int AMBER = 0xffffc043, MINT = 0xff4ce2a4;
        private static final float INSET = (1f - (float)Math.sqrt(MIN_COVERAGE)) / 2;
        private static final float[] GUIDE = {INSET, INSET, 1-INSET, INSET, 1-INSET, 1-INSET, INSET, 1-INSET};
        private final float density = getResources().getDisplayMetrics().density;
        private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path border = new Path(), brackets = new Path(), outside = new Path();
        private DocumentFrame frame;

        Outline(Context context) {
            super(context);
            pen.setStrokeCap(Paint.Cap.ROUND);
            pen.setStrokeJoin(Paint.Join.ROUND);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void show(DocumentFrame frame) {
            this.frame = frame;
            invalidate();
        }

        private void stroke(Canvas canvas, Path path, int color, float dp) {
            pen.setStyle(Paint.Style.STROKE);
            pen.setStrokeWidth(dp * density);
            pen.setColor(color);
            canvas.drawPath(path, pen);
        }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            float[] points = frame == null ? GUIDE : frame.corners;
            border.rewind();
            brackets.rewind();
            border.moveTo((points[6] + points[0]) * w / 2, (points[7] + points[1]) * h / 2);
            for (int i = 0; i < 8; i += 2) {
                int previous = (i + 6) % 8, next = (i + 2) % 8;
                float x = points[i]*w, y = points[i+1]*h;
                float ax = points[previous]*w - x, ay = points[previous+1]*h - y;
                float bx = points[next]*w - x, by = points[next+1]*h - y;
                float a = (float)Math.hypot(ax,ay), b = (float)Math.hypot(bx,by);
                if (a < 1 || b < 1) return;
                ax /= a; ay /= a; bx /= b; by /= b;
                float arm = Math.min(19*density, Math.min(a,b)*.22f);
                float bend = Math.min(5*density, arm*.5f);
                float startX = x+ax*bend, startY = y+ay*bend;
                float endX = x+bx*bend, endY = y+by*bend;
                border.lineTo(startX, startY);
                border.quadTo(x, y, endX, endY);
                brackets.moveTo(x+ax*arm, y+ay*arm);
                brackets.lineTo(startX, startY);
                brackets.quadTo(x, y, endX, endY);
                brackets.lineTo(x+bx*arm, y+by*arm);
            }
            border.close();

            outside.rewind();
            outside.setFillType(Path.FillType.EVEN_ODD);
            outside.addRect(0, 0, w, h, Path.Direction.CW);
            outside.addPath(border);
            pen.setStyle(Paint.Style.FILL);
            pen.setColor(frame == null ? 0x200b2122 : 0x410b2122);
            canvas.drawPath(outside, pen);

            int color = 0xffedf6f1;
            if (frame != null) {
                color = fitsWindow(frame) ? MINT : AMBER;
                stroke(canvas, border, 0xce21332f, 4.4f);
                stroke(canvas, border, color, 2.6f);
            }
            stroke(canvas, brackets, frame == null ? 0x95223532 : 0xce21332f, frame == null ? 4f : 5.5f);
            stroke(canvas, brackets, color, frame == null ? 2.6f : 3.7f);
        }
    }
}
