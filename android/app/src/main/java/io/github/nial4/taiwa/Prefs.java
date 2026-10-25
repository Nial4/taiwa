package io.github.nial4.taiwa;

import android.content.Context;
import android.content.SharedPreferences;

/** TV-side settings. Model, reasoning and web search live on the relay (see RelayClient settings). */
final class Prefs {
    static final String FILE = "relay";

    private final SharedPreferences p;

    Prefs(Context context) {
        p = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    String url() { return p.getString("url", null); }
    String token() { return p.getString("token", null); }
    void setUrl(String url) { p.edit().putString("url", url).apply(); }

    boolean autoSpeak() { return p.getBoolean("auto_speak", true); }
    void setAutoSpeak(boolean on) { p.edit().putBoolean("auto_speak", on).apply(); }

    String voice() { return p.getString("voice", null); }
    void setVoice(String name) { p.edit().putString("voice", name).apply(); }

    float speechRate() { return p.getFloat("speech_rate", 1f); }

    /** Conversation language (BCP-47), mirrored from the relay so TTS can start before it answers. */
    String language() { return p.getString("language", "en-US"); }
    void setLanguage(String code) { p.edit().putString("language", code).apply(); }
    void setSpeechRate(float rate) { p.edit().putFloat("speech_rate", rate).apply(); }
}
