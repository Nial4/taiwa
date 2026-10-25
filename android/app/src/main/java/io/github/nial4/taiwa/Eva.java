package io.github.nial4.taiwa;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.TypefaceSpan;

/**
 * Visual language shared with the KIDŌ launcher (NERV-terminal style): black panels, amber fills,
 * gold labels written as "漢字 ENGLISH", hairline frames, condensed / mincho / mono type.
 * Fonts are the same OFL families KIDŌ ships (licenses in assets/licenses).
 */
final class Eva {
    static final int PANEL = 0xF5030201;
    static final int SURFACE = 0xFF0C0904;
    static final int AMBER = 0xFFD07000;      // focus fills, accents
    static final int AMBER_HI = 0xFFFFA62B;   // emphasis in text
    static final int GOLD = 0xFFC99930;       // labels
    static final int GOLD_DIM = 0xFF84692A;   // data, secondary
    static final int LINE = 0xFF33250A;       // hairlines
    static final int LINE_HI = 0xFF5C4413;    // frames
    static final int CREAM = 0xFFE9DEC9;      // body text
    static final int CREAM_DIM = 0xFFB9AC95;
    static final int RED = 0xFFD8323A;
    static final int GREEN = 0xFF46C866;
    static final int UNLIT = 0xFF2A1F09;

    static Typeface cond;
    static Typeface mono;
    static Typeface mincho;

    private Eva() {}

    static void init(Context context) {
        if (cond != null) return;
        cond = context.getResources().getFont(R.font.eva_cond);
        mono = context.getResources().getFont(R.font.eva_mono);
        mincho = context.getResources().getFont(R.font.eva_mincho);
    }

    /** "対話 DIALOGUE": mincho kanji followed by condensed English at {@code englishScale}. */
    static CharSequence label(String kanji, String english, float englishScale) {
        SpannableStringBuilder sb = new SpannableStringBuilder(kanji);
        sb.setSpan(new TypefaceSpan(mincho), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (english != null) {
            sb.append(' ');
            int start = sb.length();
            sb.append(english);
            sb.setSpan(new TypefaceSpan(cond), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new RelativeSizeSpan(englishScale), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    /** Sharp-cornered box with a hairline frame. */
    static GradientDrawable frame(int fill, int stroke, int strokeDp, float density) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        if (stroke != 0) d.setStroke(Math.max(1, Math.round(strokeDp * density)), stroke);
        return d;
    }

    /** Text colour that turns black when the parent row is focused (amber fill), like KIDŌ's cards. */
    static ColorStateList onAmber(int normal) {
        return new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF000000, normal});
    }
}
