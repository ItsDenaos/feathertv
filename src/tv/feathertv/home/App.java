package tv.feathertv.home;

import android.content.ComponentName;
import android.graphics.Bitmap;

/** A launchable app, with its art already painted at tile size and its glow color. */
final class App {
    final String pkg;
    final ComponentName component;
    /** Found as a TV app (LEANBACK_LAUNCHER); otherwise a phone app the user chose to show. */
    final boolean leanback;
    final String label;
    /** Banner (or icon + name) painted once, off the main thread. */
    final Bitmap art;
    final int color;
    /** What the art was painted from (see Catalog.load), to reuse it while it's still valid. */
    final String key;

    App(String pkg, ComponentName component, boolean leanback, String label, Bitmap art, int color, String key) {
        this.pkg = pkg;
        this.component = component;
        this.leanback = leanback;
        this.label = label;
        this.art = art;
        this.color = color;
        this.key = key;
    }
}
