package tv.feathertv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** FeatherTV's home screen ("Stage" design, see docs/DESIGN.md). */
public final class Home extends Activity {
    /**
     * Loads the apps at nice 5: below the interface's threads (0, and -10 while in front), so it
     * never takes CPU from a frame being drawn, but outside Android's background group, which can
     * starve a thread while the system is starting up.
     */
    private static final ExecutorService BG = Executors.newSingleThreadExecutor(r -> new Thread(() -> {
        Process.setThreadPriority(5);
        r.run();
    }, "FeatherTV-catalog"));
    private static final String LEANBACK_SETTINGS = "android.intent.category.LEANBACK_SETTINGS";
    private static final boolean FIRE_OS = "Amazon".equalsIgnoreCase(Build.MANUFACTURER);

    /** When the home screen stopped being in front, and whether it was to open something from it. */
    private static volatile long pausedAt = -1;
    private static volatile boolean leftForApp;
    private static volatile boolean resumed;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Config config;
    private Stage stage;
    private TextView greeting;
    private TextView title;
    private TextView settings;
    private TextView brand;
    /** The TV's inputs button: only on TVs that have inputs (HDMI...). */
    private TextView inputs;
    private List<Inputs.Input> inputList = new ArrayList<>();
    private TextView hint;
    private Row scroller;
    private LinearLayout row;
    private Panel panel;
    private String focusedPkg;
    /** The apps on screen, by package: the next load reuses the art of the ones that didn't change. */
    private Map<String, App> known = new HashMap<>();
    private int generation;
    private int screenW;
    private int screenH;
    private java.text.DateFormat timeFormat;
    // Settings the screen was built with; if they change, the screen has to be recreated.
    private String language;
    private float tileFraction;
    private int accent;
    /** The tile being moved, or null. */
    private Tile moving;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            ui.postDelayed(this, 60_000 - now % 60_000 + 50);
        }
    };

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            loadApps();
        }
    };

    /** Time or time zone changed (e.g. syncing after startup) or the 12/24-hour format changed. */
    private final BroadcastReceiver time = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            timeFormat = null;
            updateClock();
        }
    };

    /**
     * Language: "auto" follows the system; any other value (en, es, pt, fr, it, de...) applies to
     * FeatherTV only. It's read here because it must be set before resources are loaded.
     */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        config = new Config(base);
        config.refresh();
        language = config.get("language", "auto");
        Configuration o = Config.languageOverride(language);
        if (o != null) applyOverrideConfiguration(o);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DisplayMetrics m = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        screenW = Math.max(m.widthPixels, m.heightPixels);
        screenH = Math.min(m.widthPixels, m.heightPixels);
        tileFraction = config.tileFraction();
        // Without a window background Android makes the window translucent. The stage (or the
        // black background) covers every pixel, so it's declared opaque: SurfaceFlinger then
        // doesn't blend it and skips whatever is underneath, such as Amazon's launcher.
        getWindow().setFormat(PixelFormat.OPAQUE);
        setContentView(buildLayout());
        applyLook();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        IntentFilter t = new IntentFilter(Intent.ACTION_TIME_CHANGED);
        t.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(time, t);
        loadApps();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        unregisterReceiver(time);
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // System language changed: the activity isn't recreated, the texts are updated instead.
        timeFormat = null;
        settings.setText(R.string.settings);
        inputs.setText(R.string.inputs);
        updateClock();
        updateHint();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ui.removeCallbacks(tick);
        tick.run();
    }

    @Override
    protected void onStop() {
        ui.removeCallbacks(tick);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        leftForApp = false;
        // If the window already had focus, onWindowFocusChanged won't come: lift the curtain here.
        if (hasWindowFocus()) Guard.homeShown();
        if (config.refresh()) {
            if (!language.equalsIgnoreCase(config.get("language", "auto"))
                    || tileFraction != config.tileFraction()) {
                recreate();
                return;
            }
            applyLook();
            loadApps();
        }
        updateHint();
    }

    @Override
    protected void onPause() {
        resumed = false;
        pausedAt = SystemClock.uptimeMillis();
        panel.hide();
        if (moving != null) stopMoving();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Guard.homeShown();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already on the home screen: go back to the first app. On Fire OS that
        // arrives through Guard (EXTRA_RESET); on Android TV, where FeatherTV is the real home
        // screen, as a HOME intent right after Android paused this activity to deliver it.
        boolean reset = intent.getBooleanExtra(Guard.EXTRA_RESET, false)
                || (intent.hasCategory(Intent.CATEGORY_HOME) && wasFrontJustNow());
        if (reset && row.getChildCount() > 0) {
            scroller.smoothScrollTo(0, 0);
            row.getChildAt(0).requestFocus();
        }
        if (hasWindowFocus()) Guard.homeShown();
    }

    /** True while the home screen is in front. */
    static boolean isFront() {
        return resumed;
    }

    /**
     * True if the home screen was in front a moment ago (Home was pressed on it). The window is
     * 5 s because, right after a restart, Amazon's launcher can take 2-3 s to appear before Guard
     * reacts; opening an app from FeatherTV sets leftForApp, so that case never counts.
     */
    static boolean wasFrontJustNow() {
        return !leftForApp && pausedAt >= 0 && SystemClock.uptimeMillis() - pausedAt < 5000;
    }

    @Override
    public void onBackPressed() {
        // Back never closes the home screen; it only closes the menu if it is open.
        panel.hide();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (moving != null && handleMoveKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) {
            if (panel.isShowing()) {
                panel.hide();
            } else {
                View f = getCurrentFocus();
                if (f instanceof Tile) openAppMenu((Tile) f);
                else if (f == settings || f == brand || f == inputs) openOptions();
            }
            return true;
        }
        return super.onKeyDown(code, e);
    }

    // ---------------------------------------------------------------- interface

    private View buildLayout() {
        // No background: the stage already paints the whole screen (see applyLook).
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);

        stage = new Stage(this);
        root.addView(stage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        float small = screenH * 0.034f;
        int side = (int) (screenW * 0.05f);

        greeting = Ui.text(this, small, Ui.TEXT_DIM);
        root.addView(greeting, at(side, (int) (screenH * 0.065f), Gravity.TOP | Gravity.START));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // The brand opens FeatherTV's options; after the divider, the TV's own: its inputs (only
        // when it has any) and Settings.
        brand = barButton(small, R.string.app_name, R.drawable.ic_feather, this::openOptions);
        brand.setContentDescription(getString(R.string.menu_options));
        bar.addView(brand);
        View sep = new View(this);
        sep.setBackgroundColor(0x40FFFFFF);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Math.max(1, screenH / 600), (int) small);
        sp.leftMargin = sp.rightMargin = (int) (small * 0.3f);
        bar.addView(sep, sp);
        inputs = barButton(small, R.string.inputs, R.drawable.ic_input, this::openInputs);
        inputs.setVisibility(View.GONE);
        bar.addView(inputs);
        settings = barButton(small, R.string.settings, R.drawable.ic_settings, this::openSettings);
        bar.addView(settings);
        // ← and → move between the buttons; ↓ goes back to the app row.
        barKeys(brand, inputs, settings);
        FrameLayout.LayoutParams bp = at(0, (int) (screenH * 0.055f), Gravity.TOP | Gravity.END);
        bp.rightMargin = side;
        root.addView(bar, bp);

        title = Ui.text(this, screenH * 0.105f, Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setAlpha(0f);
        // Fixed width: changing the name only repaints the title instead of laying out the whole screen.
        FrameLayout.LayoutParams tp = at(side, (int) (screenH * 0.26f), Gravity.TOP | Gravity.START);
        tp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        tp.rightMargin = side;
        root.addView(title, tp);

        int overhang = Tile.ringOverhang(ringWidth());
        int pad = (int) (tileHeight() * (Tile.FOCUS_SCALE - 1f) / 2) + overhang;
        scroller = new Row(this);
        scroller.setPadding(side, pad, side, pad);
        // Margin = side gutter + how much the tile grows when scaled + the outer ring.
        scroller.setMargin(side + (int) (tileWidth() * (Tile.FOCUS_SCALE - 1f) / 2) + overhang);
        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        scroller.addView(row);
        // The title and the row fade in once the apps are loaded.
        scroller.setAlpha(0f);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        rp.topMargin = (int) (screenH * 0.64f) - pad;
        root.addView(scroller, rp);

        hint = Ui.text(this, screenH * 0.026f, 0x99FFFFFF);
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hp = at(side, 0, Gravity.BOTTOM | Gravity.START);
        hp.bottomMargin = (int) (screenH * 0.06f);
        root.addView(hint, hp);

        panel = new Panel(this, screenW, screenH);
        root.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    /** Pill-shaped button for the top bar (text with an icon). */
    private TextView barButton(float size, int text, int iconRes, final Runnable action) {
        final TextView b = Ui.text(this, size, Ui.TEXT_DIM);
        b.setId(View.generateViewId());
        b.setText(text);
        // Becomes focusable once there are apps, so it doesn't take the focus at startup.
        b.setFocusable(false);
        b.setClickable(true);
        b.setCompoundDrawables(icon(iconRes, size, Ui.TEXT_DIM), null, null, null);
        b.setCompoundDrawablePadding((int) (size * 0.4f));
        int ph = (int) (size * 0.9f), pv = (int) (size * 0.35f);
        b.setPadding(ph, pv, ph, pv);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    /** Keys on the top bar: ← → to the next visible button (or nothing), ↓ to the row, ↑ nothing. */
    private void barKeys(final View... buttons) {
        for (int i = 0; i < buttons.length; i++) {
            final int at = i;
            buttons[i].setOnKeyListener((v, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        focusApp(focusedPkg);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        int step = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                        for (int j = at + step; j >= 0 && j < buttons.length; j += step) {
                            if (buttons[j].getVisibility() == View.VISIBLE) {
                                buttons[j].requestFocus();
                                break;
                            }
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        return true;
                    default:
                        return false;
                }
            });
        }
    }

    /** Applies the settings that don't need the screen to be recreated: color, background and clock. */
    private void applyLook() {
        accent = config.accent();
        Ui.pill(brand, screenH * 0.068f, accent, brand);
        Ui.pill(inputs, screenH * 0.068f, accent, inputs);
        Ui.pill(settings, screenH * 0.068f, accent, settings);
        boolean black = "black".equalsIgnoreCase(config.get("background", "stage"));
        stage.setVisibility(black ? View.GONE : View.VISIBLE);
        // A single full-screen layer: either the stage or the window's black background.
        getWindow().setBackgroundDrawable(black ? new ColorDrawable(Color.BLACK) : null);
        timeFormat = null;
        updateClock();
    }

    private void showApps(List<App> apps) {
        boolean first = row.getChildCount() == 0;
        boolean appHadFocus = row.hasFocus();
        known = new HashMap<>();
        for (App a : apps) known.put(a.pkg, a);
        row.removeAllViews();
        float radius = screenH * 0.015f, ring = ringWidth();
        for (int i = 0; i < apps.size(); i++) {
            final App a = apps.get(i);
            final Tile t = new Tile(this, a, radius, ring, accent);
            t.setNextFocusUpId(settings.getId());
            t.setOnClickListener(v -> launch(a));
            t.setOnLongClickListener(v -> {
                openAppMenu(t);
                return true;
            });
            t.setOnFocusChangeListener((v, has) -> {
                if (has) onAppFocused(a);
            });
            row.addView(t, new LinearLayout.LayoutParams(tileWidth(), tileHeight()));
        }
        fixMargins();
        settings.setFocusable(true);
        inputs.setFocusable(true);
        brand.setFocusable(true);
        if (first || appHadFocus || !(settings.isFocused() || brand.isFocused())) focusApp(focusedPkg);
        if (first) {
            title.animate().alpha(1f).setDuration(220).start();
            scroller.animate().alpha(1f).setDuration(220).start();
        }
    }

    /** Spacing between tiles; the last one has none. */
    private void fixMargins() {
        int gap = (int) (screenW * 0.022f), n = row.getChildCount();
        for (int i = 0; i < n; i++) {
            View v = row.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
            int m = i < n - 1 ? gap : 0;
            if (lp.rightMargin != m) {
                lp.rightMargin = m;
                v.setLayoutParams(lp);
            }
        }
    }

    private void onAppFocused(App a) {
        focusedPkg = a.pkg;
        boolean visible = scroller.getAlpha() > 0f;
        stage.setColor(a.color, visible);
        if (a.label.contentEquals(title.getText())) return;
        title.animate().cancel();
        title.setText(a.label);
        if (visible) {
            // The name slides in slightly, like the rest of the interface.
            title.setAlpha(0.3f);
            title.setTranslationX(title.getTextSize() * 0.12f);
            title.animate().alpha(1f).translationX(0f).setDuration(180)
                    .setInterpolator(Tile.EASE).start();
        }
    }

    /** Focuses the given app, or the first one if it's gone. With no apps, focuses Settings. */
    private void focusApp(String pkg) {
        View target = row.getChildCount() > 0 ? row.getChildAt(0) : settings;
        for (int i = 0; i < row.getChildCount(); i++) {
            Tile t = (Tile) row.getChildAt(i);
            if (t.app.pkg.equals(pkg)) {
                target = t;
                break;
            }
        }
        target.requestFocus();
    }

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        int g = h >= 5 && h < 12 ? R.string.good_morning
                : h >= 12 && h < 20 ? R.string.good_afternoon : R.string.good_evening;
        if (timeFormat == null) {
            String clock = config.get("clock", "auto");
            Locale locale = getResources().getConfiguration().locale;
            if ("12h".equalsIgnoreCase(clock) || "24h".equalsIgnoreCase(clock)) {
                String skeleton = "12h".equalsIgnoreCase(clock) ? "hm" : "Hm";
                timeFormat = new SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale);
            } else {
                timeFormat = DateFormat.getTimeFormat(this);
            }
        }
        String text = getString(g) + "  ·  " + timeFormat.format(now.getTime());
        // Same text (coming back within the same minute): setText would lay out the whole screen again.
        if (!text.contentEquals(greeting.getText())) greeting.setText(text);
    }

    private void updateHint() {
        if (moving != null) {
            hint.setText(R.string.move_hint);
            hint.setVisibility(View.VISIBLE);
        } else if (FIRE_OS && !Guard.isConnected() && !Guard.isEnabled(this)) {
            hint.setText(R.string.guard_off);
            hint.setVisibility(View.VISIBLE);
        } else {
            hint.setVisibility(View.GONE);
        }
    }

    // ---------------------------------------------------------------- per-app menu

    private void openAppMenu(final Tile t) {
        CharSequence[] labels = {
                getString(R.string.menu_move), getString(R.string.menu_hide),
                getString(R.string.menu_rename), getString(R.string.menu_info),
                getString(R.string.menu_options)};
        Runnable[] actions = {
                () -> startMoving(t), () -> hideApp(t), () -> renameApp(t), () -> appInfo(t),
                this::openOptions};
        panel.show(t.app.label, accent, t, labels, actions);
    }

    private void startMoving(Tile t) {
        moving = t;
        t.requestFocus();
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (v != t) v.animate().alpha(0.35f).setDuration(150).start();
        }
        updateHint();
    }

    /** In move mode: left/right change the position; OK, Back or Menu finish. */
    private boolean handleMoveKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shift(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                if (down && e.getRepeatCount() == 0) stopMoving();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            default:
                return false;
        }
    }

    private void shift(int dir) {
        int from = row.indexOfChild(moving), to = from + dir;
        if (from < 0 || to < 0 || to >= row.getChildCount()) return;
        // The neighbour moves instead of the focused tile, so focus isn't lost.
        View neighbour = row.getChildAt(to);
        row.removeView(neighbour);
        row.addView(neighbour, from);
        fixMargins();
        scroller.post(() -> {
            if (moving != null) scroller.reveal(moving);
        });
    }

    private void stopMoving() {
        if (moving == null) return;
        moving = null;
        List<String> order = new ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) {
            Tile t = (Tile) row.getChildAt(i);
            t.animate().alpha(1f).setDuration(150).start();
            order.add(t.app.pkg);
        }
        config.setList("apps.order", order);
        config.save();
        updateHint();
    }

    private void hideApp(Tile t) {
        List<String> hidden = config.list("apps.hide");
        if (!hidden.contains(t.app.pkg)) hidden.add(t.app.pkg);
        config.setList("apps.hide", hidden);
        config.save();
        int i = row.indexOfChild(t);
        row.removeView(t);
        fixMargins();
        if (row.getChildCount() > 0) row.getChildAt(Math.min(i, row.getChildCount() - 1)).requestFocus();
        else settings.requestFocus();
        Toast.makeText(this, getString(R.string.hidden_toast, t.app.label), Toast.LENGTH_LONG).show();
    }

    private void renameApp(final Tile t) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(t.app.label);
        input.setSelectAllOnFocus(true);
        input.setHint(R.string.rename_hint);
        int pad = (int) (screenH * 0.03f);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(R.string.rename_title, t.app.label))
                .setView(box)
                .setPositiveButton(android.R.string.ok, (dlg, w) -> {
                    config.set("name." + t.app.pkg, input.getText().toString());
                    config.save();
                    loadApps();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        d.show();
    }

    private void appInfo(Tile t) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", t.app.pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        leftForApp = true;
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            leftForApp = false;
            Toast.makeText(this, R.string.info_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void openOptions() {
        leftForApp = true;
        startActivity(new Intent(this, Options.class));
    }

    // ---------------------------------------------------------------- actions

    private void loadApps() {
        final List<String> order = config.list("apps.order");
        final Set<String> hide = new HashSet<>(config.list("apps.hide"));
        final Set<String> show = new HashSet<>(config.list("apps.show"));
        final Map<String, String> names = config.prefixed("name.");
        final Map<String, App> previous = new HashMap<>(known);
        final Context app = getApplicationContext();
        final int gen = ++generation, w = tileWidth(), h = tileHeight();
        BG.execute(() -> {
            final List<App> apps = Catalog.load(app, order, hide, show, names, w, h, previous);
            // Reloaded with the apps: a package change can also add or remove an input.
            final List<Inputs.Input> ins = Inputs.load(app);
            ui.post(() -> {
                if (gen != generation || isDestroyed() || moving != null) return;
                // In the same frame as the apps, so the bar doesn't shift once they are on screen.
                showInputs(ins);
                showApps(apps);
            });
        });
    }

    private void showInputs(List<Inputs.Input> list) {
        inputList = list;
        boolean any = !list.isEmpty();
        if ((inputs.getVisibility() == View.VISIBLE) == any) return;
        if (!any && inputs.isFocused()) settings.requestFocus();
        inputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** The TV's inputs in the side menu; the ones with nothing plugged in are marked. */
    private void openInputs() {
        final List<Inputs.Input> list = inputList;
        if (list.isEmpty()) return;
        CharSequence[] labels = new CharSequence[list.size()];
        Runnable[] actions = new Runnable[list.size()];
        for (int i = 0; i < list.size(); i++) {
            final Inputs.Input in = list.get(i);
            String text = in.device != null ? in.label + "  ·  " + in.device : in.label;
            labels[i] = Inputs.isConnected(this, in) ? text : getString(R.string.input_unplugged, text);
            actions[i] = () -> openInput(in);
        }
        panel.show(getString(R.string.inputs), accent, inputs, labels, actions);
    }

    private void openInput(Inputs.Input in) {
        leftForApp = true;
        try {
            startActivity(Inputs.intent(in));
        } catch (RuntimeException e) {
            leftForApp = false;
            Toast.makeText(this, getString(R.string.launch_failed, in.label), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Opens the activity found when loading the apps, the same one the system's launch intent would
     * name, without asking the package manager when OK is pressed. If it no longer exists (the app
     * was just updated), asks it after all.
     */
    private void launch(App a) {
        Intent i = new Intent(Intent.ACTION_MAIN).setComponent(a.component)
                .addCategory(a.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER);
        leftForApp = true;
        if (start(i)) return;
        PackageManager pm = getPackageManager();
        i = pm.getLeanbackLaunchIntentForPackage(a.pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(a.pkg);
        if (i == null || !start(i)) {
            leftForApp = false;
            Toast.makeText(this, getString(R.string.launch_failed, a.label), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean start(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** On Fire OS, android.settings.SETTINGS leads back to Amazon's home screen; the TV category is used instead. */
    private void openSettings() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(LEANBACK_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        leftForApp = true;
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    // ---------------------------------------------------------------- helpers

    private int tileWidth() {
        return (int) (screenW * tileFraction);
    }

    private int tileHeight() {
        return tileWidth() * 9 / 16;
    }

    private float ringWidth() {
        return Math.max(2f, screenH * 0.0037f);
    }

    private Drawable icon(int res, float size, int color) {
        Drawable d = getDrawable(res).mutate();
        d.setBounds(0, 0, (int) size, (int) size);
        d.setTint(color);
        return d;
    }

    private static FrameLayout.LayoutParams at(int left, int top, int gravity) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        p.leftMargin = left;
        p.topMargin = top;
        return p;
    }
}
