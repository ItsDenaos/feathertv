package tv.feathertv.home;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * FeatherTV's options, driven by the remote: each row changes its value with OK or left/right
 * and is saved immediately. The home screen applies the changes when you return.
 */
public final class Options extends Activity {
    private static final String[] LANGUAGES = {"auto", "es", "en", "pt", "fr", "it", "de"};
    private static final String[] LANGUAGE_NAMES = {null, "Español", "English", "Português", "Français", "Italiano", "Deutsch"};
    private static final String[] CLOCKS = {"auto", "12h", "24h"};
    private static final String[] BACKGROUNDS = {"stage", "black"};
    private static final int BRAND = 0xFF5B3FE0;

    private Config config;
    private int screenW;
    private int screenH;
    private float text;
    private TextView heading;
    private LinearLayout list;
    private boolean inHidden;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        config = new Config(base);
        config.refresh();
        Configuration o = Config.languageOverride(config.get("language", "auto"));
        if (o != null) applyOverrideConfiguration(o);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DisplayMetrics m = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        screenW = Math.max(m.widthPixels, m.heightPixels);
        screenH = Math.min(m.widthPixels, m.heightPixels);
        text = screenH * 0.034f;

        // No background on the window or the view: the stage is opaque and covers the screen.
        getWindow().setBackgroundDrawable(null);
        FrameLayout root = new FrameLayout(this);
        Stage stage = new Stage(this);
        stage.setColor(BRAND, false);
        root.addView(stage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        int side = (int) (screenW * 0.05f);
        heading = Ui.text(this, screenH * 0.075f, Color.WHITE);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        hp.leftMargin = side;
        hp.topMargin = (int) (screenH * 0.08f);
        root.addView(heading, hp);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        int vpad = (int) (text * 0.5f);
        scroll.setPadding(0, vpad, 0, vpad);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (screenW * 0.55f),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP | Gravity.START);
        lp.leftMargin = side - (int) (text * 0.9f);
        lp.topMargin = (int) (screenH * 0.2f);
        lp.bottomMargin = (int) (screenH * 0.06f);
        root.addView(scroll, lp);

        setContentView(root);
        showMain(0);
    }

    @Override
    public void onBackPressed() {
        if (inHidden) showMain(5);
        else super.onBackPressed();
    }

    // ---------------------------------------------------------------- main list

    private void showMain(int focus) {
        inHidden = false;
        heading.setText(R.string.options_title);
        list.removeAllViews();
        String[] accentNames = getResources().getStringArray(R.array.accent_names);
        String[] sizeNames = getResources().getStringArray(R.array.tile_size_names);
        String auto = getString(R.string.val_auto);

        addCycle(R.string.opt_language, "language", "auto", LANGUAGES, names(LANGUAGE_NAMES, auto));
        addCycle(R.string.opt_accent, "accent", "white", Config.ACCENTS, accentNames);
        addCycle(R.string.opt_tiles, "tiles", "medium", Config.TILE_SIZES, sizeNames);
        addCycle(R.string.opt_clock, "clock", "auto", CLOCKS,
                new String[] {auto, getString(R.string.val_12h), getString(R.string.val_24h)});
        addCycle(R.string.opt_background, "background", "stage", BACKGROUNDS,
                new String[] {getString(R.string.val_stage), getString(R.string.val_black)});
        addAction(getString(R.string.opt_hidden), String.valueOf(config.list("apps.hide").size()), this::showHidden);
        addAction(getString(R.string.opt_reset), "", () -> {
            config.set("apps.order", null);
            config.removePrefix("name.");
            config.save();
            Toast.makeText(this, R.string.reset_done, Toast.LENGTH_SHORT).show();
        });
        addAction(getString(R.string.opt_about), getString(R.string.about_value, version()), null);
        focusRow(focus);
    }

    private static String[] names(String[] base, String auto) {
        String[] out = base.clone();
        out[0] = auto;
        return out;
    }

    /** Row that cycles through a list of values; OK or right moves forward, left moves back. */
    private void addCycle(int label, final String key, String def, final String[] values, final String[] shown) {
        final int index = list.getChildCount();
        int current = 0;
        String v = config.get(key, def);
        for (int i = 0; i < values.length; i++) if (values[i].equalsIgnoreCase(v)) current = i;
        final int cur = current;
        View row = addRow(getString(label), shown[cur], () -> step(key, values, cur, 1, index));
        row.setOnKeyListener((x, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                step(key, values, cur, code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1, index);
                return true;
            }
            return false;
        });
    }

    private void step(String key, String[] values, int current, int dir, int index) {
        String next = values[(current + dir + values.length) % values.length];
        config.set(key, next);
        config.save();
        if ("language".equals(key)) recreate();
        else showMain(index);
    }

    private void addAction(String label, String value, Runnable action) {
        View row = addRow(label, value, action);
        row.setOnKeyListener((x, code, e) -> e.getAction() == KeyEvent.ACTION_DOWN
                && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT));
    }

    private View addRow(String label, String value, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        int ph = (int) (text * 0.9f), pv = (int) (text * 0.55f);
        row.setPadding(ph, pv, ph, pv);
        TextView name = Ui.text(this, text, Ui.TEXT_DIM);
        name.setText(label);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = Ui.text(this, text, Ui.TEXT_DIM);
        val.setText(value);
        val.setSingleLine(true);
        row.addView(val);
        Ui.pill(row, text * 2, config.accent(), name, val);
        if (action != null) row.setOnClickListener(x -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (text * 0.15f);
        list.addView(row, lp);
        return row;
    }

    private void focusRow(int i) {
        if (list.getChildCount() == 0) return;
        list.getChildAt(Math.max(0, Math.min(i, list.getChildCount() - 1))).requestFocus();
    }

    // ---------------------------------------------------------------- hidden apps

    private void showHidden() {
        inHidden = true;
        heading.setText(R.string.opt_hidden);
        list.removeAllViews();
        final List<String> hidden = config.list("apps.hide");
        TextView info = Ui.text(this, text * 0.8f, 0x99FFFFFF);
        info.setText(hidden.isEmpty() ? R.string.hidden_none : R.string.hidden_restore_hint);
        int ph = (int) (text * 0.9f);
        info.setPadding(ph, 0, ph, (int) (text * 0.6f));
        list.addView(info);
        PackageManager pm = getPackageManager();
        for (final String pkg : hidden) {
            String label = pkg;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                label = String.valueOf(pm.getApplicationLabel(ai));
            } catch (PackageManager.NameNotFoundException ignored) {
                // No longer installed: the package name is shown.
            }
            addAction(label, "", () -> {
                List<String> now = config.list("apps.hide");
                now.remove(pkg);
                config.setList("apps.hide", now);
                config.save();
                int i = list.indexOfChild(getCurrentFocus());
                showHidden();
                focusRow(i);
            });
        }
        focusRow(1);
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }
}
