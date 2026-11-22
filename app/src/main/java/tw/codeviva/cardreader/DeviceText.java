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
import java.util.concurrent.ExecutionException;

final class DeviceText implements AutoCloseable {
    static final String UNAVAILABLE = "手機上的 ML Kit 中文辨識尚未就緒。請確認已連網並啟用 Google Play 服務，再重新嘗試。";
    static final String DOWNLOADING = "正在準備 ML Kit 中文辨識模型，請保持網路連線。模型就緒後即可辨識。";
    private final ModuleInstallClient installer;
    private boolean available, requested;
    private long nextCheck;
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
