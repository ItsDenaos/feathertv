package tv.feathertv.home;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.HorizontalScrollView;

/**
 * Scrolling row that keeps the focused (scaled-up) tile within the side margins. The standard
 * HorizontalScrollView only keeps it against the edge of the screen.
 */
final class Row extends HorizontalScrollView {
    private int margin;

    Row(Context c) {
        super(c);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setClipChildren(false);
        setClipToPadding(false);
    }

    /** Minimum distance between the focused tile and each edge of the screen. */
    void setMargin(int px) {
        margin = px;
    }

    /** Scrolls the row so the tile is visible (moving a tile doesn't change focus, so the row doesn't scroll by itself). */
    void reveal(View child) {
        Rect r = new Rect();
        child.getDrawingRect(r);
        offsetDescendantRectToMyCoords(child, r);
        int dx = computeScrollDeltaToGetChildRectOnScreen(r);
        if (dx != 0) smoothScrollBy(dx, 0);
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
        int x = getScrollX();
        int left = x + margin, right = x + getWidth() - margin;
        if (r.right > right) return r.right - right;
        if (r.left < left) return Math.max(r.left - left, -x);
        return 0;
    }
}
