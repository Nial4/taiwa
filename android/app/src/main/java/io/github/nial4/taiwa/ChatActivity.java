package io.github.nial4.taiwa;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The side panel. One instance == one ChatGPT session: every Assistant press while it is open adds
 * a turn to the same conversation; Back (or leaving the panel) ends the session.
 */
public class ChatActivity extends Activity
        implements Recorder.Listener, RelayClient.Callback, Speaker.Listener, SettingsView.Host {
    private enum State { IDLE, LISTENING, TRANSCRIBING, THINKING, ANSWERING, SPEAKING }

    private static final String TAG = "Taiwa";
    private static final long ASSIST_DEBOUNCE_MS = 800;
    private static final long RENDER_INTERVAL_MS = 80;

    private final String sessionId = UUID.randomUUID().toString();
    private State state = State.IDLE;
    private RelayClient relay;
    private Recorder recorder;
    private RelayClient.Call call;
    private Speaker speaker;

    private View panel;
    private ScrollView scroll;
    private LinearLayout messages;
    private TextView status;
    private TextView title;
    private TextView section;
    private TextView model;
    private TextView statusCode;
    private TextView statusHint;
    private View composer;
    private VoiceView voice;
    private SettingsView settings;
    private Prefs prefs;

    // The answer being streamed for the current turn.
    private AnswerView answerView;
    private final StringBuilder answer = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private long thinkingSince;
    private int thoughtSeconds = -1;
    private boolean searched;
    private boolean answerDone;
    /** The current turn's request, kept so it can be re-sent after re-finding the relay. */
    private interface Turn {
        RelayClient.Call send(RelayClient relay);
    }

    private Turn lastTurn;
    private boolean rediscovered;
    private boolean renderPending;
    private boolean wide;
    private boolean followTail = true;
    private boolean closing;
    private long lastAssistAt;

    /** Debug builds only: `--es ui_locale ja-JP` on ConfigReceiver shows the UI in another language (screenshots). */
    @Override
    protected void attachBaseContext(Context base) {
        String tag = BuildConfig.DEBUG
                ? base.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).getString("ui_locale", null) : null;
        if (tag != null) {
            Configuration c = new Configuration(base.getResources().getConfiguration());
            c.setLocale(java.util.Locale.forLanguageTag(tag));
            base = base.createConfigurationContext(c);
        }
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate session=" + sessionId + " task=" + getTaskId() + " action=" + getIntent().getAction());
        Eva.init(this);
        setContentView(R.layout.activity_chat);
        panel = findViewById(R.id.panel);
        panel.setBackground(new HudFrame(getResources().getDisplayMetrics().density));
        scroll = findViewById(R.id.scroll);
        messages = findViewById(R.id.messages);
        status = findViewById(R.id.status);
        title = findViewById(R.id.title);
        section = findViewById(R.id.section);
        model = findViewById(R.id.model);
        statusCode = findViewById(R.id.status_code);
        statusHint = findViewById(R.id.status_hint);
        showChatTitle();
        composer = findViewById(R.id.composer);
        voice = findViewById(R.id.voice);
        scroll.requestFocus();
        prefs = new Prefs(this);
        speaker = new Speaker(this, this);

        panel.post(() -> {
            panel.setTranslationX(panel.getWidth() + dp(40));
            panel.animate().translationX(0).setDuration(260).setInterpolator(new DecelerateInterpolator(2f)).start();
        });

        relay = RelayClient.fromPrefs(this);
        if (relay == null) {
            addNotice(getString(R.string.err_not_configured), true);
        } else {
            relay.status((json, error) -> {
                if (closing) return;
                if (json != null) {
                    applyRelayStatus(json);
                    if (!json.optBoolean("signed_in")) addNotice(getString(R.string.err_not_signed_in), true);
                } else if (messages.getChildCount() == 0 && state == State.IDLE) {
                    addNotice(getString(R.string.err_network, relay.host()), true);
                }
            });
        }
        if (!handleAssist(getIntent())) addNotice(getString(R.string.welcome), false);
        setState(state);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.i(TAG, "onNewIntent session=" + sessionId + " action=" + intent.getAction() + " flags=0x" + Integer.toHexString(intent.getFlags()));
        setIntent(intent);
        handleAssist(intent);
    }

    private boolean handleAssist(Intent intent) {
        String audition = intent == null ? null : intent.getStringExtra("debug_voices");
        if (BuildConfig.DEBUG && audition != null) {
            speaker.audition(audition);
            return true;
        }
        String debugText = intent == null ? null : intent.getStringExtra("debug_text");
        if (BuildConfig.DEBUG && debugText != null && relay != null) {
            // adb shell am start -n io.github.nial4.taiwa/.ChatActivity --es debug_text "..." (debug builds only)
            answerView = null;
            setState(State.TRANSCRIBING);
            send(r -> r.textTurn(sessionId, debugText, prefs.autoSpeak(), this));
            return true;
        }
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_ASSIST.equals(action) && !"android.intent.action.VOICE_ASSIST".equals(action)) return false;
        startListening();
        return true;
    }

    // ---------------------------------------------------------------- turn flow

    private void startListening() {
        long now = SystemClock.uptimeMillis();
        if (now - lastAssistAt < ASSIST_DEBOUNCE_MS || closing) return;
        lastAssistAt = now;
        if (relay == null) return;
        if (settings != null && settings.getVisibility() == View.VISIBLE) closeSettings();
        speaker.stop();  // never record our own voice
        if (call != null) { // Assistant pressed while ChatGPT is answering: stop it, like ChatGPT's stop button
            call.cancel();
            call = null;
            if (!answerDone) finishAnswer(true);
        }
        if (recorder != null) recorder.cancel();
        answerView = null;
        recorder = new Recorder(this);
        recorder.start();
        setState(State.LISTENING);
    }

    @Override
    public void onLevel(float level) {
        voice.setLevel(level);
    }

    @Override
    public void onSpeechEnd(byte[] pcm) {
        recorder = null;
        setState(State.TRANSCRIBING);
        send(r -> r.voiceTurn(sessionId, pcm, prefs.autoSpeak(), this));
    }

    @Override
    public void onRecorderError(int code) {
        recorder = null;
        if (code == Recorder.ERR_MIC_CLOSED) addNotice(getString(R.string.err_mic_closed), true);
        else if (code == Recorder.ERR_NO_SPEECH) addNotice(getString(R.string.err_no_speech), false);
        else addNotice(getString(R.string.err_mic_unavailable), true);
        setState(State.IDLE);
    }

    @Override
    public void onStatus(String stage) {
        if ("thinking".equals(stage)) setState(State.THINKING);
        else if ("transcribing".equals(stage)) setState(State.TRANSCRIBING);
    }

    @Override
    public void onTranscript(String text) {
        addUserBubble(text);
        answer.setLength(0);
        reasoning.setLength(0);
        thinkingSince = SystemClock.uptimeMillis();
        thoughtSeconds = -1;
        searched = false;
        answerDone = false;
        setState(State.THINKING);
    }

    @Override
    public void onReasoning(String text) {
        reasoning.append(text);
        if (answer.length() == 0) ensureAnswerView().setThought(reasoning.toString());
        if (followTail) scrollToEnd();
    }

    @Override
    public void onSearch(String searchState) {
        AnswerView v = ensureAnswerView();
        if ("searching".equals(searchState)) {
            v.setMeta(getString(R.string.searching));
            if (state == State.THINKING) showStatus("検索", "WEB", Eva.AMBER_HI, getString(R.string.searching), null);
        } else {
            searched = true;
            v.setMeta(meta());
            if (state == State.THINKING) setState(State.THINKING);
        }
    }

    @Override
    public void onCitations(JSONArray items) {
        List<String[]> list = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o != null) list.add(new String[]{o.optString("url"), o.optString("title")});
        }
        ensureAnswerView().setSources(list);
    }

    @Override
    public void onDelta(String text) {
        AnswerView v = ensureAnswerView();
        if (answer.length() == 0) {
            if (reasoning.length() > 0) thoughtSeconds = (int) ((SystemClock.uptimeMillis() - thinkingSince + 500) / 1000);
            v.hideThought();
            v.setMeta(meta());
        }
        answer.append(text);
        if (state != State.ANSWERING) setState(State.ANSWERING);
        if (!renderPending) {
            renderPending = true;
            scroll.postDelayed(this::renderAnswer, RENDER_INTERVAL_MS);
        }
    }

    private void renderAnswer() {
        renderPending = false;
        if (answerView == null) return;
        answerView.setMarkdown(answer.toString(), true);
        if (followTail) scrollToEnd();
    }

    @Override
    public void onAnswerDone() {
        answerDone = true;
        finishAnswer(false);
        speaker.begin();
        setState(State.IDLE);
    }

    @Override
    public void onSpeech(String sentence) {
        speaker.say(sentence);
    }

    @Override
    public void onSpeakingChanged(boolean speaking) {
        if (speaking && state == State.IDLE) setState(State.SPEAKING);
        else if (!speaking && state == State.SPEAKING) setState(State.IDLE);
    }

    @Override
    public void onDone() {
        call = null;
        if (!answerDone) finishAnswer(false);
        if (state != State.SPEAKING) setState(State.IDLE);
    }

    /** OK while idle: replay the spoken version, or fetch one when auto-read is off. */
    private void speakLastAnswer() {
        if (speaker.canReplay()) {
            speaker.replay();
        } else if (answerDone && relay != null && call == null) {
            speaker.begin();
            showStatus("朗読", "READ", Eva.AMBER_HI, getString(R.string.preparing_speech), null);
            call = relay.speech(sessionId, this);
        }
    }

    @Override
    public void onError(String code, String message) {
        call = null;
        String text = Messages.error(this, code, message, relay == null ? "" : relay.host());
        if ("network".equals(code) && lastTurn != null && !rediscovered && !answerDone) {
            rediscover(text);
            return;
        }
        finishAnswer(false);
        addNotice(text, !"no_speech".equals(code));
        setState(State.IDLE);
    }

    private AnswerView ensureAnswerView() {
        if (answerView == null) {
            answerView = new AnswerView(this, scale(), !wide);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(18);
            messages.addView(answerView, lp);
        }
        return answerView;
    }

    private String meta() {
        StringBuilder sb = new StringBuilder();
        if (thoughtSeconds >= 0) sb.append(getString(R.string.thought_for, Math.max(1, thoughtSeconds)));
        if (searched) sb.append(sb.length() > 0 ? " · " : "").append(getString(R.string.searched));
        return sb.toString();
    }

    private void finishAnswer(boolean stopped) {
        if (answerView == null) return;
        String text = answer.toString() + (stopped ? "\n\n" + getString(R.string.stopped) : "");
        answerView.hideThought();
        answerView.setMeta(meta());
        answerView.setMarkdown(text, false);
        answerView = null;
        if (followTail) scrollToEnd();
    }

    private void send(Turn turn) {
        lastTurn = turn;
        rediscovered = false;
        call = turn.send(relay);
    }

    /** The relay did not answer: look for it on the LAN (its IP may have changed) and re-send once. */
    private void rediscover(String failure) {
        rediscovered = true;
        showStatus("接続", "LINK", Eva.RED, getString(R.string.relinking), null);
        new Discovery(this).run(4000, found -> {
            if (closing) return;
            if (found.isEmpty()) {
                addNotice(failure, true);
                setState(State.IDLE);
                return;
            }
            String url = found.get(0)[1];
            new RelayClient(url, prefs.token()).status((json, error) -> {
                if (closing) return;
                if (json == null) {
                    addNotice(failure, true);
                    setState(State.IDLE);
                    return;
                }
                prefs.setUrl(url);
                relayChanged();
                Log.i(TAG, "relay re-discovered at " + url);
                setState(State.TRANSCRIBING);
                call = lastTurn.send(relay);
            });
        });
    }

    // ---------------------------------------------------------------- settings (SettingsView.Host)

    private void openSettings() {
        if (settings == null) {
            settings = new SettingsView(this, this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
            ((LinearLayout) panel).addView(settings, 2, lp);  // after the header, in place of the chat
        }
        if (recorder != null) {
            recorder.cancel();
            recorder = null;
            setState(State.IDLE);
        }
        scroll.setVisibility(View.GONE);
        composer.setVisibility(View.GONE);
        model.setVisibility(View.GONE);
        settings.setVisibility(View.VISIBLE);
        settings.open();
    }

    @Override
    public void closeSettings() {
        speaker.stop();
        settings.setVisibility(View.GONE);
        scroll.setVisibility(View.VISIBLE);
        composer.setVisibility(View.VISIBLE);
        model.setVisibility(View.VISIBLE);
        showChatTitle();
        scroll.requestFocus();
        setState(state);
    }

    private boolean settingsOpen() {
        return settings != null && settings.getVisibility() == View.VISIBLE;
    }

    @Override
    public Speaker speaker() {
        return speaker;
    }

    @Override
    public RelayClient relay() {
        return relay;
    }

    @Override
    public void relayChanged() {
        relay = RelayClient.fromPrefs(this);
        if (relay != null) relay.status((json, error) -> {
            if (json != null && !closing) applyRelayStatus(json);
        });
    }

    @Override
    public void remoteSettingsChanged(org.json.JSONObject json) {
        applyRelayStatus(json);
    }

    /** Model label in the header; the relay's conversation language drives the TV's voice. */
    private void applyRelayStatus(org.json.JSONObject json) {
        setModelLabel(json.optString("model", ""));
        String language = json.optString("language", "");
        if (!language.isEmpty()) speaker.setLanguage(language);
    }

    @Override
    public void setTitle(String page) {
        title.setText(Eva.label("設定", "CONFIG", 0.82f));
        section.setText(page == null ? "SEC-03" : "// " + page);
    }

    private void showChatTitle() {
        title.setText(Eva.label("対話", "DIALOGUE", 0.82f));
        section.setText("SEC-02");
    }

    private void setModelLabel(String slug) {
        model.setText(slug.isEmpty() ? "" : slug.toUpperCase(java.util.Locale.ROOT));
    }

    private void setState(State s) {
        state = s;
        switch (s) {
            case LISTENING:
                voice.setMode(VoiceView.MODE_BARS);
                showStatus("録音", "REC", Eva.RED, getString(R.string.listening), getString(R.string.hint_finish));
                break;
            case TRANSCRIBING:
                voice.setMode(VoiceView.MODE_PULSE);
                showStatus("認識", "STT", Eva.AMBER_HI, getString(R.string.transcribing), null);
                break;
            case THINKING:
                voice.setMode(VoiceView.MODE_PULSE);
                showStatus("思考", "PROC", Eva.AMBER_HI, getString(R.string.thinking), null);
                break;
            case SPEAKING:
                voice.setMode(VoiceView.MODE_SPEAK);
                showStatus("朗読", "READ", Eva.AMBER_HI, getString(R.string.speaking), getString(R.string.hint_stop));
                break;
            case ANSWERING:
                voice.setMode(VoiceView.MODE_PULSE);
                showStatus("応答", "RESP", Eva.AMBER_HI, getString(R.string.writing), null);
                break;
            default:
                voice.setMode(VoiceView.MODE_NONE);
                boolean canHear = speaker.canReplay() || answerDone;
                showStatus("待機", "STBY", Eva.GOLD,
                        getString(hasConversation() ? R.string.press_to_continue : R.string.press_to_talk),
                        canHear ? getString(R.string.hint_replay) : null);
        }
    }

    /** Status strip: "漢字 CODE" tag, LED meter, status text, key hint on the right. */
    private void showStatus(String kanji, String code, int codeColor, String text, String hintText) {
        statusCode.setText(Eva.label(kanji, code, 0.8f));
        statusCode.setTextColor(codeColor);
        status.setText(text);
        statusHint.setText(hintText == null ? "" : hintText);
    }

    // ---------------------------------------------------------------- messages

    private boolean hasConversation() {
        for (int i = 0; i < messages.getChildCount(); i++) {
            if ("user".equals(messages.getChildAt(i).getTag())) return true;
        }
        return false;
    }

    private void addUserBubble(String text) {
        TextView label = text(13, 0);
        label.setText(Eva.label("入力", "INPUT", 0.85f));
        label.setTextColor(Eva.GOLD_DIM);
        label.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        llp.gravity = Gravity.END;
        llp.topMargin = dp(messages.getChildCount() == 0 ? 6 : 24);
        messages.addView(label, llp);

        TextView v = text(16, 0);
        v.setTextColor(Eva.CREAM);
        v.setTag("user");
        v.setText(text);
        v.setLineSpacing(0, 1.2f);
        v.setBackground(Eva.frame(Eva.SURFACE, Eva.LINE_HI, 1, getResources().getDisplayMetrics().density));
        v.setPadding(dp(14), dp(10), dp(14), dp(10));
        v.setMaxWidth(wide ? dp(640) : dp(300));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        lp.topMargin = dp(4);
        messages.addView(v, lp);
        followTail = true;
        scrollToEnd();
    }

    private void addNotice(String message, boolean error) {
        float density = getResources().getDisplayMetrics().density;
        int accent = error ? Eva.RED : Eva.GOLD_DIM;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Eva.frame(error ? 0x1AD8323A : Eva.SURFACE, accent, 1, density));
        box.setPadding(dp(14), dp(8), dp(14), dp(10));
        TextView label = text(13, 0);
        label.setText(error ? Eva.label("警告", "WARNING", 0.85f) : Eva.label("通知", "NOTICE", 0.85f));
        label.setTextColor(error ? Eva.RED : Eva.GOLD);
        label.setLetterSpacing(0.08f);
        box.addView(label);
        TextView v = text(14, 0);
        v.setTextColor(Eva.CREAM_DIM);
        v.setText(message);
        v.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(2);
        box.addView(v, tlp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        messages.addView(box, lp);
        scrollToEnd();
    }

    private TextView text(int sp, int colorRes) {
        TextView v = new TextView(this);
        TextScale.init(v, sp, scale());
        if (colorRes != 0) v.setTextColor(getColor(colorRes));
        return v;
    }

    private float scale() {
        return wide ? TextScale.WIDE : 1f;
    }

    private void scrollToEnd() {
        scroll.post(() -> scroll.smoothScrollTo(0, messages.getHeight()));
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (settingsOpen() && code != KeyEvent.KEYCODE_BACK) {
            return settings.handleKey(e) || super.dispatchKeyEvent(e);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (down) scrollPage(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (down) scrollPage(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down && e.getRepeatCount() == 0) {
                    if (state == State.LISTENING && recorder != null) recorder.finishNow();
                    else if (speaker.isSpeaking()) speaker.stop();
                    else if (state == State.IDLE) speakLastAnswer();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down && e.getRepeatCount() == 0) setWide(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && e.getRepeatCount() == 0) {
                    if (wide) setWide(false);
                    else openSettings();
                }
                return true;
            case KeyEvent.KEYCODE_SEARCH:
            case KeyEvent.KEYCODE_ASSIST:
            case KeyEvent.KEYCODE_VOICE_ASSIST:
                if (down && e.getRepeatCount() == 0) startListening();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    /** Wide mode gives tables and long answers almost the whole screen. */
    private void setWide(boolean on) {
        if (wide == on) return;
        wide = on;
        View root = (View) panel.getParent();
        int margin = getResources().getDimensionPixelSize(R.dimen.panel_margin);
        int target = on ? root.getWidth() - 2 * margin : getResources().getDimensionPixelSize(R.dimen.panel_width);
        ValueAnimator a = ValueAnimator.ofInt(panel.getWidth(), target);
        a.setDuration(220);
        a.setInterpolator(new DecelerateInterpolator(1.5f));
        a.addUpdateListener(an -> {
            panel.getLayoutParams().width = (int) an.getAnimatedValue();
            panel.requestLayout();
        });
        a.start();
        TextScale.apply(messages, scale());
        int bubbleMax = on ? dp(640) : dp(300);
        for (int i = 0; i < messages.getChildCount(); i++) {
            View v = messages.getChildAt(i);
            if ("user".equals(v.getTag())) ((TextView) v).setMaxWidth(bubbleMax);
            if (v instanceof AnswerView) ((AnswerView) v).setCompactTables(!on);
        }
    }

    private void scrollPage(int direction) {
        int step = Math.max(dp(80), scroll.getHeight() / 3);
        scroll.smoothScrollBy(0, direction * step);
        if (direction < 0) followTail = false;
        else scroll.post(() -> followTail = !scroll.canScrollVertically(1));
    }

    @Override
    public void onBackPressed() {
        if (settingsOpen()) {
            settings.back();
            return;
        }
        if (state == State.LISTENING && recorder != null) {
            recorder.cancel();
            recorder = null;
            setState(State.IDLE);
            return;
        }
        close();
    }

    // ---------------------------------------------------------------- lifecycle

    private void close() {
        if (closing) return;
        closing = true;
        endSession();
        panel.animate().translationX(panel.getWidth() + dp(40)).setDuration(200)
                .setInterpolator(new AccelerateInterpolator(1.5f))
                .withEndAction(() -> {
                    finish();
                    overridePendingTransition(0, 0);
                }).start();
    }

    private void endSession() {
        speaker.release();
        if (recorder != null) recorder.cancel();
        recorder = null;
        if (call != null) call.cancel();
        call = null;
        if (relay != null) relay.endSession(sessionId);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause session=" + sessionId + " finishing=" + isFinishing());
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "onStop session=" + sessionId + " closing=" + closing + " finishing=" + isFinishing());
        // Covered by another app or Home pressed: the panel is gone, so is the session.
        if (!closing && !isChangingConfigurations()) {
            closing = true;
            endSession();
            finish();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
