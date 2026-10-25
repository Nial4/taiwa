package io.github.nial4.taiwa;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * Status-bar LED meter in the style of KIDŌ's WI-FI dots (green → amber → red).
 * BARS: live mic level; SPEAK: a speech-like rhythm while TTS talks; PULSE: a scanner sweep while
 * waiting; NONE: all unlit.
 */
public class VoiceView extends View {
    public static final int MODE_NONE = 0;
    public static final int MODE_BARS = 1;
    public static final int MODE_PULSE = 2;
    public static final int MODE_SPEAK = 3;

    private static final int DOTS = 14;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] lit = new int[DOTS];
    private int mode = MODE_NONE;
    private float level;
    private float shown;

    public VoiceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float[] hsv = {0, 0.85f, 0.9f};
        for (int i = 0; i < DOTS; i++) {
            hsv[0] = 130f * (1f - i / (float) (DOTS - 1));  // green → yellow → red
            lit[i] = Color.HSVToColor(hsv);
        }
    }

    public void setMode(int mode) {
        this.mode = mode;
        invalidate();
    }

    /** @param level 0..1, already perceptually scaled */
    public void setLevel(float level) {
        this.level = level;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float step = w / DOTS, r = Math.min(step * 0.36f, h / 2);
        float t = SystemClock.uptimeMillis() / 1000f;
        int on;
        int scan = -1;
        if (mode == MODE_BARS || mode == MODE_SPEAK) {
            float target = mode == MODE_BARS
                    ? level * (0.9f + 0.1f * (float) Math.sin(t * 11))
                    : 0.3f + 0.28f * (float) Math.abs(Math.sin(t * 4.3)) + 0.12f * (float) Math.sin(t * 13.7);
            shown += (target - shown) * 0.35f;
            on = Math.round(shown * DOTS);
        } else {
            on = 0;
            if (mode == MODE_PULSE) {
                float p = (float) ((Math.sin(t * Math.PI * 1.2) + 1) / 2);  // ping-pong
                scan = Math.round(p * (DOTS - 1));
            }
        }
        for (int i = 0; i < DOTS; i++) {
            int color = Eva.UNLIT;
            if (i < on) color = lit[i];
            else if (scan >= 0 && Math.abs(i - scan) <= 1) color = i == scan ? Eva.AMBER_HI : Eva.AMBER;
            paint.setColor(color);
            c.drawCircle(step * (i + 0.5f), h / 2, r, paint);
        }
        if (mode != MODE_NONE) postInvalidateOnAnimation();
    }
}
