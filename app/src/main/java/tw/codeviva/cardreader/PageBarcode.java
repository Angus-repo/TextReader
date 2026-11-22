package tw.codeviva.cardreader;

import org.json.JSONArray;
import org.json.JSONObject;

/** Select the linear barcode on the lower left of the upright ID card. */
final class PageBarcode {
    static String lowerLeft(JSONObject page) {
        if (page == null) return "";
        JSONArray bars = page.optJSONArray("barcodes");
        if (bars == null) return "";
        double w = page.optDouble("width"), h = page.optDouble("height");
        int turn = Math.floorMod((int) Math.round(page.optDouble("angle", 0) / 90), 4);
        String selected = "";
        for (int i = 0; i < bars.length(); i++) {
            JSONObject bar = bars.optJSONObject(i);
            if (bar == null || bar.isNull("value") || !linear(bar.optString("kind"))) continue;
            String source = bar.optString("source");
            if (!source.startsWith("D(") || !source.endsWith(")")) continue;
            String[] points = source.substring(2, source.length() - 1).split(",");
            if (points.length != 9) continue;
            double x = 0, y = 0;
            try {
                if (Integer.parseInt(points[0].trim()) != page.optInt("pageNumber", 1)) continue;
                for (int j = 1; j < points.length; j += 2) {
                    x += Double.parseDouble(points[j]) / (4 * w);
                    y += Double.parseDouble(points[j + 1]) / (4 * h);
                }
            } catch (NumberFormatException malformedPosition) {
                continue;
            }
            double left = turn == 0 ? x : turn == 1 ? y : turn == 2 ? 1 - x : 1 - y;
            double top = turn == 0 ? y : turn == 1 ? 1 - x : turn == 2 ? 1 - y : x;
            if (!(left > 0 && left < .55 && top > .53 && top < 1)) continue;
            String value = bar.optString("value");
            if (value.isEmpty()) continue;
            if (!selected.isEmpty() && !selected.equals(value)) return "";
            selected = value;
        }
        return selected;
    }

    private static boolean linear(String kind) {
        switch (kind) {
            case "Code39": case "Code128": case "Code93": case "Codabar": case "ITF":
            case "EAN8": case "EAN13": case "UPCA": case "UPCE":
            case "DataBar": case "DataBarExpanded":
                return true;
            default:
                return false;
        }
    }
}
