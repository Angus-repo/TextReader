package tw.codeviva.cardreader;

import android.graphics.Bitmap;
import android.graphics.Rect;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Locates the printed table, including on scans with margins or a small camera tilt. */
final class LicenseGrid {
    private static final double[] FRONT = {0, .267, .356, .535, .625, .832, .919, 1};
    private static final double[] BACK = {0, .153, .249, .447, .543, .638, .722, .815, .907, 1};

    private final double left, right, top, bottom, slope;
    private final int w, h;
    private final double score;

    private LicenseGrid(int w, int h, double left, double right, double top, double bottom, double slope, double score) {
        this.w = w; this.h = h; this.left = left; this.right = right;
        this.top = top; this.bottom = bottom; this.slope = slope; this.score = score;
    }

    double x(double pageX) { return (pageX * w - left) / (right - left); }
    double y(double pageX, double pageY) { return (pageY * h - slope * (pageX * w - w / 2.0) - top) / (bottom - top); }

    Rect region(Bitmap image, float l, float t, float r, float b) {
        double x1 = left + l * (right-left), x2 = left + r * (right-left);
        double tilt = slope * ((x1 + x2) / 2 - w / 2.0);
        Rect rect = new Rect((int)Math.round(x1 * image.getWidth()/w),
                (int)Math.round((top + t*(bottom-top) + tilt) * image.getHeight()/h),
                (int)Math.round(x2 * image.getWidth()/w),
                (int)Math.round((top + b*(bottom-top) + tilt) * image.getHeight()/h));
        if (!rect.intersect(0, 0, image.getWidth(), image.getHeight())) rect.setEmpty();
        return rect;
    }

    static LicenseGrid find(Bitmap image, boolean front) {
        int iw = image.getWidth(), ih = image.getHeight();
        float scale = Math.min(1, 420f / Math.max(iw, ih));
        int w = Math.round(iw * scale), h = Math.round(ih * scale);
        if (w < 80 || h < 65) return null;
        Bitmap small = Bitmap.createScaledBitmap(image, w, h, true);
        int[] gray = new int[w * h];
        small.getPixels(gray, 0, w, 0, 0, w, h);
        if (small != image) small.recycle();
        for (int i = 0; i < gray.length; i++) {
            int rgb = gray[i];
            gray[i] = (((rgb >> 16) & 255) + 2 * ((rgb >> 8) & 255) + (rgb & 255)) / 4;
        }
        LicenseGrid best = null;
        for (int angle = -4; angle <= 4; angle++) {
            double slope = angle * .018;
            List<Rule> rules = lines(gray, w, h, slope);
            LicenseGrid fit = fit(w, h, rules, slope, front ? FRONT : BACK);
            if (fit != null && (best == null || fit.score > best.score)) best = fit;
        }
        if (best == null) return null;
        double l = best.verticalEdge(gray, best.left), r = best.verticalEdge(gray, best.right);
        return new LicenseGrid(w,h,l,r,best.top,best.bottom,best.slope,best.score);
    }

    // Horizontal rules may be interrupted at a label or photo. The outer upright
    // lines span the header too, so use their midpoint to refine the two sides.
    private double verticalEdge(int[] gray, double estimate) {
        double bestX = estimate, bestSupport = .48;
        int radius = (int)Math.ceil((right-left)*.045);
        for (int x = Math.max(2,(int)estimate-radius); x <= Math.min(w-3,(int)estimate+radius); x++) {
            for (int k = -4; k <= 4; k++) {
                int hits = 0, n = 0;
                double mid = (top+bottom)/2;
                for (int y=(int)top+1; y<(int)bottom; y++) {
                    int sx = x + (int)Math.round((y-mid)*k*.012);
                    if (sx<2 || sx>=w-2) continue;
                    int i = y*w+sx;
                    if (gray[i]+26 < Math.max(gray[i-2],gray[i+2])) hits++;
                    n++;
                }
                double support = (double)hits/Math.max(1,n) - .035*Math.abs(x-estimate)/radius;
                if (support > bestSupport) { bestSupport=support; bestX=x; }
            }
        }
        return bestX;
    }

    private static final class Rule {
        final int y, left, right, ink;
        Rule(int y, int left, int right, int ink) {
            this.y = y; this.left = left; this.right = right; this.ink = ink;
        }
    }

    private static List<Rule> lines(int[] gray, int w, int h, double slope) {
        List<Rule> found = new ArrayList<>();
        int[] offset = new int[w];
        for (int x = 0; x < w; x++) offset[x] = (int)Math.round(slope * (x - w / 2.0));
        for (int y = 0; y < h; y++) {
            int start = 0, last = -w, ink = 0;
            int bestStart = 0, bestEnd = 0, bestInk = 0;
            for (int x = 0; x < w; x++) {
                int sy = y + offset[x];
                if (sy < 2 || sy >= h - 2) continue;
                int i = sy * w + x;
                if (gray[i] + 23 > Math.max(gray[i - 2*w], gray[i + 2*w])) continue;
                if (x - last > Math.max(4, w / 50)) { start = x; ink = 0; }
                ink++;
                last = x;
                if (ink > bestInk) { bestStart = start; bestEnd = x; bestInk = ink; }
            }
            if (bestInk <= w / 5) continue;
            Rule best = new Rule(y, bestStart, bestEnd, bestInk);
            int n = found.size();
            if (n == 0 || y - found.get(n-1).y > 3) found.add(best);
            else if (best.ink > found.get(n-1).ink) found.set(n-1, best);
        }
        return found;
    }

    private static LicenseGrid fit(int w, int h, List<Rule> rules, double slope, double[] layout) {
        LicenseGrid best = null;
        for (int a = 0; a < rules.size(); a++) for (int b = a + layout.length - 3; b < rules.size(); b++) {
            Rule first = rules.get(a), last = rules.get(b);
            int height = last.y - first.y;
            if (height < h * .24) continue;
            List<Rule> matches = new ArrayList<>();
            double quality = 0;
            int widest = 0;
            for (double fraction : layout) {
                double target = first.y + height * fraction;
                Rule nearest = null;
                for (int n = a; n <= b; n++) {
                    Rule r = rules.get(n);
                    if (Math.abs(r.y - target) <= Math.max(2, .019 * height) &&
                        (nearest == null || Math.abs(r.y - target) < Math.abs(nearest.y - target))) nearest = r;
                }
                if (nearest == null) continue;
                quality += (double)nearest.ink / w * (1 - Math.abs(nearest.y - target) / (.04 * height));
                matches.add(nearest);
                widest = Math.max(widest, nearest.right - nearest.left);
            }
            if (matches.size() < layout.length - 1 || widest < height * 1.12 || widest > height * 2.12) continue;
            if (best != null && quality <= best.score) continue;
            List<Integer> left = new ArrayList<>(), right = new ArrayList<>();
            // Glare can split a rule partway across a cell. Each outer edge only needs
            // support on two different rows; the same row need not contain both edges.
            for (Rule line : matches) {
                left.add(line.left); right.add(line.right);
            }
            Collections.sort(left); Collections.sort(right);
            int l = left.get(1), r = right.get(right.size()-2);
            if (r-l < height*1.12 || r-l > height*2.12) continue;
            best = new LicenseGrid(w, h, l, r, first.y, last.y, slope, quality);
        }
        return best;
    }
}
