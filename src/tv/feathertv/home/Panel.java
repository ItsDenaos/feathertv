package tv.feathertv.home;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Side menu on the right, over the home screen. Focus stays inside the menu while it is open;
 * Back or Menu close it and return the focus to where it was.
 */
final class Panel extends FrameLayout {
    private final LinearLayout column;
    private final float textSize;
    private View returnFocus;
    private boolean showing;

    Panel(Context c, int screenW, int screenH) {
        super(c);
        textSize = screenH * 0.034f;
        setBackgroundColor(0x99000000);
        setVisibility(GONE);
        setClickable(true);
        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2141418);
        bg.setCornerRadius(screenH * 0.02f);
        column.setBackground(bg);
        int pad = (int) (screenH * 0.035f);
        column.setPadding(pad, pad, pad, pad);
        // Top right, above the app row, so the app being edited stays visible.
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (screenW * 0.3f),
                LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
        lp.rightMargin = (int) (screenW * 0.05f);
        lp.topMargin = (int) (screenH * 0.13f);
        addView(column, lp);
    }

    boolean isShowing() {
        return showing;
    }

    void show(CharSequence title, int accent, View back, CharSequence[] labels, Runnable[] actions) {
        returnFocus = back;
        column.removeAllViews();
        TextView head = Ui.text(getContext(), textSize * 0.8f, 0x80FFFFFF);
        head.setText(title);
        head.setSingleLine(true);
        head.setEllipsize(TextUtils.TruncateAt.END);
        int hp = (int) (textSize * 0.8f);
        head.setPadding(hp, 0, hp, (int) (textSize * 0.5f));
        column.addView(head);

        TextView first = null;
        for (int i = 0; i < labels.length; i++) {
            final TextView item = Ui.text(getContext(), textSize, Ui.TEXT_DIM);
            item.setText(labels[i]);
            item.setSingleLine(true);
            item.setEllipsize(TextUtils.TruncateAt.END);
            item.setFocusable(true);
            item.setClickable(true);
            int ph = (int) (textSize * 0.8f), pv = (int) (textSize * 0.45f);
            item.setPadding(ph, pv, ph, pv);
            Ui.pill(item, textSize * 2, accent, item);
            final Runnable action = actions[i];
            item.setOnClickListener(v -> {
                hide();
                action.run();
            });
            final boolean isFirst = i == 0, isLast = i == labels.length - 1;
            item.setOnKeyListener((v, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        return isFirst;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        return isLast;
                    default:
                        return false;
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (textSize * 0.15f);
            column.addView(item, lp);
            if (first == null) first = item;
        }

        showing = true;
        setVisibility(VISIBLE);
        setAlpha(0f);
        column.setTranslationX(textSize * 2);
        animate().alpha(1f).setDuration(150).start();
        column.animate().translationX(0f).setDuration(180).setInterpolator(Tile.EASE).start();
        if (first != null) first.requestFocus();
    }

    void hide() {
        if (!showing) return;
        showing = false;
        setVisibility(GONE);
        if (returnFocus != null && returnFocus.isAttachedToWindow()) returnFocus.requestFocus();
        returnFocus = null;
    }
}
