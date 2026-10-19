package tv.feathertv.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;

/**
 * The "stage": an elliptical glow in the focused app's color over a black background.
 * Centered at (70 %, 20 %), radii of 70 % x 80 % of the screen, from 60 % opacity to transparent.
 *
 * Performance: a full-screen gradient recalculated on every frame dropped frames on a Fire TV
 * Stick. Here the glow is painted once per color, already over black, into a bitmap at a quarter
 * of the resolution (an upscaled gradient looks the same), and a color change is a cross-fade
 * between two bitmaps. Each frame only draws one or two images.
 *
 * It is opaque and covers the whole screen, so the window has no background: painting several
 * full-screen layers exhausts a Fire TV Stick's memory bandwidth.
 */
final class Stage extends View {
    private static final int DIV = 4;
    private static final int ALPHA = 0x99;

    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fade = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint gradient = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Rect dst = new Rect();
    /** Area where the glow has color; outside it the bitmap is pure black. */
    private final Rect glow = new Rect();
    private final Bitmap[] buffers = new Bitmap[2];
    private Bitmap current;
    private Bitmap previous;
    private int color;
    private ValueAnimator anim;

    Stage(Context c) {
        super(c);
    }

    /** Changes the glow color, with a short cross-fade if requested. */
    void setColor(int target, boolean animate) {
        if (target == color && current != null) return;
        color = target;
        if (buffers[0] == null) return; // No size yet: it's painted in onSizeChanged.
        if (anim != null) anim.cancel();
        Bitmap old = current;
        Bitmap next = buffers[0] == old ? buffers[1] : buffers[0];
        paint(next, target);
        current = next;
        previous = animate ? old : null;
        if (previous == null) {
            invalidate();
            return;
        }
        fade.setAlpha(0);
        anim = ValueAnimator.ofInt(0, 255);
        anim.setDuration(200);
        anim.addUpdateListener(a -> {
            fade.setAlpha((Integer) a.getAnimatedValue());
            invalidate();
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                previous = null;
                invalidate();
            }
        });
        anim.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (w <= 0 || h <= 0) return;
        if (anim != null) anim.cancel();
        int bw = Math.max(1, w / DIV), bh = Math.max(1, h / DIV);
        buffers[0] = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        buffers[1] = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        dst.set(0, 0, w, h);
        // The gradient fades out at 70 % of its radii (0.49 W x 0.56 H) around (0.7 W, 0.2 H).
        glow.set((int) (w * 0.21f) - 2, 0, w, Math.min(h, (int) (h * 0.76f) + 2));
        current = null;
        previous = null;
        if (color != 0) {
            paint(buffers[0], color);
            current = buffers[0];
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        // The window has no background (saves a full-screen pass): the stage is opaque.
        // Clearing to black is almost free on the GPU; the image is only drawn where there is color.
        c.drawColor(Color.BLACK);
        if (current == null) return;
        c.save();
        c.clipRect(glow);
        if (previous != null) {
            c.drawBitmap(previous, null, dst, image);
            c.drawBitmap(current, null, dst, fade);
        } else {
            c.drawBitmap(current, null, dst, image);
        }
        c.restore();
    }

    /** Paints the glow already composited over black (opaque), so the cross-fade is exact. */
    private void paint(Bitmap into, int col) {
        float w = into.getWidth(), h = into.getHeight();
        float rx = w * 0.7f, ry = h * 0.8f;
        int center = (col & 0x00FFFFFF) | (ALPHA << 24);
        gradient.setShader(new RadialGradient(0, 0, rx, new int[] {center, col & 0x00FFFFFF},
                new float[] {0f, 0.7f}, Shader.TileMode.CLAMP));
        Canvas c = new Canvas(into);
        c.drawColor(Color.BLACK);
        c.translate(w * 0.7f, h * 0.2f);
        c.scale(1f, ry / rx);
        c.drawCircle(0, 0, rx, gradient);
    }
}
