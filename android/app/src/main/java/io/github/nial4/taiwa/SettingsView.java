package io.github.nial4.taiwa;

import android.content.Context;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.Voice;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Settings pages inside the panel (▶ from the chat). ▲▼ move, OK/▶ open or toggle, ◀/Back go up.
 * TV-side options live in {@link Prefs}; model, reasoning and web search are stored on the relay.
 */
final class SettingsView extends ScrollView {
    interface Host {
        Speaker speaker();
        RelayClient relay();
        /** Prefs now point at another relay. */
        void relayChanged();
        void remoteSettingsChanged(JSONObject settings);
        /** @param page the sub-page name, or null for the settings root */
        void setTitle(String page);
        void closeSettings();
    }

    private static final float[] RATES = {0.8f, 0.9f, 1f, 1.1f, 1.25f, 1.5f};
    private static final String[] EFFORTS = {null, "low", "medium", "high"};
    private static final int[] EFFORT_LABELS = {
            R.string.effort_default, R.string.effort_low, R.string.effort_medium, R.string.effort_high};

    private interface Page {
        void build();
    }

    private final Host host;
    private final Prefs prefs;
    private final LinearLayout list;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Page> pages = new ArrayDeque<>();
    private final ArrayDeque<Integer> focusStack = new ArrayDeque<>();
    private JSONObject remote;
    private String remoteError;
    private Runnable pendingPreview;
    private boolean previewArmed;
    private int rowNumber;

    SettingsView(Context context, Host host) {
        super(context);
        this.host = host;
        this.prefs = new Prefs(context);
        setVerticalScrollBarEnabled(false);
        setFadingEdgeLength(dp(28));
        setVerticalFadingEdgeEnabled(true);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(4), dp(16), dp(20));
        addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    void open() {
        pages.clear();
        focusStack.clear();
        push(this::rootPage);
        loadRemote();
    }

    // ------------------------------------------------------------------ navigation

