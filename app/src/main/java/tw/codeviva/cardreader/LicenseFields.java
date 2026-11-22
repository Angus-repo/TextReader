package tw.codeviva.cardreader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Field keys are stable even when the user renames a display label. Coordinates refer to the printed grid. */
final class LicenseFields {
    static boolean supports(String code) { return code.equals("dl_front") || code.equals("dl_back"); }

    static final class Cell {
        final String key;
        final float l, t, r, b;
        Cell(String key, double l, double t, double r, double b) {
            this.key = key; this.l = (float)l; this.t = (float)t; this.r = (float)r; this.b = (float)b;
        }
    }

    static final Cell[] FRONT = {
        new Cell("LicenseNumber", .067,.272,.472,.352), new Cell("Sex", .55,.272,.665,.352),
        new Cell("Name", .068,.362,.661,.527), new Cell("BirthDate", .068,.543,.407,.617),
        new Cell("LicenseClass", .476,.543,.812,.617), new Cell("ConditionCode", .882,.543,.996,.617),
        new Cell("Address", .069,.632,.993,.822), new Cell("ExpiryDate", .072,.839,.472,.914),
        new Cell("ControlNumber", .553,.839,.994,.914), new Cell("IssueDate", .073,.929,.472,.994),
        new Cell("ReviewDate", .553,.929,.994,.994), new Cell("Authority", .013,.10,.655,.26)
    };
    static final Cell[] BACK = {
        new Cell("PrintNumber", .088,.01,.591,.147), new Cell("Mobilization", .684,.01,.996,.147),
        new Cell("ConditionA", .086,.162,.669,.244), new Cell("ConditionB", .683,.162,.996,.244),
        new Cell("Conditions", .087,.256,.994,.439),
        new Cell("AddressChange1", .087,.454,.751,.536), new Cell("AddressStamp1", .815,.454,.995,.536),
        new Cell("AddressChange2", .087,.552,.751,.631), new Cell("AddressStamp2", .815,.552,.995,.631),
        new Cell("AddressChange3", .087,.645,.751,.716), new Cell("AddressStamp3", .815,.645,.995,.716),
        new Cell("ReviewRecord1", .087,.732,.507,.81), new Cell("ReviewStamp1", .815,.732,.995,.81),
        new Cell("ReviewRecord2", .087,.821,.507,.901), new Cell("ReviewStamp2", .815,.821,.995,.901),
        new Cell("Notes", .087,.916,.993,.993)
    };
    private static final Pattern SPECIMEN = Pattern.compile("[樣样][\\s\\p{Z}]*[張张]");
    private static final Pattern DATE = Pattern.compile("(?:民國)?[0-9]{1,4}(?:[./-][0-9]{1,2}[./-][0-9]{1,2}|年[0-9]{1,2}月[0-9]{1,2}日?)");
    private static final Pattern AUTHORITY = Pattern.compile("([\\p{IsHan}]{2,18}(?:部|局|所|站))(?=製發|核發|制發|制发)");

    static String clean(String key, String raw) {
        String s = SPECIMEN.matcher(ResultText.forDisplay(raw)).replaceAll("").trim();
        s = s.replaceAll("【\\s*】|\\[\\s*\\]|〔\\s*〕", "").trim();
        if (s.matches("[\\s\\p{P}\\p{S}]*") || s.matches("未讀取|未填寫|未填|空白|無資料|null|[\\s:：|]+")) return "";
        String compact = s.replaceAll("[\\s\\p{Z}]+", "");
        switch (key) {
            case "Authority":
                if (compact.matches(".*交通部(?:製發|制發|核發)(?:汽車|機車)?駕駛執照.*")) return "交通部";
                java.util.regex.Matcher m = AUTHORITY.matcher(compact.replace("中華民國",""));
                if (m.find()) return m.group(1);
                return compact.matches("[\\p{IsHan}]{2,20}(部|局|所|站|中心|政府)") ? compact : "";
            case "LicenseNumber": return compact.matches("(?i)[A-Z0-9][A-Z0-9-]{5,19}") ? compact : "";
            case "ControlNumber": case "Mobilization": return compact.matches("(?i)[A-Z0-9-]+") ? compact : "";
            case "PrintNumber": return compact.matches(".*[0-9].*") ? compact : "";
            case "Sex": return IdCardDetails.sexValue(compact);
            case "BirthDate": case "IssueDate": case "ExpiryDate": case "ReviewDate":
                return DATE.matcher(compact).matches() || compact.matches("長期有效|永久有效|免換照") ? compact : "";
            case "ConditionCode": return compact.matches("[A-Za-z0-9、/，,-]{1,12}") ? compact : "";
            case "ConditionA": case "ConditionB":
                return s.replaceFirst("(?i)^" + key.charAt(key.length()-1) + "[\\s:：.、]*", "").trim();
            case "Conditions":
                s = s.replaceFirst("(?i)^\\s*C?\\s*加\\s*註\\s*條\\s*件\\s*[:：]?","").trim();
                return s.matches("[ABC\\s:：|]*") || s.matches("[\\s\\p{P}\\p{S}]*") || compact.contains("A無限制") || compact.contains("B駕駛自動排檔車") ? "" : s;
            default: return s;
        }
    }

