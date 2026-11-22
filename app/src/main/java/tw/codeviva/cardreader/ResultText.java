package tw.codeviva.cardreader;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Display formatting shared by new results, saved results, and copied JSON. */
final class ResultText {
    static final String RULE = "共用規則：有「樣本」的照片照常辨識與儲存。欄位結果、JSON 顯示與複製內容都會略過這兩個字，包含兩字之間有空格或換行的情況。";
    private static final String SPACE = "[\\s\\p{Z}\\u200B\\uFEFF]";
    private static final Pattern WATERMARK = Pattern.compile("[樣样]" + SPACE + "*本");
    private static final Pattern BLANK = Pattern.compile(SPACE + "*");

    static String forDisplay(String original) {
        Matcher mark = WATERMARK.matcher(original);
        if (!mark.find()) return original;
        // Keep original line boundaries around surviving text. Drop only the
        // lines emptied by removing the watermark, including vertical lettering.
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        boolean hasMatch = true, changed = false;
        for (int i = 0; i <= original.length(); i++) {
            if (hasMatch && i >= mark.end()) hasMatch = mark.find();
            boolean hidden = hasMatch && i >= mark.start();
            changed |= hidden;
            char c = i < original.length() ? original.charAt(i) : '\n';
            if (c == '\r' || c == '\n') {
                if (!changed || !BLANK.matcher(line).matches()) lines.add(line.toString());
                line.setLength(0);
                changed = false;
                if (c == '\r' && i + 1 < original.length() && original.charAt(i + 1) == '\n') i++;
            } else if (!hidden) {
                line.append(c);
            }
        }
        return String.join("\n", lines);
    }
}
