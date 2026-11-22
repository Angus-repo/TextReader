package tw.codeviva.cardreader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.ReaderException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.oned.MultiFormatOneDReader;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class IdCardDetails {
    static final String BARCODE_LABEL = "左下角條碼";
    private static final String SPACE = "[\\s\\p{Z}]*";
    private static final Pattern SEX_LABEL = Pattern.compile("性" + SPACE + "[別别]");
    private static final Pattern LABELED_SEX = Pattern.compile(SEX_LABEL.pattern() + SPACE + "[:：]?" + SPACE
            + "([男女])(?:性)?(?=$|[\\s\\p{Z}\\p{P}0-9]|統一編號|统一编号|出生|發證|发证|姓名|[A-Z][0-9])");
    private static final Pattern SINGLE_SEX = Pattern.compile("(?m)^[\\p{Z}\\t]*([男女])(?:性)?[\\p{Z}\\t]*\\r?$");
    private static final Pattern SEX_ABOVE_NUMBER = Pattern.compile(
            "(?m)^[\\p{Z}\\t]*([男女])[\\p{Z}\\t]*\\r?\\n" + SPACE + "[統统]" + SPACE + "一" + SPACE + "[編编]" + SPACE + "[號号]");
    private static final Map<DecodeHintType, ?> BARCODE_HINTS = Collections.singletonMap(DecodeHintType.TRY_HARDER, true);

    static String printedSex(String text) {
        String value = uniqueValue(LABELED_SEX.matcher(text));
        if (value != null) return value;
        // OCR can report the label and its one-character value in separate blocks.
        value = SEX_LABEL.matcher(text).find() ? uniqueValue(SINGLE_SEX.matcher(text))
                : uniqueValue(SEX_ABOVE_NUMBER.matcher(text));
        return value == null ? "" : value;
    }

    private static String uniqueValue(Matcher values) {
        String found = null;
        while (values.find()) {
            String value = values.group(1);
            if (found != null && !found.equals(value)) return "";
            found = value;
        }
        return found;
    }

    static String sexValue(String field) {
        String value = field.trim();
        if (value.equals("男") || value.equals("女")) return value;
        if (value.equals("男性") || value.equals("女性")) return value.substring(0, 1);
        return printedSex(value);
    }

    static void readImage(Context context, AzureReader.Reading reading, byte[] jpeg) throws InterruptedException, JSONException {
        boolean back = reading.type.code.equals("id_back");
        if (!back) {
            if (!reading.type.code.equals("id_front") || reading.type.fieldLabel("Sex") == null) return;
            JSONObject sex = reading.fields == null ? null : reading.fields.optJSONObject("Sex");
            if ((sex != null && !sexValue(sex.optString("valueString")).isEmpty()) || !printedSex(reading.text).isEmpty()) return;
        }
        Bitmap source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (source == null) {
            if (back) reading.local.put("Barcode", "");
            return;
        }
        Bitmap upright = source;
        try {
            int turn = 90 * (int)Math.round(reading.pageAngle / 90d);
            if (turn != 0) {
                Matrix rotation = new Matrix();
                rotation.setRotate(-turn);
                upright = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), rotation, true);
            }
            if (back) {
                reading.local.put("Barcode", barcode(upright));
            } else {
                String sex = readSexRegion(context, upright);
                if (!sex.isEmpty()) reading.local.put("Sex", sex);
            }
        } finally {
            if (upright != source) upright.recycle();
            source.recycle();
        }
    }

    private static String readSexRegion(Context context, Bitmap card) throws InterruptedException {
        // The printed sex is under the portrait, above the ID number.
        int x = (int)(card.getWidth() * .55f), y = (int)(card.getHeight() * .52f);
        Bitmap region = Bitmap.createBitmap(card, x, y, card.getWidth() - x, card.getHeight() - y);
        try (DeviceText reader = new DeviceText(context)) {
            String text = reader.read(region).getText();
            String labeled = printedSex(text);
            if (!labeled.isEmpty() || SEX_LABEL.matcher(text).find()) return labeled;
            String single = uniqueValue(SINGLE_SEX.matcher(text));
            return single == null ? "" : single;
        } catch (IOException | RuntimeException unavailable) {
            return ""; // Keep the original Azure reading when on-device OCR is unavailable.
        } finally {
            if (region != card) region.recycle();
        }
    }

    static String barcode(Bitmap card) {
        int w = Math.round(card.getWidth() * .77f), top = (int)(card.getHeight() * .45f);
        int h = card.getHeight() - top;
        if (w < 30 || h < 6) return "";
        int[] pixels = new int[w * h];
        card.getPixels(pixels, 0, w, 0, top, w, h);
        RGBLuminanceSource source = new RGBLuminanceSource(w, h, pixels);
        MultiFormatOneDReader decoder = new MultiFormatOneDReader(BARCODE_HINTS);
        try {
            return decoder.decode(new BinaryBitmap(new HybridBinarizer(source)), BARCODE_HINTS).getText();
        } catch (ReaderException noLocalThreshold) {
            try {
                return decoder.decode(new BinaryBitmap(new GlobalHistogramBinarizer(source)), BARCODE_HINTS).getText();
            } catch (ReaderException unreadable) {
                return "";
            }
        }
    }
}
