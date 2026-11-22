package tw.codeviva.cardreader;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;

/** All corners are in the visible viewfinder's normalized coordinates, clockwise from top left. */
final class DocumentFrame {
    final float[] corners;
    final float coverage;
    final boolean clipped;
    private DocumentFrame(float[] corners) {
        this.corners = corners;
        float twiceArea = 0;
        boolean atEdge = false;
        for (int i = 0; i < 8; i += 2) {
            int next = (i + 2) % 8;
            twiceArea += corners[i] * corners[next + 1] - corners[next] * corners[i + 1];
            atEdge |= corners[i] < .009f || corners[i + 1] < .009f ||
                      corners[i] > .991f || corners[i + 1] > .991f;
        }
        coverage = twiceArea / 2;
        clipped = atEdge;
    }

    static DocumentFrame detect(Bitmap bitmap, Rect words) {
        float scale = 320f / Math.max(bitmap.getWidth(), bitmap.getHeight());
        int w = Math.round(bitmap.getWidth() * scale), h = Math.round(bitmap.getHeight() * scale);
        Bitmap small = Bitmap.createScaledBitmap(bitmap, w, h, true);
        int[] gray = new int[w * h];
        small.getPixels(gray, 0, w, 0, 0, w, h);
        if (small != bitmap) small.recycle();
        for (int i = 0; i < gray.length; i++) {
            int rgb = gray[i];
            gray[i] = (77 * Color.red(rgb) + 150 * Color.green(rgb) + 29 * Color.blue(rgb)) >> 8;
        }
        Rect anchor = words == null || words.isEmpty() ? null : new Rect(
                Math.round(words.left * scale), Math.round(words.top * scale),
                Math.round(words.right * scale), Math.round(words.bottom * scale));
        return detect(gray, w, h, anchor);
    }

    static DocumentFrame detect(int[] gray, int w, int h, Rect anchor) {
        Line left = edge(gray, w, h, true, false, anchor), right = edge(gray, w, h, true, true, anchor);
        Line top = edge(gray, w, h, false, false, anchor), bottom = edge(gray, w, h, false, true, anchor);
        if (left == null || right == null || top == null || bottom == null) return null;
        float[] p = new float[8];
        intersect(left, top, w, h, p, 0);
        intersect(right, top, w, h, p, 2);
        intersect(right, bottom, w, h, p, 4);
        intersect(left, bottom, w, h, p, 6);
        DocumentFrame result = new DocumentFrame(p);
        return result.coverage > .10f ? result : null;
    }

    private static final class Line {
        final float intercept, slope;
        Line(float c, float m) { intercept = c; slope = m; }
    }

    // A paper edge produces a sustained, same-polarity contrast along a line.
    // Sampling several inclinations also locates the corners of a tilted card.
    private static final int[] SEARCH_OFFSETS = {0, -1, 1, -2, 2, -3, 3};