    /** @return true when the key was handled here */
    boolean handleKey(KeyEvent e) {
        int code = e.getKeyCode();
        View focus = findFocus();
        boolean editing = focus instanceof EditText;
        if (code == KeyEvent.KEYCODE_DPAD_LEFT && !editing) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) back();
            return true;
        }
        boolean activate = code == KeyEvent.KEYCODE_DPAD_RIGHT && !editing
                || (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && !editing;
        if (activate) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && focus != null && focus.hasOnClickListeners()) {
                focus.performClick();
            }
            return true;
        }
        return false;
    }

    void back() {
        cancelPreview();
        pages.pop();
        if (pages.isEmpty()) {
            host.closeSettings();
            return;
        }
        int focusIndex = focusStack.isEmpty() ? 0 : focusStack.pop();
        render(focusIndex);
    }

    private void push(Page page) {
        if (!pages.isEmpty()) focusStack.push(focusedIndex());
        pages.push(page);
        render(-1);
    }

    /** Rebuild the current page, keeping the focused row. */
    private void refresh() {
        render(focusedIndex());
    }

    private void render(int focusIndex) {
        previewArmed = false;
        rowNumber = 0;
        list.removeAllViews();
        pages.peek().build();
        View target = null;
        int n = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (!v.isFocusable()) continue;
            if (target == null || n == focusIndex || (focusIndex < 0 && v.getTag() == Boolean.TRUE)) target = v;
            if (n == focusIndex) break;
            n++;
        }
        if (target != null) target.requestFocus();
        ui.post(() -> previewArmed = true);  // no preview for the row focused by opening the page
    }

    private int focusedIndex() {
        View f = findFocus();
        int n = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (!v.isFocusable()) continue;
            if (v == f || v == (f == null ? null : f.getParent())) return n;
            n++;
        }
        return 0;
    }

    // ------------------------------------------------------------------ pages

    private void rootPage() {
        host.setTitle(null);
        section("朗読", "VOICE");
        row(str(R.string.set_language), remote == null ? "…" : languageLabel(remote.optString("language")), true,
                () -> push(this::languagePage));
        row(str(R.string.set_auto_read), str(prefs.autoSpeak() ? R.string.on : R.string.off), false, () -> {
            prefs.setAutoSpeak(!prefs.autoSpeak());
            refresh();
        });
        boolean ttsReady = host.speaker().isReady();
        row(str(R.string.set_voice), ttsReady ? voiceLabel(host.speaker().currentVoice()) : "…", true,
                () -> push(this::voicePage));
        if (!ttsReady) {
            // The TTS engine may still be cold-starting (~14 s on some TVs); show the voice once it is up.
            Page self = pages.peek();
            ui.postDelayed(() -> { if (pages.peek() == self && getVisibility() == VISIBLE) refresh(); }, 1000);
        }
        row(str(R.string.set_rate), rateLabel(prefs.speechRate()), true, () -> push(this::ratePage));

        section("対話", "CHATGPT");
        row(str(R.string.set_model), remote == null ? "…" : modelLabel(remote.optString("model")), true,
                () -> push(this::modelPage));
        row(str(R.string.set_effort), remote == null ? "…" : effortLabel(remote.isNull("reasoning_effort") ? null
                : remote.optString("reasoning_effort")), true, () -> push(this::effortPage));
        row(str(R.string.set_web_search), remote == null ? "…" : str(remote.optBoolean("web_search") ? R.string.on : R.string.off),
                false, () -> {
                    if (remote != null) update("web_search", !remote.optBoolean("web_search"), false);
                });

        section("接続", "LINK");
        row(str(R.string.set_relay), hostOf(prefs.url()), true, () -> push(this::relayPage));
        JSONObject account = remote == null ? null : remote.optJSONObject("account");
        String who = remote == null ? (remoteError != null ? str(R.string.conn_failed) : "…")
                : account != null && account.optBoolean("signed_in") ? account.optString("email") : str(R.string.signed_out);
        row(str(R.string.set_account), who, false, null);
        row(str(R.string.set_version), BuildConfig.VERSION_NAME, false, null);
    }

    private void languagePage() {
        host.setTitle(str(R.string.page_language));
        JSONArray langs = remote == null ? null : remote.optJSONArray("languages");
        if (langs == null) {
            note(remoteError != null ? error(remoteError) : "…");
            return;
        }
        String current = remote.optString("language");
        for (int i = 0; i < langs.length(); i++) {
            JSONObject l = langs.optJSONObject(i);
            String code = l.optString("code");
            View r = row(l.optString("native"), code.equals(current) ? "■" : code, false,
                    () -> update("language", code, true));
            r.setTag(code.equals(current));
        }
        note(str(R.string.note_language));
    }

    private void voicePage() {
        host.setTitle(str(R.string.page_voice));
        if (!host.speaker().isReady()) {
            note(str(R.string.note_tts_starting));
            return;
        }
        List<Voice> voices = host.speaker().voices();
        if (voices.isEmpty()) {
            note(str(R.string.note_no_voices));
            return;
        }
        String current = host.speaker().currentVoice();
        for (Voice v : voices) {
            String name = v.getName();
            View r = row(voiceLabel(name), name.equals(current) ? "■" : "", false, () -> {
                prefs.setVoice(name);
                host.speaker().configure(name, prefs.speechRate());
                back();
            });
            r.setTag(name.equals(current));
            r.setOnFocusChangeListener((view, has) -> {
                if (has) schedulePreview(name, prefs.speechRate());
            });
        }
        note(str(R.string.note_voice));
    }

    private void ratePage() {
        host.setTitle(str(R.string.page_rate));
        float current = prefs.speechRate();
        for (float rate : RATES) {
            View r = row(rateLabel(rate), Math.abs(rate - current) < 0.01f ? "■" : "", false, () -> {
                prefs.setSpeechRate(rate);
                host.speaker().configure(prefs.voice(), rate);
                back();
            });
            r.setTag(Math.abs(rate - current) < 0.01f);
            r.setOnFocusChangeListener((view, has) -> {
                if (has) schedulePreview(host.speaker().currentVoice(), rate);
            });
        }
    }

    private void modelPage() {
        host.setTitle(str(R.string.page_model));
        JSONArray models = remote == null ? null : remote.optJSONArray("models");
        if (models == null || models.length() == 0) {
            note(remoteError != null ? error(remoteError) : str(R.string.loading_models));
            return;
        }
        String current = remote.optString("model");
        for (int i = 0; i < models.length(); i++) {
            JSONObject m = models.optJSONObject(i);
            String slug = m.optString("slug");
            View r = row(m.optString("display_name", slug), slug.equals(current) ? "■" : "", false,
                    () -> update("model", slug, true));
            r.setTag(slug.equals(current));
        }
        note(str(R.string.note_model));
    }

    private void effortPage() {
        host.setTitle(str(R.string.page_effort));
        String current = remote == null || remote.isNull("reasoning_effort") ? null : remote.optString("reasoning_effort");
        for (int i = 0; i < EFFORTS.length; i++) {
            String value = EFFORTS[i];
            boolean selected = value == null ? current == null : value.equals(current);
            View r = row(str(EFFORT_LABELS[i]), selected ? "■" : "", false,
                    () -> update("reasoning_effort", value == null ? JSONObject.NULL : value, true));
            r.setTag(selected);
        }
        note(str(R.string.note_effort));
    }

    private void relayPage() {
        host.setTitle(str(R.string.page_relay));
        row(str(R.string.relay_current), hostOf(prefs.url()), false, null);
        row(str(R.string.page_discover), "", true, () -> push(this::discoverPage));
        row(str(R.string.page_manual), "", true, () -> push(this::manualPage));
        note(str(R.string.note_relay));
    }

    private void discoverPage() {
        host.setTitle(str(R.string.page_discover));
        note(str(R.string.discovering));
        Page self = pages.peek();
        new Discovery(getContext()).run(4000, found -> {
            if (pages.peek() != self) return;
            list.removeAllViews();
            rowNumber = 0;
            if (found.isEmpty()) {
                note(str(R.string.discover_none));
                row(str(R.string.discover_again), "", false, this::refresh);
            }
            for (String[] f : found) row(f[0], hostOf(f[1]), false, () -> connect(f[1]));
            View first = firstFocusable();
            if (first != null) first.requestFocus();
        });
    }

    private void manualPage() {
        host.setTitle(str(R.string.page_manual));
        EditText edit = new EditText(getContext());
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        edit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        edit.setText(prefs.url() == null ? "" : hostOf(prefs.url()));
        edit.setTextColor(Eva.CREAM);
        edit.setTypeface(Eva.mono);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        edit.setBackground(Eva.frame(Eva.SURFACE, Eva.AMBER, 1, getResources().getDisplayMetrics().density));
        edit.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        list.addView(edit, lp);
        row(str(R.string.manual_save), "", false, () -> {
            String t = edit.getText().toString().trim().replaceFirst("^https?://", "");
            if (t.isEmpty()) return;
            connect("http://" + (t.contains(":") ? t : t + ":8787"));
        });
        note(str(R.string.manual_hint));
    }

    // ------------------------------------------------------------------ actions

    private void connect(String url) {
        note(getContext().getString(R.string.connecting, hostOf(url)));
        new RelayClient(url, prefs.token()).status((json, errorCode) -> {
            if (json == null) {
                note(getContext().getString(R.string.connect_failed, Messages.error(getContext(), errorCode, errorCode, hostOf(url))));
                return;
            }
            prefs.setUrl(url);
            host.relayChanged();
            loadRemote();
            // Back to the relay page, which shows the new address.
            while (pages.size() > 2) pages.pop();
            focusStack.clear();
            render(0);
            note(getContext().getString(R.string.connected, hostOf(url)));
        });
    }

    private void loadRemote() {
        RelayClient relay = host.relay();
        if (relay == null) {
            remoteError = "not_configured";
            return;
        }
        relay.settings((json, errorCode) -> {
            remote = json;
            remoteError = errorCode;
            if (json != null) host.remoteSettingsChanged(json);
            if (!pages.isEmpty()) refresh();
        });
    }

    private void update(String key, Object value, boolean goBack) {
        RelayClient relay = host.relay();
        if (relay == null) return;
        JSONObject change = new JSONObject();
        try {
            change.put(key, value);
        } catch (JSONException e) {
            return;
        }
        relay.updateSettings(change, (json, errorCode) -> {
            if (json == null) {
                note(getContext().getString(R.string.save_failed, error(errorCode)));
                return;
            }
            remote = json;
            host.remoteSettingsChanged(json);
            if (goBack) back();
            else refresh();
        });
    }

    private void schedulePreview(String voice, float rate) {
        cancelPreview();
        if (!previewArmed) return;
        pendingPreview = () -> host.speaker().preview(voice, rate, previewText());
        ui.postDelayed(pendingPreview, 350);
    }

    private void cancelPreview() {
        if (pendingPreview != null) ui.removeCallbacks(pendingPreview);
        pendingPreview = null;
    }

    // ------------------------------------------------------------------ labels

    private String str(int res) {
        return getContext().getString(res);
    }

    private String error(String code) {
        if ("not_configured".equals(code)) return str(R.string.err_not_configured);
        return Messages.error(getContext(), code, code, hostOf(prefs.url()));
    }

    /** The relay's per-language preview phrase ("Hi, hello", "Hi，你好", "こんにちは", ...). */
    private String previewText() {
        JSONArray langs = remote == null ? null : remote.optJSONArray("languages");
        for (int i = 0; langs != null && i < langs.length(); i++) {
            JSONObject l = langs.optJSONObject(i);
            if (l.optString("code").equals(prefs.language())) return l.optString("preview", "Hi");
        }
        return "Hi";
    }

    private String languageLabel(String code) {
        JSONArray langs = remote == null ? null : remote.optJSONArray("languages");
        for (int i = 0; langs != null && i < langs.length(); i++) {
            JSONObject l = langs.optJSONObject(i);
            if (l.optString("code").equals(code)) return l.optString("native", code);
        }
        return code;
    }

    /** "Chinese (China) 1", numbered in the order of the voice list, "(online)" for network voices. */
    private String voiceLabel(String name) {
        if (name == null) return str(R.string.by_default);
        java.util.Locale ui = getResources().getConfiguration().getLocales().get(0);
        int n = 0;
        for (Voice v : host.speaker().voices()) {
            n++;
            if (v.getName().equals(name)) {
                String label = v.getLocale().getDisplayName(ui) + " " + n;
                return v.isNetworkConnectionRequired() ? getContext().getString(R.string.voice_online, label) : label;
            }
        }
        return str(R.string.by_default);  // the engine's default for the language
    }

    private String rateLabel(float rate) {
        String s = (rate == (int) rate ? String.valueOf((int) rate) : String.valueOf(rate)) + "×";
        return Math.abs(rate - 1f) < 0.01f ? getContext().getString(R.string.rate_normal, s) : s;
    }

    private String modelLabel(String slug) {
        JSONArray models = remote == null ? null : remote.optJSONArray("models");
        for (int i = 0; models != null && i < models.length(); i++) {
            JSONObject m = models.optJSONObject(i);
            if (slug.equals(m.optString("slug"))) return m.optString("display_name", slug);
        }
        return slug.isEmpty() ? str(R.string.not_set) : slug;
    }

    private String effortLabel(String value) {
        for (int i = 0; i < EFFORTS.length; i++) {
            if (value == null ? EFFORTS[i] == null : value.equals(EFFORTS[i])) return str(EFFORT_LABELS[i]);
        }
        return value;
    }

    private String hostOf(String url) {
        if (url == null) return str(R.string.not_set);
        Uri u = Uri.parse(url);
        return u.getHost() == null ? url : u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
    }

    // ------------------------------------------------------------------ views

    /** A KIDŌ-style row: "01  label ........ VALUE ›", solid amber with black text when focused. */
    private View row(String title, String value, boolean chevron, Runnable onClick) {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setBackground(rowBackground());
        r.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView n = text(13, Eva.onAmber(Eva.GOLD_DIM));
        n.setTypeface(Eva.mono);
        n.setText(String.format(java.util.Locale.ROOT, "%02d", ++rowNumber));
        n.setDuplicateParentStateEnabled(true);
        r.addView(n, new LinearLayout.LayoutParams(dp(30), LayoutParams.WRAP_CONTENT));
        TextView t = text(16, Eva.onAmber(Eva.CREAM));
        t.setText(title);
        t.setDuplicateParentStateEnabled(true);
        r.addView(t, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        TextView v = text(15, Eva.onAmber(Eva.GOLD));
        v.setText(value + (chevron ? "  ›" : ""));
        v.setSingleLine(true);
        v.setMaxWidth(dp(220));
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setDuplicateParentStateEnabled(true);
        r.addView(v);
        if (onClick != null) r.setOnClickListener(x -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        list.addView(r, lp);
        return r;
    }

    private void section(String kanji, String english) {
        TextView t = text(14, android.content.res.ColorStateList.valueOf(Eva.GOLD));
        t.setText(Eva.label(kanji, english, 0.85f));
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(14), dp(list.getChildCount() == 0 ? 6 : 22), 0, dp(6));
        list.addView(t);
        View rule = new View(getContext());
        rule.setBackgroundColor(Eva.LINE_HI);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2 + 1));
        lp.bottomMargin = dp(4);
        list.addView(rule, lp);
    }

    private void note(String message) {
        TextView t = text(13, android.content.res.ColorStateList.valueOf(Eva.GOLD_DIM));
        t.setText(message);
        t.setLineSpacing(0, 1.25f);
        t.setPadding(dp(14), dp(14), dp(14), 0);
        list.addView(t);
    }

    private View firstFocusable() {
        for (int i = 0; i < list.getChildCount(); i++) {
            if (list.getChildAt(i).isFocusable()) return list.getChildAt(i);
        }
        return null;
    }

    private StateListDrawable rowBackground() {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused}, Eva.frame(Eva.AMBER, 0, 0, 1));
        d.addState(new int[]{}, Eva.frame(0, 0, 0, 1));
        return d;
    }

    private TextView text(int sp, android.content.res.ColorStateList colors) {
        TextView v = new TextView(getContext());
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(colors);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
