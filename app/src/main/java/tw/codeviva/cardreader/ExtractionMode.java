package tw.codeviva.cardreader;

import java.math.BigDecimal;

/** The selected input strategy and the corresponding asynchronous Azure reference rate. */
enum ExtractionMode {
    MINIMAL("Minimal", "手機 OCR → 數位文字", "prebuilt-digitalParse", "0.01"),
    BASIC("Basic", "Azure OCR 讀取文字", "prebuilt-read", "1"),
    STANDARD("Standard", "Azure OCR 與版面分析", "prebuilt-layout", "5");

    final String label, description, analyzer;
    final BigDecimal referenceRate;

    ExtractionMode(String label, String description, String analyzer, String rate) {
        this.label = label;
        this.description = description;
        this.analyzer = analyzer;
        this.referenceRate = new BigDecimal(rate);
    }

    static ExtractionMode fromLabel(String value) {
        for (ExtractionMode mode : values())
            if (mode.label.equalsIgnoreCase(value)) return mode;
        return null;
    }

    static ExtractionMode forRule(String value, String code) {
        ExtractionMode mode = fromLabel(value);
        if (mode != null) return mode;
        return "receipt".equals(code) || "form".equals(code) ? STANDARD : BASIC;
    }

    String meter() { return "documentPages" + label; }

    BigDecimal asyncRate(Config config) {
        String custom = config.pageRates.optString(label);
        return custom.isEmpty() ? referenceRate : new BigDecimal(custom);
    }

    @Override public String toString() { return label + " · " + description; }
}
