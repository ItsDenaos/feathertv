package tv.feathertv.home;

import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Replaces Amazon's home screen with FeatherTV.
 *
 * On Fire OS the Home button can't be intercepted, so this service waits for Amazon's home
 * window to appear (it only receives events from that package and never reads the screen),
 * covers it with a black curtain and brings FeatherTV to the front.
 *
 * Android delays app launches by 5 s after Home is pressed, except launches started by the
 * system; that's why FeatherTV is opened with an immediate alarm (this needs
 * alarm_manager_constants=min_futurity=0, which the installer sets).
 */
public final class Guard extends AccessibilityService {
    /** Only Amazon's main screen: Alexa searches and the launcher's other windows are left alone. */
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity";
    static final String EXTRA_RESET = "tv.feathertv.home.RESET";
    private static final long CURTAIN_TIMEOUT_MS = 2500;

    private static Guard instance;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable lift = this::liftCurtain;
    private View curtain;
    private PendingIntent openHome;
    private PendingIntent openHomeReset;

    // Note: the CLOSE_SYSTEM_DIALOGS broadcast (reason=homekey) would arrive before Amazon's
    // window, but Fire OS doesn't deliver it to non-system apps (tested on 7.7.1.7).

    @Override
    protected void onServiceConnected() {
        instance = this;
        openHome = pending(false);
        openHomeReset = pending(true);
        // The curtain is created once and kept hidden: showing it is just a visibility change.
        curtain = new View(this);
        curtain.setBackgroundColor(Color.BLACK);
        curtain.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(curtain, lp);
        } catch (RuntimeException e) {
            curtain = null; // Without a curtain, Amazon's screen would show for a moment; nothing worse.
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence cls = e.getClassName();
        if (cls == null || !cls.toString().startsWith(AMAZON_HOME)) return;
        // Amazon sometimes reports again when FeatherTV is already in front: nothing to do.
        if (Home.isFront()) return;
        bringHome(Home.wasFrontJustNow());
    }

    /** Covers the screen and opens FeatherTV with an immediate alarm (without the 5 s delay). */
    private void bringHome(boolean reset) {
        dropCurtain();
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        try {
            am.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime(), reset ? openHomeReset : openHome);
        } catch (RuntimeException ex) {
            liftCurtain();
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        ui.removeCallbacks(lift);
        if (curtain != null) {
            try {
                ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(curtain);
            } catch (RuntimeException ignored) {
                // It was already gone.
            }
            curtain = null;
        }
        super.onDestroy();
    }

    /** Called by Home once it is visible. */
    static void homeShown() {
        Guard g = instance;
        if (g != null) g.liftCurtain();
    }

    /** True if the service is enabled in the accessibility settings. */
    static boolean isEnabled(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        ComponentName me = new ComponentName(c, Guard.class);
        for (String s : list.split(":")) {
            if (s.equalsIgnoreCase(me.flattenToString()) || s.equalsIgnoreCase(me.flattenToShortString())) return true;
        }
        return false;
    }

    private PendingIntent pending(boolean reset) {
        Intent i = new Intent(this, Home.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (reset) i.putExtra(EXTRA_RESET, true);
        return PendingIntent.getActivity(this, reset ? 1 : 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void dropCurtain() {
        if (curtain == null) return;
        curtain.animate().cancel();
        curtain.setAlpha(1f);
        curtain.setVisibility(View.VISIBLE);
        // If FeatherTV never opens, the curtain lifts by itself.
        ui.removeCallbacks(lift);
        ui.postDelayed(lift, CURTAIN_TIMEOUT_MS);
    }

    private void liftCurtain() {
        ui.removeCallbacks(lift);
        final View c = curtain;
        if (c == null || c.getVisibility() != View.VISIBLE) return;
        c.animate().alpha(0f).setDuration(120).withEndAction(() -> c.setVisibility(View.GONE)).start();
    }
}
