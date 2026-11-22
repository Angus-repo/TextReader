package tw.codeviva.cardreader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.common.moduleinstall.ModuleInstall;
import com.google.android.gms.common.moduleinstall.ModuleInstallClient;
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

final class DeviceText implements AutoCloseable {
    static final String RULE = "共用規則：即時辨識與相簿選圖都會檢查「樣本」。讀到這兩個字就跳過整張影像，不新增辨識紀錄。分行或中間有空格也適用。";
    static final String SKIPPED = "已跳過這張影像：偵測到「樣本」，未新增辨識紀錄。";
    static final String UNAVAILABLE = "手機上的 ML Kit 中文辨識尚未就緒。請確認已連網並啟用 Google Play 服務，再重新嘗試。";
    static final String DOWNLOADING = "正在準備 ML Kit 中文辨識模型，請保持網路連線。模型就緒後即可辨識。";
    private final ModuleInstallClient installer;
    private boolean available, requested;
    private long nextCheck;
    private static final Pattern SAMPLE = Pattern.compile("[樣样][\\s\\p{Z}\\u200B\\uFEFF]*本");
    private final TextRecognizer recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());

    DeviceText(Context context) { installer = ModuleInstall.getClient(context); }

    private void ensureModel() throws IOException, InterruptedException {
        if (available) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now < nextCheck) throw new IOException(DOWNLOADING);
        try {
            available = Tasks.await(installer.areModulesAvailable(recognizer)).areModulesAvailable();
        } catch (ExecutionException e) {
            // Older Play services can still resolve the model on the first OCR request.
            return;
        }
        if (available) return;
        if (!requested) {
            try {
                Tasks.await(installer.installModules(ModuleInstallRequest.newBuilder().addApi(recognizer).build()));
                requested = true;
            } catch (ExecutionException e) {
                throw new IOException(UNAVAILABLE, e.getCause());
            }
        }
        nextCheck = now + 1800;
        throw new IOException(DOWNLOADING);
    }

    static boolean sample(String text) { return text != null && SAMPLE.matcher(text).find(); }

    static boolean sample(Text text) {
        if (sample(text.getText())) return true;
        List<Rect> starts = new ArrayList<>(), ends = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                for (Text.Element word : line.getElements()) {
                    Rect box = word.getBoundingBox();
                    if (box == null) continue;
                    String value = word.getText().trim();
                    if ("樣".equals(value) || "样".equals(value)) starts.add(box);
                    if ("本".equals(value)) ends.add(box);
                }
            }
        }
        // A watermark can be split into two blocks, with unrelated card text
        // between them in OCR reading order. Compare their physical positions.
        for (Rect first : starts) for (Rect second : ends)
            if (alignedGlyphs(first, second)) return true;
        return false;
    }

    static boolean alignedGlyphs(Rect first, Rect second) {
        int em = Math.max(first.height(), second.height());
        if (em == 0 || Math.min(first.height(), second.height()) < em * .6f) return false;
        float dx = second.exactCenterX() - first.exactCenterX();
        float dy = second.exactCenterY() - first.exactCenterY();
        return (Math.abs(dy) < em * .45f && dx > 0 && dx < em * 5) ||
               (Math.abs(dx) < em * .45f && dy > 0 && dy < em * 5);
    }

    Text read(Bitmap bitmap) throws IOException, InterruptedException {
        ensureModel();
        try {
            return Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)));
        } catch (ExecutionException e) {
            throw new IOException(UNAVAILABLE, e.getCause());
        }
    }

    static Rect documentTextBounds(Text text, Bitmap image) {
        Rect words = new Rect();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                if (box == null) continue;
                float x = box.exactCenterX() / image.getWidth(), y = box.exactCenterY() / image.getHeight();
                if (x > .12f && x < .88f && y > .12f && y < .88f) words.union(box);
            }
        }
        return words;
    }

    // Evaluate sharpness on ML Kit's text lines, so a sharp table or background
    // cannot compensate for blurred print. Units are luminance on a 0–255 scale.
    static boolean legible(Text text, Bitmap image, DocumentFrame frame) {
        int tested = 0, clear = 0;
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                if (box == null || line.getText().trim().length() < 2) continue;
                if (frame != null && !frame.contains(box.exactCenterX() / image.getWidth(),
                        box.exactCenterY() / image.getHeight())) continue;
                Rect r = new Rect(box);
                if (!r.intersect(1, 1, image.getWidth() - 1, image.getHeight() - 1) ||
                    r.width() < 8 || r.height() < 9) continue;
                int stride = r.width() + 2;
                int[] pixels = new int[stride * (r.height() + 2)];
                image.getPixels(pixels, 0, stride, r.left - 1, r.top - 1, stride, r.height() + 2);
                for (int i = 0; i < pixels.length; i++) {
                    int rgb = pixels[i];
                    pixels[i] = (Color.red(rgb) + 2 * Color.green(rgb) + Color.blue(rgb)) / 4;
                }
                tested++;
                if (sharp(pixels, stride, r.height() + 2)) clear++;
            }
        }
        return clear > 0 && clear * 2 >= tested;
    }

    static boolean sharp(int[] gray, int w, int h) {
        double sum = 0, squares = 0;
        int count = 0;
        for (int y = 1; y < h - 1; y += 2) {
            for (int x = 1; x < w - 1; x += 2) {
                int i = y * w + x;
                int lap = gray[i-1] + gray[i+1] + gray[i-w] + gray[i+w] - 4 * gray[i];
                sum += lap;
                squares += (double) lap * lap;
                count++;
            }
        }
        return count > 0 && squares / count - Math.pow(sum / count, 2) >= 95;
    }

    @Override public void close() { recognizer.close(); }
}
