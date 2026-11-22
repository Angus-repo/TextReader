package tw.codeviva.cardreader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A reading aid for printed labels in plain OCR. The original transcript stays intact. */
final class OcrRows {
    private static final String SPACE = "[\\s　]";
    private static final Pattern EDGE_SPACE = Pattern.compile("^" + SPACE + "+|" + SPACE + "+$");

    private static final Pattern ADDRESS_PART = Pattern.compile(".*[鄉鎮區村里鄰路街段巷弄號樓室].*");

    static List<ResultLayout.Row> from(CardCatalog.Card card, String text) {
        Set<String> labels = new LinkedHashSet<>();
        for (int i = 0; i < card.fields.length(); i++) {
            labels.add(card.fields.optJSONObject(i).optString("label"));
        }
        boolean identityCard = addPrintedLabels(card.code, labels);
        labels.remove("");
        List<ResultLayout.Row> rows = new ArrayList<>();
        if (text.isEmpty() || labels.isEmpty()) return rows;

        List<String> alternatives = new ArrayList<>();
        for (String label : labels) alternatives.add(Pattern.quote(label));
        // General prose needs a colon. On ID cards a label may occupy a cell on its own.
        String delimiter = identityCard ? "(?=[\\s　:：]|$)" + SPACE + "*[:：]?" : SPACE + "*[:：]";
        Pattern header = Pattern.compile("(?<![^\\s　])(" + String.join("|", alternatives) + ")" + delimiter + SPACE + "*");
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) lines[i] = EDGE_SPACE.matcher(lines[i]).replaceAll("");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            if (identityCard && i + 1 < lines.length && !labels.contains(line)) {
                String joined = line + lines[i + 1];
                if (labels.contains(joined)) {
                    line = joined;
                    i++;
                }
            }
            Matcher match = header.matcher(line);
            if (!match.lookingAt()) continue;

            String label = match.group(1);
            int start = match.end();
            boolean sharedLine = false;
            while (match.find()) {
                add(rows, label, line.substring(start, match.start()));
                label = match.group(1);
                start = match.end();
                sharedLine = true;
            }
            String value = line.substring(start).trim();
            if (value.isEmpty() && !sharedLine && i + 1 < lines.length) {
                String next = lines[i + 1];
                if (!next.isEmpty() && !header.matcher(next).lookingAt()
                        && !(i + 2 < lines.length && labels.contains(next + lines[i + 2]))) {
                    value = next;
                    i++;
                }
            }
            if (identityCard && isName(label) && isSingleHanCharacter(value)) {
                StringBuilder name = new StringBuilder(value);
                while (i + 1 < lines.length) {
                    String next = lines[i + 1];
                    if (!isSingleHanCharacter(next) || labels.contains(next)) break;
                    if (i + 2 < lines.length && labels.contains(next + lines[i + 2])) break;
                    name.append(next);
                    i++;
                }
                value = name.toString();
            }
            if (identityCard && !value.isEmpty() && (label.contains("地址") || label.contains("住址"))) {
                StringBuilder address = new StringBuilder(value);
                while (i + 1 < lines.length) {
                    String next = lines[i + 1];
                    if (!ADDRESS_PART.matcher(next).matches() || header.matcher(next).lookingAt()) break;
                    address.append(next);
                    i++;
                }
                value = address.toString();
            }
            add(rows, label, value);
        }
        return rows;
    }

    private static void add(List<ResultLayout.Row> rows, String label, String text) {
        String value = EDGE_SPACE.matcher(text).replaceAll("");
        if (!value.isEmpty()) rows.add(new ResultLayout.Row(label, value, Double.NaN));
    }

    private static boolean isSingleHanCharacter(String value) {
        return value.codePointCount(0, value.length()) == 1
            && Character.UnicodeScript.of(value.codePointAt(0)) == Character.UnicodeScript.HAN;
    }

    private static boolean isName(String label) {
        return label.endsWith("姓名") || label.equals("父") || label.equals("母") || label.equals("配偶");
    }

    private static boolean addPrintedLabels(String code, Set<String> labels) {
        switch (code) {
            case "id_front":
                labels.addAll(Arrays.asList("統一編號", "出生年月日"));
                return true;
            case "id_back":
                labels.addAll(Arrays.asList("父", "母", "配偶", "住址"));
                return true;
            case "dl_front":
                labels.addAll(Arrays.asList("住址", "持照條件", "管轄編號", "管制編號"));
                return true;
            case "dl_back":
                labels.addAll(Arrays.asList("住所變更", "登記機關"));
                return true;
            case "nhi_front":
                return true;
            default:
                return false;
        }
    }
}
