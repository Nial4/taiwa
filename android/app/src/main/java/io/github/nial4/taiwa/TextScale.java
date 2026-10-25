package io.github.nial4.taiwa;

import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Message text sizes are stored as a base size and multiplied by the panel's current scale. */
final class TextScale {
    /** Text is a bit smaller in wide mode so more of a table or long answer fits on screen. */
    static final float WIDE = 0.85f;

    private TextScale() {}

    static void init(TextView v, float sp, float scale) {
        v.setTag(R.id.base_sp, sp);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * scale);
    }

    static void apply(View v, float scale) {
        if (v instanceof AnswerView) {
            ((AnswerView) v).setScale(scale);
        } else if (v instanceof TextView && v.getTag(R.id.base_sp) instanceof Float) {
            ((TextView) v).setTextSize(TypedValue.COMPLEX_UNIT_SP, (Float) v.getTag(R.id.base_sp) * scale);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) apply(g.getChildAt(i), scale);
        }
    }
}
