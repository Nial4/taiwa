package io.github.nial4.taiwa;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Panel background: near-black fill, gold hairline frame and amber corner brackets. */
final class HudFrame extends Drawable {
    private final Paint fill = new Paint();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corner = new Paint();
    private final float d;

    HudFrame(float density) {
        d = density;
        fill.setColor(Eva.PANEL);
        line.setColor(Eva.LINE_HI);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1, density));
        corner.setColor(Eva.AMBER);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        c.drawRect(b, fill);
        float h = line.getStrokeWidth() / 2;
        c.drawRect(b.left + h, b.top + h, b.right - h, b.bottom - h, line);
        float len = 22 * d, w = 3 * d;
        // ┌ ┐ └ ┘
        c.drawRect(b.left, b.top, b.left + len, b.top + w, corner);
        c.drawRect(b.left, b.top, b.left + w, b.top + len, corner);
        c.drawRect(b.right - len, b.top, b.right, b.top + w, corner);
        c.drawRect(b.right - w, b.top, b.right, b.top + len, corner);
        c.drawRect(b.left, b.bottom - w, b.left + len, b.bottom, corner);
        c.drawRect(b.left, b.bottom - len, b.left + w, b.bottom, corner);
        c.drawRect(b.right - len, b.bottom - w, b.right, b.bottom, corner);
        c.drawRect(b.right - w, b.bottom - len, b.right, b.bottom, corner);
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
