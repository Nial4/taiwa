package io.github.nial4.taiwa;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Inline Markdown (headings, lists, emphasis, code, links) rendered in the KIDŌ palette. */
final class Markdown {
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*)");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d+[.)])\\s+(.*)");
    private static final Pattern INLINE = Pattern.compile(
            "\\(\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)\\)"          // 1,2  ([site](url))  web-search citation
            + "|\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)"                  // 3,4  [text](url)
            + "|\\*\\*(.+?)\\*\\*"                                       // 5    **bold**
            + "|`([^`]+)`"                                                // 6    `code`
            + "|~~(.+?)~~"                                                // 7    ~~strike~~
            + "|(?<![*\\w])\\*(?![\\s*])([^*\\n]+?)(?<!\\s)\\*(?![*\\w])"  // 8    *italic*
            + "|(https?://[^\\s)）\\]]+)");                               // 9    bare URL
    private static final Pattern PARTIAL_LINK = Pattern.compile("\\(?\\[[^\\]\\n]*(]\\([^)\\n]*)?$");

    private Markdown() {}

    static CharSequence render(String src, boolean streaming, int indentPx) {
        String text = src.replaceAll("\n{3,}", "\n\n").trim();
        if (streaming) text = hideDanglingMarkers(text);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String[] lines = text.split("\n", -1);
        boolean inList = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int start = sb.length();
            Matcher m;
            if (inList && line.matches("^\\s{2,}\\S.*") && !BULLET.matcher(line).matches()
                    && !NUMBERED.matcher(line).matches()) {
                // Indented continuation of the previous list item.
                inline(sb, line.trim());
                hang(sb, start, 1, 0, indentPx);
                if (i < lines.length - 1) sb.append('\n');
                continue;
            }
            inList = BULLET.matcher(line).matches() || NUMBERED.matcher(line).matches()
                    || (inList && line.trim().isEmpty());
            if ((m = HEADING.matcher(line)).matches()) {
                inline(sb, m.group(1));
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new RelativeSizeSpan(1.08f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(Eva.AMBER_HI), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if ((m = BULLET.matcher(line)).matches()) {
                sb.append("▸  ");
                sb.setSpan(new ForegroundColorSpan(Eva.AMBER), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                inline(sb, m.group(2));
                hang(sb, start, m.group(1).length() / 2, 1, indentPx);
            } else if ((m = NUMBERED.matcher(line)).matches()) {
                sb.append(m.group(2)).append(' ');
                inline(sb, m.group(3));
                hang(sb, start, m.group(1).length() / 2, 1, indentPx);
            } else {
                inline(sb, line);
            }
            if (i < lines.length - 1) sb.append('\n');
        }
        if (streaming) {
            int s = sb.length();
            sb.append(sb.length() == 0 ? "▌" : " ▌");
            sb.setSpan(new ForegroundColorSpan(Eva.AMBER_HI), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    /** Indent by {@code level} steps; wrapped lines get {@code extra} more steps (hanging indent). */
    private static void hang(SpannableStringBuilder sb, int start, int level, int extra, int indentPx) {
        int first = level * indentPx;
        sb.setSpan(new LeadingMarginSpan.Standard(first, first + extra * indentPx), start, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    static void inline(SpannableStringBuilder sb, String s) {
        Matcher m = INLINE.matcher(s);
        int last = 0;
        while (m.find()) {
            sb.append(s, last, m.start());
            int start = sb.length();
            if (m.group(1) != null) {
                chip(sb, m.group(1));
            } else if (m.group(3) != null) {
                sb.append(m.group(3));
                sb.setSpan(new ForegroundColorSpan(Eva.AMBER_HI), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(5) != null) {
                inline(sb, m.group(5));
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(Eva.AMBER_HI), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(6) != null) {
                sb.append(m.group(6));
                sb.setSpan(new TypefaceSpan(Eva.mono), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(Eva.GOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new BackgroundColorSpan(Eva.UNLIT), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(7) != null) {
                inline(sb, m.group(7));
                sb.setSpan(new StrikethroughSpan(), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(8) != null) {
                inline(sb, m.group(8));
                sb.setSpan(new StyleSpan(Typeface.ITALIC), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                chip(sb, host(m.group(9)));
            }
            last = m.end();
        }
        sb.append(s, last, s.length());
    }

    /** A grey source pill like ChatGPT's citation chips; links cannot be opened on the TV anyway. */
    private static void chip(SpannableStringBuilder sb, String label) {
        int start = sb.length();
        sb.append('\u2009').append(label.replaceFirst("^www\\.", "").toUpperCase(java.util.Locale.ROOT)).append('\u2009');
        sb.setSpan(new TypefaceSpan(Eva.mono), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(Eva.GOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new BackgroundColorSpan(Eva.UNLIT), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new RelativeSizeSpan(0.78f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static String host(String url) {
        String h = android.net.Uri.parse(url).getHost();
        return h == null ? url : h;
    }

    /** While streaming, an unclosed ** or ` would flash as raw markup; drop it until it closes. */
    private static String hideDanglingMarkers(String s) {
        Matcher link = PARTIAL_LINK.matcher(s);
        if (link.find()) s = s.substring(0, link.start());
        int bold = s.split("\\*\\*", -1).length - 1;
        if (bold % 2 == 1) {
            int i = s.lastIndexOf("**");
            s = s.substring(0, i) + s.substring(i + 2);
        }
        int ticks = s.length() - s.replace("`", "").length();
        if (ticks % 2 == 1) {
            int i = s.lastIndexOf('`');
            s = s.substring(0, i) + s.substring(i + 1);
        }
        return s;
    }
}
