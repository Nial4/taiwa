package io.github.nial4.taiwa;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the relay's spoken version aloud with the TV's TTS engine in the conversation language, one
 * sentence at a time as they arrive, ducking whatever else is playing while it talks.
 *
 * The engine binding is shared by every panel for the life of the process: on some TVs a cold
 * Google TTS start takes ~14 s, so it is kept warm instead of being shut down with each panel.
 */
final class Speaker {
    interface Listener {
        void onSpeakingChanged(boolean speaking);
    }

    private static final String TAG = "TaiwaTts";
    private static final AudioAttributes ATTRS = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

    private final Context context;
    private final AudioManager audio;
    private final AudioFocusRequest focus;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> pending = new ArrayList<>();
    private final List<String> lastSpeech = new ArrayList<>();
    private static TextToSpeech shared;
    private static boolean sharedReady;
    private static final List<Speaker> waiting = new ArrayList<>();
    private TextToSpeech tts;
    private boolean ready;
    private boolean speaking;
    private int queued;
    private int finished;
    private int generation;  // utterance ids carry it, so callbacks from a stopped batch are ignored

    Speaker(Context context, Listener listener) {
        this.listener = listener;
        this.context = context.getApplicationContext();
        audio = context.getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(ATTRS)
                .build();
        if (shared == null) {
            shared = new TextToSpeech(this.context, status -> ui.post(() -> sharedInit(status)));
        }
        tts = shared;
        if (sharedReady) onInit();
        else waiting.add(this);
    }