    // A positioned OCR word may be a single Han character or a complete alphanumeric string.
    private static final class Word {
        final double x, y, left, right;
        final String value;
        Word(double left, double right, double y, String value) {
            this.left = left; this.right = right; this.x = (left+right)/2; this.y = y; this.value = value;
        }
    }
    private static List<Word> words(JSONObject page, int turn, LicenseGrid grid, String level) {
        List<Word> result = new ArrayList<>();
        JSONArray items = page == null ? null : page.optJSONArray(level);
        if (items == null) return result;
        double width = page.optDouble("width"), height = page.optDouble("height");
        if (!(width > 0 && height > 0)) return result;
        for (int i=0; i<items.length(); i++) {
            JSONObject word = items.optJSONObject(i);
            if (word == null || word.isNull("content")) continue;
            String src = word.optString("source");
            if (!src.startsWith("D(") || !src.endsWith(")")) continue;
            String[] p = src.substring(2,src.length()-1).split(",");
            if (p.length != 9) continue;
            double left = 1, right = 0, centerY = 0;
            try {
                if (Integer.parseInt(p[0].trim()) != page.optInt("pageNumber",1)) continue;
                for (int j = 1; j < 9; j += 2) {
                    double x = Double.parseDouble(p[j])/width, y = Double.parseDouble(p[j+1])/height;
                    double ux = turn == 0 ? x : turn == 1 ? y : turn == 2 ? 1-x : 1-y;
                    double uy = turn == 0 ? y : turn == 1 ? 1-x : turn == 2 ? 1-y : x;
                    left = Math.min(left,grid.x(ux)); right = Math.max(right,grid.x(ux));
                    centerY += grid.y(ux,uy)/4;
                }
            } catch (NumberFormatException invalid) { continue; }
            result.add(new Word(left,right,centerY,word.optString("content")));
        }
        return result;
    }

    static JSONObject fromPage(JSONObject page, int turn, LicenseGrid grid, Cell[] cells) throws JSONException {
        List<Word> words = words(page, turn, grid, "words");
        if (words.isEmpty()) words = words(page, turn, grid, "lines");
        JSONObject values = new JSONObject();
        for (Cell cell : cells) {
            StringBuilder value = new StringBuilder();
            for (Word word : words) {
                if (!(word.x >= cell.l && word.x <= cell.r && word.left >= cell.l-.025 && word.right <= cell.r+.025 && word.y >= cell.t && word.y <= cell.b)) continue;
                if (value.length() > 0 && Character.isLetter(value.charAt(value.length()-1)) &&
                        value.charAt(value.length()-1) < 128 && word.value.matches("^[a-zA-Z].*")) value.append(' ');
                value.append(word.value);
            }
            values.put(cell.key, clean(cell.key, value.toString()));
        }
        return values;
    }

