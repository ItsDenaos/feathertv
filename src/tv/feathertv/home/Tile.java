package tv.feathertv.home;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * An app's 16:9 tile. Draws the art prepared by Catalog with anti-aliased rounded corners. With
 * focus it grows and shows a ring outside it, separated by a gap, so it is also visible on white
 * banners.
 */
final class Tile extends View {
    static final float FOCUS_SCALE = 1.12f;
    /** The interface's shared curve: starts fast and eases out. */
    static final TimeInterpolator EASE = new DecelerateInterpolator(1.6f);

    final App app;
    private final float radius;
    private final float ringWidth;
    private final float ringGap;
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final RectF ringBounds = new RectF();

    Tile(Context c, App app, float radius, float ringWidth, int ringColor) {
        super(c);
        this.app = app;
        this.radius = radius;
        this.ringWidth = ringWidth;
        this.ringGap = ringWidth * 1.5f;
        setFocusable(true);
        setClickable(true);
        setContentDescription(app.label);
        // No outline: no shadow when the tile is raised, it should only draw on top.
        setOutlineProvider(null);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(ringWidth);
        ring.setColor(ringColor);
        art.setShader(new BitmapShader(app.art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        bounds.set(0, 0, w, h);
        float o = ringGap + ringWidth / 2;
        ringBounds.set(-o, -o, w + o, h + o);
        // The art is painted at tile size; it's only scaled if the layout changes.
        if (w != app.art.getWidth() || h != app.art.getHeight()) {
            Matrix m = new Matrix();
            m.setScale(w / (float) app.art.getWidth(), h / (float) app.art.getHeight());
            art.getShader().setLocalMatrix(m);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawRoundRect(bounds, radius, radius, art);
        if (isFocused()) {
            float r = radius + ringGap + ringWidth / 2;
            c.drawRoundRect(ringBounds, r, r, ring);
        }
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        float s = gained ? FOCUS_SCALE : 1f;
        animate().scaleX(s).scaleY(s).translationZ(gained ? 1f : 0f).setDuration(160)
                .setInterpolator(EASE).start();
        invalidate();
    }

    /** How far the ring extends on each side, with the tile already scaled up. */
    static int ringOverhang(float ringWidth) {
        return (int) Math.ceil(ringWidth * 2.5f * FOCUS_SCALE);
    }
}
