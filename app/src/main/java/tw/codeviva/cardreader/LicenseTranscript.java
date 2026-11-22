package tw.codeviva.cardreader;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recovers values from printed headings and row order when the page has no usable word boxes. */
final class LicenseTranscript {
    private static final String NEXT = "[:：]?\\n?";
    private static final String DATE = "((?:民國)?(?:\\d{1,4}[./-](?:0?[1-9]|1[0-2])[./-](?:0?[1-9]|[12]\\d|3[01])|\\d{1,4}年\\d{1,2}月\\d{1,2}日?))";
    private static final String CLASS = "(?:(?:普通|職業)?(?:小型車|小型汽車|大貨車|大客車|聯結車)|(?:大型重型|普通重型|普通輕型|小型輕型|重型|輕型)機車)";
    private static final String CODE = "([A-Z0-9](?:[、,/][A-Z0-9])*)";
    private static final String CONTROL_LABEL = "管\\p{IsHan}\\n?編號";
    private static final String CONTROL = "([A-Z0-9-]{4,20})";
    private static final Pattern CLASS_ROW = line("(?:駕照\\n?種類" + NEXT + ")?(" + CLASS + ")");
    private static final Pattern CODE_LABEL = line("持照\\n?條件(?:代碼)?" + NEXT + CODE);
    // The class and address delimit the small condition cell even when its heading is damaged.
    private static final Pattern CODE_ROW = line(CLASS + "\\n(?:[\\p{IsHan}]{1,4}\\n){0,2}" + CODE
            + "(?=\\n(?:住|地)\\n?址)");
    private static final Pattern BIRTH = line("出生\\n?(?:日期|年月日)" + NEXT + DATE);
    private static final Pattern EXPIRY = line("有效\\n?日期" + NEXT + DATE);
    private static final Pattern ISSUE = line("發照\\n?日期" + NEXT + DATE);
    private static final Pattern REVIEW = line("審驗\\n?日期" + NEXT + DATE);
    private static final Pattern CONTROL_ROW = line(CONTROL_LABEL + NEXT + CONTROL);
    // If only 「日期」 survived, require the preceding control number and the *following*
    // review heading. A lone date at the bottom could belong to either column.
    private static final Pattern ISSUE_ROW = line(CONTROL_LABEL + NEXT + "[A-Z0-9-]{4,20}\\n日期" + NEXT + DATE
            + "(?=\\n(?:審驗|[\\p{IsHan}]{2}\\n日[\\p{IsHan}])(?:\\n|$))");
    private static final Pattern PRINT = line("印\\n?製\\n?號\\n?碼" + NEXT
            + "((?:\\([\\p{IsHan}A-Za-z0-9]{1,3}\\)\\n?)?[A-Za-z-]*\\d[A-Za-z0-9-]{2,19})");
    private static final Pattern MOBILIZATION = line("動員\\n?編號" + NEXT + "([A-Za-z0-9-]*\\d[A-Za-z0-9-]*)");
    private static final Pattern LEGEND_A = line("A[.:、]?\\n?([\\p{IsHan}]{2,24})");
    private static final Pattern LEGEND_B = line("B[.:、]?\\n?([\\p{IsHan}]{2,24})");
    private static final Pattern ANNOTATION = line("[\\h]*C[\\h]*加註條件[\\h]*[:：][\\h]*([^\\n]+)");
    private static final Pattern AUTHORITY = line("發照機關" + NEXT + "([\\p{IsHan}]{2,20}(?:部|局|所|站|中心|政府))");
    private static final Pattern TRANSPORT_TITLE = Pattern.compile("交通部(?:製發|制發|核發)(?:汽車|機車)?駕駛執照");
    private static final Pattern SPECIMEN = Pattern.compile("[樣样][\\s\\p{Z}]*[張张]");

    private static Pattern line(String body) {
        return Pattern.compile("^" + body + "$", Pattern.MULTILINE);
    }

    private static String unique(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        String candidate = "";
        while (matcher.find()) {
            String value = matcher.group(1).replace("\n", "");
            if (!candidate.isEmpty() && !candidate.equals(value)) return "";
            candidate = value;
        }
        return candidate;
    }

    static Map<String,String> read(String side, String raw) {
        String text = Normalizer.normalize(raw, Normalizer.Form.NFKC).replace("\r\n","\n").replace('\r','\n');
        String unmarked = SPECIMEN.matcher(text).replaceAll("");
        text = unmarked.replaceAll("[\\p{Z}\\t]+", "").replaceAll("\\n+", "\n");
        Map<String,String> values = new LinkedHashMap<>();
        if ("dl_front".equals(side)) {
            values.put("LicenseClass",unique(CLASS_ROW,text));
            String code = unique(CODE_LABEL,text);
            values.put("ConditionCode",code.isEmpty() ? unique(CODE_ROW,text) : code);
            values.put("BirthDate",unique(BIRTH,text));
            values.put("ExpiryDate",unique(EXPIRY,text));
            values.put("ControlNumber",unique(CONTROL_ROW,text));
            String issueDate = unique(ISSUE,text);
            values.put("IssueDate",issueDate.isEmpty() ? unique(ISSUE_ROW,text) : issueDate);
            values.put("ReviewDate",unique(REVIEW,text));
            String authority = unique(AUTHORITY,text);
            if (authority.isEmpty() && TRANSPORT_TITLE.matcher(text.replace("\n","")).find()) authority = "交通部";
            values.put("Authority",authority);
        } else {
            values.put("PrintNumber",unique(PRINT,text));
            values.put("Mobilization",unique(MOBILIZATION,text));
            int annotationStart = text.indexOf("C加註條件");
            String legends = annotationStart < 0 ? text : text.substring(0,annotationStart);
            String a = unique(LEGEND_A,legends), b = unique(LEGEND_B,legends);
            // Restore these two *printed legends* only when both match the template;
            // they are independent of the holder's condition code on the front.
            if (a.matches("[無无典][限尿]制") && b.matches("[駕驾寅寓][駛驶秋]自動排檔車")) {
                a = "無限制";
                b = "駕駛自動排檔車";
            }
            values.put("ConditionA",a);
            values.put("ConditionB",b);
            values.put("Conditions",LicenseFields.clean("Conditions",unique(ANNOTATION,unmarked)));
        }
        return values;
    }
}