    static void readImage(Context context, AzureReader.Reading reading, byte[] jpeg) throws InterruptedException, JSONException {
        if (!supports(reading.type.code) || !reading.type.hasFields()) return;
        Bitmap image = BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);
        if (image == null) return;
        Bitmap upright = image;
        DeviceText ocr = null;
        try {
            int turn = Math.floorMod((int)Math.round(reading.pageAngle/90),4);
            if (turn != 0) {
                Matrix m = new Matrix(); m.setRotate(-90*turn);
                upright = Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),m,true);
            }
            boolean front = reading.type.code.equals("dl_front");
            LicenseGrid grid = LicenseGrid.find(upright,front);
            if (grid == null) return;
            List<Cell> requested = new ArrayList<>();
            for (Cell cell : front ? FRONT : BACK) {
                if (reading.type.fieldLabel(cell.key) != null || reading.type.fieldLabel(legacyKey(cell.key)) != null)
                    requested.add(cell);
            }
            Cell[] cells = requested.toArray(new Cell[0]);
            JSONObject values = fromPage(reading.licensePage,turn,grid,cells);
            JSONObject emptyCells = new JSONObject();
            boolean modelAvailable = true;

            for (Cell cell : cells) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                Rect rect = grid.region(upright,cell.l,cell.t,cell.r,cell.b);
                if (!hasInk(upright,rect)) {
                    if (!rect.isEmpty() && values.optString(cell.key).isEmpty()) emptyCells.put(cell.key,true);
                    continue;
                }
                if (!modelAvailable || !values.optString(cell.key).isEmpty() ||
                        !clean(cell.key,IdCardDetails.cloudValue(reading,cell.key)).isEmpty()) continue;
                Bitmap crop = Bitmap.createBitmap(upright,rect.left,rect.top,rect.width(),rect.height());
                Bitmap enlarged = crop;
                try {
                    float scale = Math.max(1f,Math.min(3f,Math.min(95f/crop.getHeight(),1150f/crop.getWidth())));
                    enlarged = Bitmap.createScaledBitmap(crop,Math.round(crop.getWidth()*scale),Math.round(crop.getHeight()*scale),true);
                    if (ocr == null) ocr = new DeviceText(context);
                    values.put(cell.key,clean(cell.key,ocr.read(enlarged).getText()));
                } catch (IOException | RuntimeException unavailable) {
                    modelAvailable = false; // Continue resolving the other cells from Azure.
                } finally {
                    if (enlarged != crop) enlarged.recycle();
                    if (crop != upright) crop.recycle();
                }
            }
            if (emptyCells.length() > 0) reading.local.put("LicenseBlankCells",emptyCells);
            for (Cell cell : cells) {
                if (!values.optString(cell.key).isEmpty()) {
                    reading.local.put("LicenseCells",values);
                    break;
                }
            }
        } finally {
            reading.licensePage = null;
            if (ocr != null) ocr.close();
            if (upright != image) upright.recycle();
            image.recycle();
        }
    }

    // Ignore ruling along the perimeter and mild paper texture. Require ink across several
    // rows so a horizontal table line alone does not trigger recognition of an empty cell.
    static boolean hasInk(Bitmap image, Rect rect) {
        int insetX = Math.max(2, Math.min(6,Math.round(rect.width()*.025f))), insetY = Math.max(2, Math.round(rect.height()*.1f));
        int w = rect.width()-2*insetX, h = rect.height()-2*insetY;
        if (w < 3 || h < 3) return false;
        int[] pixels = new int[w*h], histogram = new int[256];
        image.getPixels(pixels,0,w,rect.left+insetX,rect.top+insetY,w,h);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (pixels[i] >> 8) & 255;
            histogram[pixels[i]]++;
        }
        int paper = 255, brightCount = histogram[255];
        while (paper > 0 && brightCount < pixels.length/4) brightCount += histogram[--paper];
        int rows = 0;
        for (int y = 0; y < h; y++) {
            int dark = 0;
            for (int x = 0; x < w; x++) if (pixels[y*w+x] < paper-51 && ++dark >= 2) break;
            if (dark >= 2 && ++rows >= Math.max(3,h/18)) return true;
        }
        return false;
    }

    private static String legacyKey(String key) {
        if (key.startsWith("AddressChange")) return "ChangeOfAddress";
        if (key.startsWith("ReviewRecord")) return "Records";
        if (key.contains("Stamp")) return "Registration";
        return key;
    }

    private static String legacyGroup(JSONObject cells, String...keys) {
        List<String> lines = new ArrayList<>();
        for (String key : keys) if (!cells.optString(key).isEmpty()) lines.add(cells.optString(key));
        return String.join("\n",lines);
    }

    static String positioned(JSONObject cells, String key) {
        if (cells == null) return "";
        switch (key) {
            case "ChangeOfAddress": return legacyGroup(cells,"AddressChange1","AddressChange2","AddressChange3");
            case "Registration": return legacyGroup(cells,"AddressStamp1","AddressStamp2","AddressStamp3","ReviewStamp1","ReviewStamp2");
            case "Records": return legacyGroup(cells,"ReviewRecord1","ReviewRecord2");
            default: return clean(key,cells.optString(key));
        }
    }

    static Map<String,String> labeled(CardCatalog.Card card, String text) {
        Map<String,String> values = new LinkedHashMap<>();
        for (ResultLayout.Row row : OcrRows.from(card,SPECIMEN.matcher(text).replaceAll(""))) {
            String old = values.putIfAbsent(row.label,row.value);
            if (old != null && !old.equals(row.value)) values.put(row.label,"");
        }
        return values;
    }

    private static String[] printedLabels(String key) {
        switch (key) {
            case "LicenseNumber": return new String[]{"駕照號碼","駕駛執照號碼"};
            case "Name": return new String[]{"姓名"};
            case "Sex": return new String[]{"性別"};
            case "Address": return new String[]{"住址","地址"};
            case "LicenseClass": return new String[]{"駕照種類","車種"};
            case "ControlNumber": return new String[]{"管轄編號","管制編號"};
            case "ConditionCode": return new String[]{"持照條件"};
            case "BirthDate": return new String[]{"出生日期","出生年月日"};
            case "ExpiryDate": return new String[]{"有效日期"};
            case "IssueDate": return new String[]{"發照日期"};
            case "ReviewDate": return new String[]{"審驗日期"};
            case "PrintNumber": return new String[]{"印製號碼"};
            case "Mobilization": return new String[]{"動員編號"};
            case "Conditions": return new String[]{"加註條件"};
            case "Notes": return new String[]{"備註"};
            default: return new String[0];
        }
    }

    static String fromLabels(Map<String,String> values, String key, String label, String text) {
        String s = values.get(label);
        if (s == null) {
            for (String alias : printedLabels(key)) {
                if (values.containsKey(alias)) { s = values.get(alias); break; }
            }
        }
        if (s != null) return clean(key,s);
        if (key.equals("Authority")) return clean(key,text.replace('\n',' '));
        if (key.equals("LicenseNumber")) return IdCardDetails.idNumber(text);
        return "";
    }
}
