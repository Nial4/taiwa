package io.github.nial4.taiwa;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One assistant message, ChatGPT style: an optional "thought / searched" line, the Markdown body
 * split into blocks (text, table, code, quote, rule, image), and the web sources.
 * While streaming only blocks whose source changed are rebuilt.
 */
final class AnswerView extends LinearLayout {
    private enum Kind { TEXT, TABLE, CODE, QUOTE, RULE, IMAGE }

    private static final class Block {
        final Kind kind;
        final String src;

        Block(Kind kind, String src) {
            this.kind = kind;
            this.src = src;
        }
    }

    private static final Pattern RULE = Pattern.compile("^\\s*(\\*\\s*\\*\\s*\\*|-\\s*-\\s*-|_\\s*_\\s*_)[\\s*_-]*$");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");
    private static final Pattern IMAGE = Pattern.compile("^\\s*!\\[([^\\]]*)]\\((\\S+?)(?:\\s+\"[^\"]*\")?\\)\\s*$");

    private final TextView meta;
    private final TextView thought;
    private final LinearLayout body;
    private final LinearLayout sources;
    private final List<Block> rendered = new ArrayList<>();
    private final List<View> views = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private float scale;
    /** Narrow panel: tables become one card per row (a real grid collapses its columns there). */
    private boolean compactTables;

    AnswerView(Context context, float scale, boolean compactTables) {
        super(context);
        this.scale = scale;
        this.compactTables = compactTables;
        setOrientation(VERTICAL);
        LinearLayout head = new LinearLayout(context);
        head.setOrientation(HORIZONTAL);
        head.setBaselineAligned(true);
        TextView label = text(13, Eva.GOLD_DIM);
        label.setText(Eva.label("応答", "RESPONSE", 0.85f));
        label.setLetterSpacing(0.08f);
        head.addView(label);
        meta = text(12, Eva.GOLD_DIM);
        meta.setTypeface(Eva.mono);
        meta.setLetterSpacing(0.04f);
        meta.setVisibility(GONE);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        mlp.setMarginStart(dp(12));
        head.addView(meta, mlp);
        addView(head, matchWrap(0));
        thought = text(13, Eva.GOLD_DIM);
        thought.setTypeface(Eva.mono);
        thought.setLineSpacing(0, 1.25f);
        thought.setMaxLines(3);
        thought.setEllipsize(TextUtils.TruncateAt.END);
        thought.setVisibility(GONE);
        addView(thought, matchWrap(dp(6)));
        body = new LinearLayout(context);
        body.setOrientation(VERTICAL);
        addView(body, matchWrap(0));
        sources = new LinearLayout(context);
        sources.setOrientation(VERTICAL);
        sources.setVisibility(GONE);
        addView(sources, matchWrap(dp(14)));
    }

    // ------------------------------------------------------------------ status lines

    void setMeta(String text) {
        meta.setText(text);
        meta.setVisibility(TextUtils.isEmpty(text) ? GONE : VISIBLE);
    }

    /** Live reasoning summary preview; shows its latest heading or sentence. */
    void setThought(String text) {
        String t = text == null ? "" : text.trim();
        Matcher m = Pattern.compile("\\*\\*([^*]+)\\*\\*").matcher(t);
        String shown = null;
        while (m.find()) shown = m.group(1);
        if (shown == null) {
            String[] paras = t.split("\n\\s*\n");
            shown = paras[paras.length - 1].replace("**", "");
        }
        thought.setText(TextUtils.isEmpty(shown) ? shown : "▸ " + shown);
        thought.setVisibility(TextUtils.isEmpty(shown) ? GONE : VISIBLE);
    }

    void hideThought() {
        thought.setVisibility(GONE);
    }

