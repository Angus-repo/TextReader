package tw.codeviva.cardreader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.ReaderException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitArray;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.oned.MultiFormatOneDReader;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.text.Normalizer;
import java.util.Collections;
import java.util.Locale;
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

    static final String ID_LABEL = "身分證字號";
    private static final Pattern ID = Pattern.compile("(?<![A-Za-z0-9])([A-Za-z](?:" + SPACE + "[0-9]){9})(?![A-Za-z0-9]|[\\p{Z}\\t]+[0-9])");
    private static final Pattern SERIAL = Pattern.compile("(?m)^[\\p{Z}\\t]*([0-9](?:[\\p{Z}\\t]*[0-9]){9})[\\p{Z}\\t]*\\r?$");
    private static final Pattern SERVICE_LABEL = Pattern.compile("役" + SPACE + "[別别]");
    private static final Pattern OTHER_BACK_FIELD = Pattern.compile("出生" + SPACE + "地|住" + SPACE + "址|戶籍|配偶|父|母|統一編號|流水號");
    private static final Pattern SERVICE_WORD = Pattern.compile("[\\p{L}][\\p{L}0-9（）()、·-]{0,23}");

    static String idNumber(String text) {
        return uniqueNumber(ID, text);
    }

    static String barcodeId(String barcode) {
        String compact = Normalizer.normalize(barcode, Normalizer.Form.NFKC).replaceAll("[\\s\\p{Z}]+", "").toUpperCase(Locale.ROOT);
        return compact.matches("[A-Z][0-9]{9}") ? compact : "";
    }

    static String serial(String text) {
        return uniqueNumber(SERIAL, text);
    }

    private static String uniqueNumber(Pattern pattern, String text) {
        Matcher candidates = pattern.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC));
        String found = "";
        while (candidates.find()) {
            String value = candidates.group(1).replaceAll("[\\s\\p{Z}]+", "").toUpperCase(Locale.ROOT);
            if (!found.isEmpty() && !found.equals(value)) return "";
            found = value;
        }
        return found;
    }

    static String militaryService(String text) {
        String clean = ResultText.forDisplay(text);
        Matcher label = SERVICE_LABEL.matcher(clean);
        if (!label.find()) return "";
        String after = clean.substring(label.end()).replaceFirst("^[\\p{Z}\\t:：]*", "");
        String[] lines = after.split("\\r\\n|\\r|\\n", 3);
        String sameLine = beforeNextLabel(lines[0]);
        if (!sameLine.isEmpty()) return serviceValue(sameLine);
        if (!lines[0].trim().isEmpty() || lines.length < 2) return "";
        // A bare next line can belong to a neighbouring cell. Accept only service terminology.
        return serviceTerm(beforeNextLabel(lines[1]));
    }

    static String serviceValue(String text) {
        String value = ResultText.forDisplay(text).replaceAll("[\\s\\p{Z}]+", "");
        if (!SERVICE_WORD.matcher(value).matches() || Character.UnicodeScript.of(value.codePointAt(0)) != Character.UnicodeScript.HAN
                || OTHER_BACK_FIELD.matcher(value).find()
                || value.matches("役[別别]|空白|未填寫|未填|未辨識|無資料|無法辨識|樣張|樣|样|本")) return "";
        return value;
    }

    private static String beforeNextLabel(String text) {
        Matcher other = OTHER_BACK_FIELD.matcher(text);
        return (other.find() ? text.substring(0, other.start()) : text).trim();
    }

    private static String serviceTerm(String text) {
        String value = serviceValue(text);
        return value.matches(".*[役兵軍訓].*|後備|體位未定") ? value : "";
    }

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
        boolean front = reading.type.code.equals("id_front");
        if (!front && !back) return;
        String text = ResultText.forDisplay(reading.text);
        boolean needSex = front && reading.type.fieldLabel("Sex") != null
                && sexValue(cloudValue(reading, "Sex")).isEmpty() && printedSex(text).isEmpty();
        boolean needId = front && reading.type.fieldLabel("IdNumber") != null
                && idNumber(cloudValue(reading, "IdNumber")).isEmpty() && idNumber(text).isEmpty();
        if (front && !needSex && !needId) return;
        Bitmap source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (source == null) {
            if (back && !reading.local.has("Barcode")) reading.local.put("Barcode", "");
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
                String value = "";
                try {
                    value = barcode(upright);
                } catch (RuntimeException invalidImage) {
                    // Failure to read the optional bar region must not discard the Azure transcript.
                }
                String cloud = reading.local.optString("Barcode");
                if (cloud.isEmpty()) reading.local.put("Barcode", value);
                else if (!value.isEmpty() && !value.equals(cloud)) reading.local.put("Barcode", "");
            }
            boolean needService = back && reading.type.fieldLabel("MilitaryService") != null
                    && serviceValue(cloudValue(reading, "MilitaryService")).isEmpty() && militaryService(text).isEmpty();
            boolean needSerial = back && reading.type.fieldLabel("Serial") != null
                    && serial(cloudValue(reading, "Serial")).isEmpty() && serial(text).isEmpty();
            if (!needSex && !needId && !needService && !needSerial) return;
            try (DeviceText reader = new DeviceText(context)) {
                if (front) {
                    String region = regionText(reader, upright, .51f, .53f, 1f, 1f, false);
                    if (needSex) {
                        String value = printedSex(region);
                        if (value.isEmpty() && !SEX_LABEL.matcher(region).find()) value = uniqueValue(SINGLE_SEX.matcher(region));
                        putValue(reading, "Sex", value);
                    }
                    if (needId) {
                        String value = idNumber(region);
                        if (value.isEmpty()) value = idNumber(regionText(reader, upright, .53f, .74f, 1f, .99f, false));
                        if (value.isEmpty()) value = idNumber(regionText(reader, upright, .53f, .74f, 1f, .99f, true));
                        putValue(reading, "IdNumber", value);
                    }
                }
                if (needService) {
                    String region = regionText(reader, upright, .43f, .21f, .99f, .39f, false);
                    String value = militaryService(region);
                    if (value.isEmpty() && !SERVICE_LABEL.matcher(region).find()) value = serviceTerm(region);
                    putValue(reading, "MilitaryService", value);
                }
                if (needSerial) putValue(reading, "Serial", serial(regionText(reader, upright, .53f, .70f, 1f, 1f, false)));
            } catch (IOException | RuntimeException unavailable) {
                // Model download or OCR failure must preserve already available Azure and barcode results.
            }
        } finally {
            if (upright != source) upright.recycle();
            source.recycle();
        }
    }

    private static void putValue(AzureReader.Reading reading, String key, String value) throws JSONException {
        if (value != null && !value.isEmpty()) reading.local.put(key, value);
    }

    static String cloudValue(AzureReader.Reading reading, String key) {
        JSONObject field = reading.fields == null ? null : reading.fields.optJSONObject(key);
        return field == null || field.isNull("valueString") ? "" : ResultText.forDisplay(field.optString("valueString")).trim();
    }

    private static String regionText(DeviceText reader, Bitmap card, float left, float top, float right, float bottom, boolean enhanceRed)
            throws IOException, InterruptedException {
        int x = (int)(card.getWidth() * left), y = (int)(card.getHeight() * top);
        int w = Math.max(1, Math.round(card.getWidth() * right) - x);
        int h = Math.max(1, Math.round(card.getHeight() * bottom) - y);
        Bitmap region = Bitmap.createBitmap(card, x, y, w, h);
        float scale = Math.max(1f, Math.min(3f, 850f / w));
        Bitmap contrast = null, enlarged = null;
        try {
            contrast = enhanceRed ? redInk(region) : region;
            enlarged = Bitmap.createScaledBitmap(contrast, Math.round(w * scale), Math.round(h * scale), true);
            return ResultText.forDisplay(reader.read(enlarged).getText());
        } finally {
            if (enlarged != null && enlarged != contrast) enlarged.recycle();
            if (contrast != null && contrast != region) contrast.recycle();
            if (region != card) region.recycle();
        }
    }

    // The front number is often red on a patterned background. Keep ordinary OCR for monochrome copies.
    static Bitmap redInk(Bitmap region) {
        int w = region.getWidth(), h = region.getHeight();
        int[] pixels = new int[w*h];
        region.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int i = 0; i < pixels.length; i++) {
            int rgb = pixels[i], r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
            int redAmount = Math.max(0, 2*r - g - b - 16);
            int gray = Math.max(0, 248 - redAmount - (27 * (255-g)) / 100);
            pixels[i] = 0xff000000 | (gray * 0x010101);
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
    }

    static String barcode(Bitmap card) {
        int w = Math.round(card.getWidth() * .77f), top = (int)(card.getHeight() * .45f);
        int h = card.getHeight() - top;
        if (w < 30 || h < 6) return "";
        Bitmap region = Bitmap.createBitmap(card, 0, top, w, h);
        try {
            String value = decodeBars(region);
            if (!value.isEmpty()) return value;
            // Short bars photographed at an angle can be crossed by a horizontal scan line.
            // Rotate only the barcode region; cap its resolution for these additional attempts.
            float scale = Math.min(1f, 1050f / w);
            for (int angle : new int[]{8, -8, 16, -16}) {
                Matrix m = new Matrix();
                m.setScale(scale, scale);
                m.postRotate(angle);
                Bitmap rotated = Bitmap.createBitmap(region, 0, 0, w, h, m, true);
                try {
                    value = decodeBars(rotated);
                    if (!value.isEmpty()) return value;
                } finally {
                    if (rotated != region) rotated.recycle();
                }
            }
            return "";
        } finally {
            if (region != card) region.recycle();
        }
    }

    static String decodeBars(Bitmap image) {
        int w = image.getWidth(), h = image.getHeight();
        int[] pixels = new int[w * h];
        image.getPixels(pixels, 0, w, 0, 0, w, h);
        RGBLuminanceSource source = new RGBLuminanceSource(w, h, pixels);
        MultiFormatOneDReader decoder = new MultiFormatOneDReader(BARCODE_HINTS);
        try {
            return decoder.decode(new BinaryBitmap(new HybridBinarizer(source)), BARCODE_HINTS).getText();
        } catch (ReaderException noLocalThreshold) {
            try {
                return decoder.decode(new BinaryBitmap(new GlobalHistogramBinarizer(source)), BARCODE_HINTS).getText();
            } catch (ReaderException unreadable) {
                return contrastRows(source, decoder, w, h);
            }
        }
    }

    // Average parallel rows to suppress speckle, then threshold against nearby bars
    // and spaces. This preserves narrow stripes across shadows on laminated cards.
    private static String contrastRows(RGBLuminanceSource source, MultiFormatOneDReader decoder, int w, int h) {
        if (w < 32 || h < 5) return "";
        byte[] gray = source.getMatrix();
        int[] profile = new int[w];
        int radius = Math.max(12, Math.min(48, w / 30));
        BitArray bits = new BitArray(w);
        String previous = "";
        for (int y = 2; y < h - 2; y += Math.max(2, h / 42)) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                profile[x] = ((gray[i - w] & 255) + 2 * (gray[i] & 255) + (gray[i + w] & 255)) / 4;
            }
            bits.clear();
            for (int start = 0; start < w; start += radius) {
                int lo = 255, hi = 0;
                for (int x = Math.max(0, start - radius); x < Math.min(w, start + 2 * radius); x++) {
                    lo = Math.min(lo, profile[x]);
                    hi = Math.max(hi, profile[x]);
                }
                if (hi - lo < 12) continue;
                for (int x = start; x < Math.min(w, start + radius); x++)
                    if (2 * profile[x] < lo + hi) bits.set(x);
            }
            try {
                String value = decoder.decodeRow(y, bits, BARCODE_HINTS).getText();
                if (value.equals(previous)) return value;
                previous = value;
            } catch (ReaderException unreadable) {
                // Other rows may avoid glare or printed text crossing the stripes.
            } finally {
                decoder.reset();
            }
        }
        return "";
    }
}
