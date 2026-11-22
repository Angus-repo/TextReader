package tw.codeviva.cardreader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import org.json.JSONObject;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A reading aid for printed labels in plain OCR. The original transcript stays intact. */
final class OcrRows {
    private static final String SPACE = "[\\s　]";
    private static final Pattern EDGE_SPACE = Pattern.compile("^" + SPACE + "+|" + SPACE + "+$");

    private static final Pattern ADDRESS_PART = Pattern.compile(".*[鄉鎮區村里鄰路街段巷弄號樓室].*");

    private static final Pattern NHI_ID = Pattern.compile("(?:[A-Za-z][0-9]{9}|[A-Za-z]{2}[0-9]{8})");
    private static final Pattern NHI_BIRTH = Pattern.compile("[0-9]{1,4}([./-])(?:0?[1-9]|1[0-2])\\1(?:0?[1-9]|[12][0-9]|3[01])");
    private static final Pattern NHI_NUMBER = Pattern.compile("[0-9]{4}(?:" + SPACE + "*[0-9]{4}){2}");
    private static final Pattern NAME_TEXT = Pattern.compile("[\\p{L}][\\p{L}\\p{M} ·・‧．.'’-]*");

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
        String[] lines = text.split("\\r\\n|\\r|\\n");
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
        if (card.code.equals("nhi_front")) addUnlabeledNhiFields(card, lines, rows);
        return rows;
    }

    // NHI cards print the name immediately above the ID. Other values have distinct printed formats.
    private static void addUnlabeledNhiFields(CardCatalog.Card card, String[] lines, List<ResultLayout.Row> rows) {
        Map<String, Set<String>> candidates = new LinkedHashMap<>();
        String previous = "";
        for (String line : lines) {
            if (line.isEmpty()) continue;
            if (NHI_ID.matcher(line).matches()) {
                remember(candidates, "IdNumber", line);
                if (looksLikeNhiName(previous, card)) remember(candidates, "Name", previous);
            } else if (NHI_BIRTH.matcher(line).matches()) {
                remember(candidates, "BirthDate", line);
            } else if (NHI_NUMBER.matcher(line).matches()) {
                remember(candidates, "NhiCardNumber", line.replaceAll(SPACE + "+", ""));
            }
            previous = line;
        }

        // Map by the saved rule key so renamed labels work and removed fields stay removed.
        Set<String> printed = new LinkedHashSet<>();
        for (ResultLayout.Row row : rows) printed.add(row.label);
        for (int i = 0; i < card.fields.length(); i++) {
            JSONObject field = card.fields.optJSONObject(i);
            String key = field.optString("key");
            String label = field.optString("label");
            Set<String> matches = candidates.get(key);
            if (printed.contains(label) || matches == null || matches.size() != 1) continue;
            if (key.equals("Name") && candidates.get("IdNumber").size() != 1) continue;
            add(rows, label, matches.iterator().next());
        }
    }

    private static void remember(Map<String, Set<String>> candidates, String key, String value) {
        candidates.computeIfAbsent(key, unused -> new LinkedHashSet<>()).add(value);
    }

    private static boolean looksLikeNhiName(String text, CardCatalog.Card card) {
        if (!NAME_TEXT.matcher(text).matches()) return false;
        String compact = text.replaceAll(SPACE + "+", "").toUpperCase(Locale.ROOT);
        if (compact.length() < 2 || compact.contains("健康保險") || compact.contains("HEALTH")
                || compact.contains("INSURANCE") || compact.equals("NATIONAL") || compact.equals("健保卡")
                || compact.equals("樣本") || compact.equals("樣張") || compact.equals("SAMPLE")) return false;
        for (int i = 0; i < card.fields.length(); i++) {
            if (text.equals(card.fields.optJSONObject(i).optString("label"))) return false;
        }
        return true;
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
                labels.addAll(Arrays.asList("住址", "地址", "持照條件", "管轄編號", "管制編號", "駕照號碼", "駕駛執照號碼",
                        "姓名", "性別", "駕照種類", "車種", "出生日期", "出生年月日", "有效日期", "發照日期", "審驗日期"));
                return true;
            case "dl_back":
                labels.addAll(Arrays.asList("印製號碼", "動員編號", "備註", "住所變更", "住址變更", "登記機關", "登記機關章戳", "職業駕駛審驗", "加註條件"));
                return true;
            case "nhi_front":
                return true;
            default:
                return false;
        }
    }
}