    private static void sharedInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed: " + status);
            shared = null;
            waiting.clear();
            return;
        }
        sharedReady = true;
        for (Speaker s : waiting) s.onInit();
        waiting.clear();
    }

    private void onInit() {
        if (tts == null) return;
        Prefs prefs = new Prefs(context);
        Locale locale = Locale.forLanguageTag(prefs.language());
        int lang = tts.setLanguage(locale);
        Voice preferred = voice(prefs.voice());
        if (preferred != null && sameLanguage(preferred.getLocale(), locale)) tts.setVoice(preferred);
        tts.setSpeechRate(prefs.speechRate());
        Log.i(TAG, "TTS ready, " + prefs.language() + "=" + lang
                + " voice=" + (tts.getVoice() == null ? null : tts.getVoice().getName()));
        tts.setAudioAttributes(ATTRS);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
            }

            @Override
            public void onDone(String id) {
                ui.post(() -> utteranceEnded(id));
            }

            @Override
            public void onError(String id) {
                ui.post(() -> utteranceEnded(id));
            }

            @Override
            public void onStop(String id, boolean interrupted) {
                ui.post(() -> utteranceEnded(id));
            }
        });
        ready = true;
        for (String s : pending) enqueue(s);
        pending.clear();
    }

    /** The conversation language changed (relay setting): speak it from now on. */
    void setLanguage(String code) {
        Prefs prefs = new Prefs(context);
        if (code == null || code.equals(prefs.language())) return;
        prefs.setLanguage(code);
        Locale locale = Locale.forLanguageTag(code);
        Voice preferred = ready ? voice(prefs.voice()) : null;
        if (preferred != null && !sameLanguage(preferred.getLocale(), locale)) prefs.setVoice(null);
        if (ready) tts.setLanguage(locale);
    }

    /**
     * Voices for the conversation language in a stable order (the numbers shown in settings follow it):
     * the language's own region first, local before network, then by name.
     */
    List<Voice> voices() {
        List<Voice> list = new ArrayList<>();
        if (!ready || tts.getVoices() == null) return list;
        Locale locale = Locale.forLanguageTag(new Prefs(context).language());
        // Google TTS marks voices "notInstalled" until first use, then downloads them by itself: list them all.
        // "zh-CN-language" style entries are the engine's per-language default alias of a real voice: skip them.
        for (Voice v : tts.getVoices()) {
            if (sameLanguage(v.getLocale(), locale) && !v.getName().endsWith("-language")) list.add(v);
        }
        String country = locale.getCountry();
        list.sort((a, b) -> {
            int c = Boolean.compare(!country.equals(a.getLocale().getCountry()), !country.equals(b.getLocale().getCountry()));
            if (c != 0) return c;
            return a.getName().compareTo(b.getName());
        });
        return list;
    }

    private static boolean sameLanguage(Locale a, Locale b) {
        try {
            return a.getISO3Language().equals(b.getISO3Language());
        } catch (java.util.MissingResourceException e) {
            return a.getLanguage().equals(b.getLanguage());
        }
    }

    boolean isReady() {
        return ready;
    }

    String currentVoice() {
        return ready && tts.getVoice() != null ? tts.getVoice().getName() : null;
    }

    private Voice voice(String name) {
        if (name == null || tts.getVoices() == null) return null;
        for (Voice v : tts.getVoices()) {
            if (v.getName().equals(name)) return v;
        }
        return null;
    }

    /** Use this voice and rate from now on (already saved in Prefs by the caller). */
    void configure(String voiceName, float rate) {
        if (!ready) return;
        Voice v = voice(voiceName);
        if (v != null) tts.setVoice(v);
        tts.setSpeechRate(rate);
    }

    /** Short sample with a given voice and rate, without changing the current settings. */
    void preview(String voiceName, float rate, String text) {
        if (!ready) return;
        stop();
        Voice saved = tts.getVoice();
        float savedRate = new Prefs(context).speechRate();
        Voice v = voice(voiceName);
        if (v != null) tts.setVoice(v);
        tts.setSpeechRate(rate);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview");
        if (saved != null) tts.setVoice(saved);
        tts.setSpeechRate(savedRate);
    }

    /** Debug builds: read a sample with every voice of the language, announcing its number first. */
    void audition(String sample) {
        if (!ready) {
            ui.postDelayed(() -> audition(sample), 500);
            return;
        }
        List<Voice> voices = voices();
        Voice original = tts.getVoice();
        int n = 0;
        for (Voice v : voices) {
            n++;
            Log.i(TAG, "audition " + n + " = " + v.getName() + " network=" + v.isNetworkConnectionRequired()
                    + " installed=" + !v.getFeatures().contains("notInstalled"));
            tts.setVoice(v);
            enqueue(n + ". " + sample);
        }
        if (original != null) tts.setVoice(original);
    }

    /** Start a new spoken answer (forgets the previous one). */
    void begin() {
        stop();
        lastSpeech.clear();
    }

    void say(String sentence) {
        lastSpeech.add(sentence);
        if (!ready) {
            pending.add(sentence);
            return;
        }
        enqueue(sentence);
    }

    boolean canReplay() {
        return !lastSpeech.isEmpty();
    }

    void replay() {
        stop();
        for (String s : lastSpeech) enqueue(s);
    }

    boolean isSpeaking() {
        return speaking;
    }

    void stop() {
        pending.clear();
        if (tts != null && ready) tts.stop();
        generation++;
        queued = finished = 0;
        setSpeaking(false);
    }

    /** The panel is closing: stop talking but keep the shared engine warm for the next one. */
    void release() {
        stop();
        waiting.remove(this);
        if (ready) tts.setOnUtteranceProgressListener(null);
        tts = null;
        ready = false;
    }

    private void enqueue(String sentence) {
        if (tts == null) return;
        if (!speaking) {
            audio.requestAudioFocus(focus);
            setSpeaking(true);
        }
        queued++;
        tts.speak(sentence, TextToSpeech.QUEUE_ADD, null, generation + ":" + queued);
    }

    private void utteranceEnded(String id) {
        if (id == null || !id.startsWith(generation + ":")) return;
        finished++;
        if (speaking && finished >= queued) {
            queued = finished = 0;
            setSpeaking(false);
        }
    }

    private void setSpeaking(boolean on) {
        if (speaking == on) return;
        speaking = on;
        if (!on) audio.abandonAudioFocusRequest(focus);
        listener.onSpeakingChanged(on);
    }
}
