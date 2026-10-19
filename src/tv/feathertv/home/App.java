package tv.feathertv.home;

import android.content.ComponentName;
import android.graphics.Bitmap;

/** A launchable app, with its art already painted at tile size and its glow color. */
final class App {
    final String pkg;
    final ComponentName component;
    final String label;
    /** Banner (or icon + name) painted once, off the main thread. */
    final Bitmap art;
    final int color;

    App(String pkg, ComponentName component, String label, Bitmap art, int color) {
        this.pkg = pkg;
        this.component = component;
        this.label = label;
        this.art = art;
        this.color = color;
    }
}
