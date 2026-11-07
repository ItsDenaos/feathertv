package tv.feathertv.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.view.View;

import java.nio.ByteBuffer;
import java.util.Random;

/**
 * The "stage": an elliptical glow in the focused app's color over a black background.
 * Centered at (70 %, 20 %), radii of 70 % x 80 % of the screen, from 60 % opacity to transparent.
 *
 * Performance: a full-screen gradient recalculated on every frame dropped frames on a Fire TV
 * Stick. Here the glow's shape is computed once into an alpha-only mask at a quarter of the
 * resolution (an upscaled gradient looks the same), and the GPU tints it with the app's color
 * while drawing it. Changing color repaints nothing and uploads nothing: it is a 200 ms cross-fade
 * between two tinted copies of the same mask.
 *
 * It is opaque and covers the whole screen, so the window has no background: painting several
 * full-screen layers exhausts a Fire TV Stick's memory bandwidth.
 */
final class Stage extends View {
    private static final int DIV = 4;
    private static final int ALPHA = 0x99;
    /** The same mask serves the home screen and Options (only used from the main thread). */
    private static Bitmap shared;

    /** The previous color, fading out. */
    private final Paint outgoing = new Paint(Paint.FILTER_BITMAP_FLAG);
    /** The current color, added on top: over black, old × (1 − f) + new × f is an exact cross-fade. */
    private final Paint incoming = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();
    /** Area where the glow has color; outside it the mask is empty. */
    private final Rect glow = new Rect();
    /** One animator for every color change: nothing is allocated per key press. */
    private final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
    private Bitmap mask;
    private int color;
    private int previous;
    private float progress = 1f;

    Stage(Context c) {
        super(c);
        incoming.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        anim.setDuration(200);
        anim.addUpdateListener(a -> {
            progress = (Float) a.getAnimatedValue();
            invalidate();
        });
    }

    /** Changes the glow color, with a short cross-fade if requested. */
    void setColor(int target, boolean animate) {
        if (target == color) return;
        anim.cancel();
        previous = animate ? color : 0;
        color = target;
        if (previous == 0) {
            progress = 1f;
            invalidate();
            return;
        }
        progress = 0f;
        anim.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (w <= 0 || h <= 0) return;
        dst.set(0, 0, w, h);
        // The gradient fades out at 70 % of its radii (0.49 W x 0.56 H) around (0.7 W, 0.2 H).
        glow.set((int) (w * 0.21f) - 2, 0, w, Math.min(h, (int) (h * 0.76f) + 2));
        int mw = Math.max(1, w / DIV), mh = Math.max(1, h / DIV);
        if (shared == null || shared.getWidth() != mw || shared.getHeight() != mh) shared = paintMask(mw, mh);
        mask = shared;
    }

    @Override
    protected void onDraw(Canvas c) {
        // The window has no background (saves a full-screen pass): the stage is opaque.
        // Clearing to black is almost free on the GPU; the mask is only drawn where there is color.
        c.drawColor(Color.BLACK);
        if (mask == null || color == 0) return;
        c.save();
        c.clipRect(glow);
        if (progress < 1f) {
            outgoing.setColor(previous);
            outgoing.setAlpha(Math.round(255 * (1f - progress)));
            c.drawBitmap(mask, null, dst, outgoing);
        }
        // An alpha-only bitmap is drawn in the paint's color.
        incoming.setColor(color);
        incoming.setAlpha(Math.round(255 * progress));
        c.drawBitmap(mask, null, dst, incoming);
        c.restore();
    }

    /**
     * The glow's opacity: ALPHA at the center, falling linearly to 0 at 70 % of the radii.
     * A little noise (±½ step) keeps the dark end of the gradient from showing bands.
     */
    private static Bitmap paintMask(int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        int stride = bmp.getRowBytes();
        byte[] px = new byte[stride * h];
        float cx = w * 0.7f, cy = h * 0.2f, rx = w * 0.7f * 0.7f, ry = h * 0.8f * 0.7f;
        int x0 = Math.max(0, (int) (cx - rx)), y1 = Math.min(h, (int) Math.ceil(cy + ry));
        Random noise = new Random(7);
        for (int y = 0; y < y1; y++) {
            float dy = (y + 0.5f - cy) / ry;
            for (int x = x0; x < w; x++) {
                float dx = (x + 0.5f - cx) / rx;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d >= 1f) continue;
                int a = (int) (ALPHA * (1f - d) + noise.nextFloat());
                px[y * stride + x] = (byte) Math.min(255, a);
            }
        }
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(px));
        return bmp;
    }
}