    void setSources(List<String[]> items) {
        sources.removeAllViews();
        if (items.isEmpty()) {
            sources.setVisibility(GONE);
            return;
        }
        TextView title = text(13, Eva.GOLD_DIM);
        title.setText(Eva.label("出典", "SOURCES", 0.85f));
        title.setLetterSpacing(0.08f);
        sources.addView(title, matchWrap(0));
        int n = 0;
        for (String[] it : items) {
            if (++n > 6) break;
            String host = Uri.parse(it[0]).getHost();
            if (host != null) host = host.replaceFirst("^www\\.", "");
            TextView v = text(13, Eva.CREAM_DIM);
            v.setSingleLine(true);
            v.setEllipsize(TextUtils.TruncateAt.END);
            String label = TextUtils.isEmpty(it[1]) ? host : it[1];
            SpannableStringBuilder line = new SpannableStringBuilder(String.format(java.util.Locale.ROOT, "%02d  ", n));
            line.setSpan(new android.text.style.TypefaceSpan(Eva.mono), 0, line.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            line.setSpan(new android.text.style.ForegroundColorSpan(Eva.AMBER), 0, line.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            line.append(label).append(host != null && !host.equals(label) ? " · " + host : "");
            v.setText(line);
            sources.addView(v, matchWrap(dp(4)));
        }
        sources.setVisibility(VISIBLE);
    }

    // ------------------------------------------------------------------ body

    void setMarkdown(String markdown, boolean streaming) {
        List<Block> blocks = parse(markdown, streaming);
        if (streaming && (blocks.isEmpty() || blocks.get(blocks.size() - 1).kind != Kind.TEXT)) {
            blocks.add(new Block(Kind.TEXT, ""));  // holds the streaming cursor
        }
        int k = 0;
        while (k < blocks.size() && k < rendered.size()
                && blocks.get(k).kind == rendered.get(k).kind && blocks.get(k).src.equals(rendered.get(k).src)) {
            k++;
        }
        // The first changed block is usually the one still streaming: update it in place.
        if (k < blocks.size() && k < rendered.size() && blocks.get(k).kind == rendered.get(k).kind
                && update(views.get(k), blocks.get(k), streaming && k == blocks.size() - 1)) {
            rendered.set(k, blocks.get(k));
            k++;
        }
        while (rendered.size() > k) {
            body.removeView(views.remove(views.size() - 1));
            rendered.remove(rendered.size() - 1);
        }
        for (int j = k; j < blocks.size(); j++) {
            Block b = blocks.get(j);
            View v = create(b, streaming && j == blocks.size() - 1);
            views.add(v);
            rendered.add(b);
            body.addView(v, matchWrap(j == 0 ? dp(2) : dp(12)));
        }
        // Only the last block may show the cursor.
        int last = streaming ? rendered.size() - 1 : rendered.size();
        for (int j = 0; j < last; j++) {
            if (rendered.get(j).kind == Kind.TEXT && views.get(j).getTag() == Boolean.TRUE) {
                update(views.get(j), rendered.get(j), false);
            }
        }
    }

    private static List<Block> parse(String md, boolean streaming) {
        List<Block> out = new ArrayList<>();
        String[] lines = md.replace("\r", "").split("\n", -1);
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                flush(out, text);
                StringBuilder code = new StringBuilder(trimmed.substring(3).trim()).append('\n');
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) code.append(lines[i++]).append('\n');
                i++;
                out.add(new Block(Kind.CODE, code.toString()));
                continue;
            }
            if (trimmed.startsWith("|")) {
                int j = i;
                StringBuilder table = new StringBuilder();
                while (j < lines.length && lines[j].trim().startsWith("|")) table.append(lines[j++].trim()).append('\n');
                boolean hasSep = j - i >= 2 && TABLE_SEP.matcher(lines[i + 1]).matches();
                if (hasSep) {
                    flush(out, text);
                    out.add(new Block(Kind.TABLE, table.toString()));
                    i = j;
                    continue;
                }
                if (streaming && j == lines.length) break;  // header row still arriving: hold it back
            }
            if (RULE.matcher(line).matches() && !trimmed.isEmpty()) {
                flush(out, text);
                out.add(new Block(Kind.RULE, ""));
                i++;
                continue;
            }
            Matcher img = IMAGE.matcher(line);
            if (img.matches()) {
                flush(out, text);
                out.add(new Block(Kind.IMAGE, img.group(2) + "\n" + img.group(1)));
                i++;
                continue;
            }
            if (trimmed.startsWith(">")) {
                flush(out, text);
                StringBuilder quote = new StringBuilder();
                while (i < lines.length && lines[i].trim().startsWith(">")) {
                    quote.append(lines[i++].trim().replaceFirst("^>\\s?", "")).append('\n');
                }
                out.add(new Block(Kind.QUOTE, quote.toString().trim()));
                continue;
            }
            text.append(line).append('\n');
            i++;
        }
        flush(out, text);
        return out;
    }

    private static void flush(List<Block> out, StringBuilder text) {
        String t = text.toString().trim();
        if (!t.isEmpty()) out.add(new Block(Kind.TEXT, t));
        text.setLength(0);
    }

    private View create(Block b, boolean cursor) {
        Context c = getContext();
        switch (b.kind) {
            case TABLE: {
                if (compactTables) {
                    LinearLayout cards = new LinearLayout(c);
                    cards.setOrientation(VERTICAL);
                    update(cards, b, cursor);
                    return cards;
                }
                TableLayout t = new TableLayout(c);
                t.setShrinkAllColumns(true);
                t.setStretchAllColumns(true);
                update(t, b, cursor);
                return t;
            }
            case CODE: {
                TextView v = text(14, Eva.GREEN);
                v.setTypeface(Eva.mono);
                v.setLineSpacing(0, 1.2f);
                v.setPadding(dp(14), dp(12), dp(14), dp(12));
                v.setBackground(Eva.frame(0xFF000000, 0xFF1E4A2A, 1, getResources().getDisplayMetrics().density));
                update(v, b, cursor);
                return v;
            }
            case QUOTE: {
                LinearLayout row = new LinearLayout(c);
                View bar = new View(c);
                bar.setBackgroundColor(Eva.AMBER);
                row.addView(bar, new LinearLayout.LayoutParams(dp(3), LayoutParams.MATCH_PARENT));
                TextView v = text(16, Eva.CREAM_DIM);
                v.setLineSpacing(0, 1.3f);
                v.setPadding(dp(12), 0, 0, 0);
                row.addView(v, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
                update(row, b, cursor);
                return row;
            }
            case RULE: {
                View v = new View(c);
                v.setBackgroundColor(Eva.LINE_HI);
                v.setMinimumHeight(dp(1));
                return v;
            }
            case IMAGE: {
                ImageView v = new ImageView(c);
                v.setAdjustViewBounds(true);
                v.setMaxHeight(dp(220));
                v.setScaleType(ImageView.ScaleType.FIT_START);
                String[] parts = b.src.split("\n", 2);
                loadImage(v, parts[0]);
                return v;
            }
            default: {
                TextView v = text(16, Eva.CREAM);
                v.setLineSpacing(0, 1.32f);
                update(v, b, cursor);
                return v;
            }
        }
    }

    /** @return false when the view cannot be updated in place */
    private boolean update(View v, Block b, boolean cursor) {
        switch (b.kind) {
            case TEXT:
                ((TextView) v).setText(Markdown.render(b.src, cursor, dp(18)));
                v.setTag(cursor);
                return true;
            case CODE: {
                String[] parts = b.src.split("\n", 2);
                ((TextView) v).setText(parts.length > 1 ? parts[1].replaceAll("\n$", "") : "");
                return true;
            }
            case QUOTE:
                ((TextView) ((LinearLayout) v).getChildAt(1)).setText(Markdown.render(b.src, false, dp(18)));
                return true;
            case TABLE:
                if (v instanceof TableLayout) {
                    if (compactTables) return false;
                    fillTable((TableLayout) v, b.src);
                } else {
                    if (!compactTables) return false;
                    fillCards((LinearLayout) v, b.src);
                }
                return true;
            default:
                return false;
        }
    }

    private static List<String[]> tableRows(String src) {
        List<String[]> rows = new ArrayList<>();
        String[] lines = src.split("\n");
        for (int r = 0; r < lines.length; r++) {
            if (r == 1 && TABLE_SEP.matcher(lines[r]).matches()) continue;
            String row = lines[r].trim().replaceAll("^\\|", "").replaceAll("\\|$", "");
            String[] cells = row.split("(?<!\\\\)\\|", -1);
            for (int i = 0; i < cells.length; i++) cells[i] = cells[i].trim().replace("<br>", "\n");
            rows.add(cells);
        }
        return rows;
    }

    /** One card per data row: the first cell as an amber title, then "header  value" lines. */
    private void fillCards(LinearLayout cards, String src) {
        cards.removeAllViews();
        List<String[]> rows = tableRows(src);
        if (rows.isEmpty()) return;
        String[] header = rows.get(0);
        for (int r = 1; r < rows.size(); r++) {
            String[] cells = rows.get(r);
            LinearLayout card = new LinearLayout(getContext());
            card.setOrientation(VERTICAL);
            card.setPadding(dp(12), dp(8), dp(8), dp(10));
            card.setBackground(Eva.frame(Eva.SURFACE, Eva.LINE, 1, getResources().getDisplayMetrics().density));
            TextView title = text(15, Eva.AMBER_HI);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            SpannableStringBuilder sb = new SpannableStringBuilder();
            Markdown.inline(sb, cells.length > 0 ? cells[0] : "");
            title.setText(sb);
            card.addView(title);
            for (int i = 1; i < cells.length; i++) {
                if (cells[i].isEmpty()) continue;
                TextView line = text(14, Eva.CREAM);
                line.setLineSpacing(0, 1.2f);
                SpannableStringBuilder l = new SpannableStringBuilder();
                if (i < header.length && !header[i].isEmpty()) {
                    l.append(header[i]).append("  ");
                    l.setSpan(new android.text.style.ForegroundColorSpan(Eva.GOLD_DIM), 0, l.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                Markdown.inline(l, cells[i]);
                line.setText(l);
                card.addView(line, matchWrap(dp(3)));
            }
            cards.addView(card, matchWrap(r == 1 ? 0 : dp(6)));
        }
    }

    private void fillTable(TableLayout t, String src) {
        t.removeAllViews();
        String[] lines = src.split("\n");
        boolean header = true;
        for (int r = 0; r < lines.length; r++) {
            if (r == 1 && TABLE_SEP.matcher(lines[r]).matches()) continue;
            String row = lines[r].trim().replaceAll("^\\|", "").replaceAll("\\|$", "");
            TableRow tr = new TableRow(getContext());
            for (String cell : row.split("(?<!\\\\)\\|", -1)) {
                TextView v = text(14, header ? Eva.AMBER_HI : Eva.CREAM);
                SpannableStringBuilder sb = new SpannableStringBuilder();
                Markdown.inline(sb, cell.trim().replace("<br>", "\n"));
                v.setText(sb);
                if (header) v.setTypeface(Typeface.DEFAULT_BOLD);
                v.setLineSpacing(0, 1.2f);
                v.setPadding(dp(8), dp(8), dp(8), dp(8));
                v.setGravity(Gravity.TOP | Gravity.START);
                tr.addView(v);
            }
            t.addView(tr);
            View line = new View(getContext());
            line.setBackgroundColor(header ? Eva.LINE_HI : Eva.LINE);
            t.addView(line, new TableLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1)));
            header = false;
        }
    }

    private void loadImage(ImageView v, String url) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(10000);
                byte[] data;
                try (InputStream in = c.getInputStream()) {
                    data = in.readAllBytes();
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                o.inSampleSize = 1;
                while (o.outWidth / o.inSampleSize > 1280 || o.outHeight / o.inSampleSize > 720) o.inSampleSize *= 2;
                o.inJustDecodeBounds = false;
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (bmp != null) ui.post(() -> v.setImageBitmap(bmp));
            } catch (Exception ignored) {
                // Broken image links are common in model output; just leave the space empty.
            }
        }, "image").start();
    }

    // ------------------------------------------------------------------ helpers

    void setCompactTables(boolean compact) {
        if (compact == compactTables) return;
        compactTables = compact;
        for (int k = 0; k < rendered.size(); k++) {
            if (rendered.get(k).kind != Kind.TABLE) continue;
            View old = views.get(k);
            View fresh = create(rendered.get(k), false);
            int index = body.indexOfChild(old);
            body.removeViewAt(index);
            body.addView(fresh, index, old.getLayoutParams());
            views.set(k, fresh);
        }
    }

    void setScale(float scale) {
        this.scale = scale;
        for (int i = 0; i < getChildCount(); i++) TextScale.apply(getChildAt(i), scale);
    }

    private TextView text(int sp, int color) {
        TextView v = new TextView(getContext());
        TextScale.init(v, sp, scale);
        v.setTextColor(color);
        return v;
    }

    private LayoutParams matchWrap(int top) {
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
