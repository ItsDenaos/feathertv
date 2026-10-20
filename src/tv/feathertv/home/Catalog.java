package tv.feathertv.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextPaint;
import android.text.TextUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the installed apps and paints each tile's art. Runs off the main thread: the UI
 * thread only receives bitmaps that are ready to draw.
 */
final class Catalog {
    /** Neutral glow for apps without a clear color (black and white, greys). */
    static final int NEUTRAL = 0xFF8A8A99;
    private static final int PLAIN_BG = 0xFF26262C;

    private Catalog() { }

    /**
     * @param known the apps loaded last time, by package: an app whose activity, APK, name and
     *              tile size haven't changed keeps its art instead of being painted again (an
     *              install, an update, a rename or a hidden app would otherwise repaint them all).
     */
    static List<App> load(Context c, List<String> order, Set<String> hide, Set<String> show,
                          Map<String, String> names, int tileW, int tileH, Map<String, App> known) {
        PackageManager pm = c.getPackageManager();
        Map<String, ResolveInfo> found = new LinkedHashMap<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(tv, 0)) add(found, ri, c, hide);
        int tvCount = found.size();
        if (!show.isEmpty()) {
            // Phone apps (without a TV banner) that the user asked to show.
            Intent phone = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(phone, 0)) {
                if (show.contains(ri.activityInfo.packageName)) add(found, ri, c, hide);
            }
        }

        List<App> apps = new ArrayList<>(found.size());
        int i = 0;
        for (ResolveInfo ri : found.values()) {
            boolean leanback = i++ < tvCount;
            String pkg = ri.activityInfo.packageName;
            String label = names.get(pkg);
            if (label == null) label = String.valueOf(ri.loadLabel(pm));
            // The APK's path changes with every update.
            String key = ri.activityInfo.name + '|' + ri.activityInfo.applicationInfo.sourceDir + '|'
                    + label + '|' + tileW + 'x' + tileH;
            App old = known.get(pkg);
            if (old != null && old.key.equals(key)) {
                apps.add(old);
                continue;
            }
            Drawable banner = ri.activityInfo.loadBanner(pm);
            Bitmap art = banner != null ? paintBanner(banner, tileW, tileH)
                    : paintIcon(ri.loadIcon(pm), label, tileW, tileH);
            int color = dominantColor(art);
            apps.add(new App(pkg, new ComponentName(pkg, ri.activityInfo.name), leanback, label,
                    toGpu(art), color, key));
        }
        return sort(apps, order);
    }

    private static void add(Map<String, ResolveInfo> found, ResolveInfo ri, Context c, Set<String> hide) {
        String pkg = ri.activityInfo.packageName;
        if (pkg.equals(c.getPackageName()) || hide.contains(pkg) || found.containsKey(pkg)) return;
        found.put(pkg, ri);
    }

    /** The user's chosen order first; the rest in alphabetical order. */
    private static List<App> sort(List<App> apps, List<String> order) {
        final Collator collator = Collator.getInstance();
        List<App> rest = new ArrayList<>(apps);
        List<App> out = new ArrayList<>(apps.size());
        for (String pkg : order) {
            for (int i = 0; i < rest.size(); i++) {
                if (rest.get(i).pkg.equals(pkg)) {
                    out.add(rest.remove(i));
                    break;
                }
            }
        }
        Collections.sort(rest, (a, b) -> collator.compare(a.label, b.label));
        out.addAll(rest);
        return out;
    }

    private static Bitmap paintBanner(Drawable banner, int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        banner.setBounds(0, 0, w, h);
        banner.draw(new Canvas(bmp));
        return bmp;
    }

    /** Apps without a TV banner: the icon centered on dark grey with the name below. */
    private static Bitmap paintIcon(Drawable icon, String label, int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(PLAIN_BG);
        if (icon != null) {
            int s = (int) (h * 0.46f), top = (int) (h * 0.14f);
            icon.setBounds((w - s) / 2, top, (w + s) / 2, top + s);
            icon.draw(c);
        }
        TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(h * 0.14f);
        CharSequence shown = TextUtils.ellipsize(label, text, w * 0.9f, TextUtils.TruncateAt.END);
        c.drawText(shown, 0, shown.length(), w / 2f, h * 0.88f, text);
        return bmp;
    }

    /**
     * Android 9 and later: moves the art to graphics memory once, here in the background. Otherwise
     * every return to the home screen uploads it to the GPU again (Android frees the textures of a
     * window that is no longer visible), and so does the first scroll over each tile.
     */
    private static Bitmap toGpu(Bitmap art) {
        if (Build.VERSION.SDK_INT < 28) return art;
        try {
            Bitmap hw = art.copy(Bitmap.Config.HARDWARE, false);
            if (hw == null) return art;
            art.recycle();
            return hw;
        } catch (RuntimeException e) {
            return art; // Some devices refuse it: the art stays in normal memory.
        }
    }

    /**
     * Dominant color of the art: a 12-hue histogram over a thumbnail, ignoring blacks,
     * whites and greys. Returns a color bright enough for the glow.
     */
    static int dominantColor(Bitmap art) {
        final int w = 32, h = 18;
        Bitmap small = Bitmap.createScaledBitmap(art, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != art) small.recycle();

        float[] hsv = new float[3];
        double[] weight = new double[12], r = new double[12], g = new double[12], b = new double[12];
        for (int p : px) {
            if (Color.alpha(p) < 128) continue;
            Color.colorToHSV(p, hsv);
            if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue;
            int bin = Math.min(11, (int) (hsv[0] / 30f));
            double k = hsv[1] * hsv[2];
            weight[bin] += k;
            r[bin] += Color.red(p) * k;
            g[bin] += Color.green(p) * k;
            b[bin] += Color.blue(p) * k;
        }
        int best = 0;
        for (int i = 1; i < 12; i++) if (weight[i] > weight[best]) best = i;
        // Less than 4 % of the image has color: treat the app as having no color of its own.
        if (weight[best] < px.length * 0.04) return NEUTRAL;
        int c = Color.rgb((int) (r[best] / weight[best]), (int) (g[best] / weight[best]),
                (int) (b[best] / weight[best]));
        Color.colorToHSV(c, hsv);
        hsv[2] = Math.max(hsv[2], 0.7f);
        return Color.HSVToColor(hsv);
    }
}
