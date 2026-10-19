package tv.feathertv.home;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

/** Interface pieces shared by the home screen, the menus and Options. */
final class Ui {
    static final int TEXT_DIM = 0xB3FFFFFF;

    private Ui() { }

    static TextView text(Context c, float px, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Text color that reads well on the accent color. */
    static int onAccent(int accent) {
        double l = (0.299 * Color.red(accent) + 0.587 * Color.green(accent) + 0.114 * Color.blue(accent)) / 255;
        return l > 0.6 ? Color.BLACK : Color.WHITE;
    }

    /**
     * "Pill" style: without focus, transparent with dimmed text; with focus, filled with the
     * accent color and contrasting text. Also tints the text views' icons.
     */
    static void pill(View v, float radius, final int accent, final TextView... labels) {
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radius);
        v.setBackground(bg);
        View.OnFocusChangeListener l = (x, has) -> {
            bg.setColor(has ? accent : Color.TRANSPARENT);
            int fg = has ? onAccent(accent) : TEXT_DIM;
            for (TextView t : labels) {
                t.setTextColor(fg);
                for (Drawable d : t.getCompoundDrawables()) if (d != null) d.setTint(fg);
            }
        };
        v.setOnFocusChangeListener(l);
        l.onFocusChange(v, v.isFocused());
    }
}