    private static Line edge(int[] gray, int w, int h, boolean vertical, boolean far, Rect anchor) {
        int axis = vertical ? w : h, along = vertical ? h : w;
        int start = far ? axis / 2 : 1, stop = far ? axis - 1 : axis / 2;
        if (anchor != null) {
            int low = vertical ? anchor.left : anchor.top;
            int high = vertical ? anchor.right : anchor.bottom;
            // OCR can slightly overrun printed edges, especially on tilted pages.
            int slack = Math.round(axis * .04f);
            if (far) start = Math.max(start, high - slack);
            else stop = Math.min(stop, low + slack);
        }
        int step = vertical ? 1 : w;
        float best = 0;
        Line line = null;
        for (int center = start; center < stop; center++) {
            for (int angle = -16; angle <= 16; angle++) {
                float slope = angle * .022f;
                int positive = 0, negative = 0, support = 0;
                for (int k = 0; k < 32; k++) {
                    int t = Math.round(along * (.23f + .54f * k / 31));
                    int pos = Math.round(center + slope * (t - along * .5f));
                    if (pos < 1 || pos + 1 >= axis) continue;
                    int index = vertical ? t * w + pos : pos * w + t;
                    int delta = gray[index + step] - gray[index - step];
                    if (Math.abs(delta) < 12) continue;
                    support++;
                    if (delta > 0) positive += Math.min(70, delta);
                    else negative += Math.min(70, -delta);
                }
                if (support < 20) continue;
                float strength = Math.max(positive, negative) / 32f;
                // Prefer the perimeter when a table also has strong interior grid lines.
                float distance = Math.abs(center - axis * .5f) / axis;
                float score = Math.min(40f, strength) + .17f * strength + .55f * support + 40 * distance;
                if (strength >= 13 && score > best) {
                    best = score;
                    line = new Line(center - slope * along * .5f, slope);
                }
            }
        }
        if (line == null) return null;
        // Refine the winning line against its nearby edge pixels, avoiding
        // extrapolation error from the coarse angle search at the four corners.
        double sumT = 0, sumP = 0, sumTT = 0, sumTP = 0;
        int count = 0;
        for (int t = along / 5; t < 4 * along / 5; t += 2) {
            int prediction = Math.round(line.intercept + line.slope * t);
            int peak = 11, bestPos = prediction;
            for (int d : SEARCH_OFFSETS) {
                int pos = prediction + d;
                if (pos < 1 || pos >= axis - 1) continue;
                int index = vertical ? t * w + pos : pos * w + t;
                int gradient = Math.abs(gray[index + step] - gray[index - step]);
                if (gradient > peak) { peak = gradient; bestPos = pos; }
            }
            if (peak < 12) continue;
            sumT += t; sumP += bestPos; sumTT += t * t; sumTP += t * bestPos;
            count++;
        }
        double denominator = count * sumTT - sumT * sumT;
        if (count < 12 || denominator == 0) return line;
        float slope = (float)((count * sumTP - sumT * sumP) / denominator);
        return new Line((float)((sumP - slope * sumT) / count), slope);
    }

    private static void intersect(Line x, Line y, int w, int h, float[] out, int i) {
        float xx = (x.intercept + x.slope * y.intercept) / (1f - x.slope * y.slope);
        out[i] = xx / w;
        out[i + 1] = (y.intercept + y.slope * xx) / h;
    }

    boolean contains(float x, float y) {
        for (int i = 0; i < 8; i += 2) {
            int j = (i + 2) % 8;
            float cross = (corners[j] - corners[i]) * (y - corners[i+1]) -
                    (corners[j+1] - corners[i+1]) * (x - corners[i]);
            if (cross < 0) return false;
        }
        return true;
    }

    boolean closeTo(DocumentFrame previous) {
        if (previous == null) return false;
        for (int i = 0; i < 8; i++)
            if (Math.abs(corners[i] - previous.corners[i]) > .018f) return false;
        return true;
    }

    Bitmap crop(Bitmap source) {
        float[] src = corners.clone();
        for (int i = 0; i < 8; i += 2) {
            src[i] *= source.getWidth();
            src[i + 1] *= source.getHeight();
        }
        int w = Math.max(1, Math.round(Math.max(length(src, 0, 2), length(src, 6, 4))));
        int h = Math.max(1, Math.round(Math.max(length(src, 0, 6), length(src, 2, 4))));
        Matrix map = new Matrix();
        if (!map.setPolyToPoly(src, 0, new float[]{0,0, w,0, w,h, 0,h}, 0, 4))
            throw new IllegalArgumentException("無法裁切文件，請重新對準四角。");
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(source, map, new Paint(Paint.FILTER_BITMAP_FLAG));
        return result;
    }

    private static float length(float[] points, int a, int b) {
        return (float) Math.hypot(points[a] - points[b], points[a + 1] - points[b + 1]);
    }
}
