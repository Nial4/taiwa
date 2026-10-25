package io.github.nial4.taiwa;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Talks to the relay on the user's computer. The TV never contacts OpenAI directly. */
final class RelayClient {
    static final String PREFS = Prefs.FILE;
    private static final String TAG = "Taiwa";

    interface Callback {
        void onStatus(String stage);
        void onTranscript(String text);
        void onDelta(String text);
        void onReasoning(String text);
        void onSearch(String state);
        void onCitations(JSONArray items);
        /** The written answer is complete; the spoken version may follow. */
        void onAnswerDone();
        void onSpeech(String sentence);
        void onDone();
        void onError(String code, String message);
    }

    interface JsonCallback {
        /** @param errorCode null on success, else "network" or "http_NNN" (see Messages) */
        void onResult(JSONObject json, String errorCode);
    }

    static final class Call {
        private volatile HttpURLConnection conn;
        private volatile boolean cancelled;

        void cancel() {
            cancelled = true;
            HttpURLConnection c = conn;
            if (c != null) new Thread(c::disconnect).start();
        }
    }

    private static final ExecutorService EXEC = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final String base;
    private final String token;

    RelayClient(String base, String token) {
        this.base = base.replaceAll("/+$", "");
        this.token = token;
    }

    static RelayClient fromPrefs(Context context) {
        Prefs p = new Prefs(context);
        return p.url() == null || p.token() == null ? null : new RelayClient(p.url(), p.token());
    }

    String base() {
        return base;
    }

    String host() {
        return base.replaceFirst("^https?://", "");
    }

    Call voiceTurn(String session, byte[] pcm, boolean speech, Callback cb) {
        return stream("/v1/sessions/" + session + "/turn", pcm, "application/octet-stream", speech, cb);
    }

    /** Spoken version of the session's last answer, on demand (auto-read off). */
    Call speech(String session, Callback cb) {
        return stream("/v1/sessions/" + session + "/speech", new byte[0], "application/octet-stream", true, cb);
    }

    Call textTurn(String session, String text, boolean speech, Callback cb) {
        byte[] body;
        try {
            body = new JSONObject().put("text", text).toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return stream("/v1/sessions/" + session + "/text", body, "application/json", speech, cb);
    }

    void endSession(String session) {
        EXEC.execute(() -> {
            try {
                HttpURLConnection c = open("/v1/sessions/" + session);
                c.setRequestMethod("DELETE");
                c.getResponseCode();
                c.disconnect();
            } catch (IOException ignored) {
                // The relay expires idle sessions on its own.
            }
        });
    }

    void status(JsonCallback cb) {
        json("GET", "/v1/status", null, cb);
    }

    void settings(JsonCallback cb) {
        json("GET", "/v1/settings", null, cb);
    }

    void updateSettings(JSONObject changes, JsonCallback cb) {
        json("PUT", "/v1/settings", changes, cb);
    }

    private void json(String method, String path, JSONObject body, JsonCallback cb) {
        EXEC.execute(() -> {
            try {
                HttpURLConnection c = open(path);
                c.setRequestMethod(method);
                if (body != null) {
                    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode(bytes.length);
                    c.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream out = c.getOutputStream()) {
                        out.write(bytes);
                    }
                }
                int code = c.getResponseCode();
                if (code != 200) {
                    ui.post(() -> cb.onResult(null, "http_" + code));
                    return;
                }
                JSONObject json = new JSONObject(readAll(c.getInputStream()));
                ui.post(() -> cb.onResult(json, null));
            } catch (IOException | JSONException e) {
                ui.post(() -> cb.onResult(null, "network"));
            }
        });
    }

    private HttpURLConnection open(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        // A pooled keep-alive socket may already be closed by the relay after a few idle seconds,
        // and a streamed POST cannot be replayed on it. Requests are rare; use a fresh socket each time.
        c.setRequestProperty("Connection", "close");
        return c;
    }

    private Call stream(String path, byte[] body, String contentType, boolean speech, Callback cb) {
        Call call = new Call();
        EXEC.execute(() -> {
            boolean finished = false;
            try {
                HttpURLConnection c = null;
                int code = -1;
                for (int attempt = 0; c == null; attempt++) {
                    try {
                        c = open(path);
                        call.conn = c;
                        c.setRequestMethod("POST");
                        c.setDoOutput(true);
                        c.setFixedLengthStreamingMode(body.length);
                        c.setRequestProperty("Content-Type", contentType);
                        c.setRequestProperty("Accept", "text/event-stream");
                        c.setRequestProperty("X-Sample-Rate", String.valueOf(Recorder.RATE));
                        c.setRequestProperty("X-Speech", speech ? "1" : "0");
                        try (OutputStream out = c.getOutputStream()) {
                            out.write(body);
                        }
                        code = c.getResponseCode();
                    } catch (IOException e) {
                        // Nothing was answered yet, so sending the turn again is safe.
                        Log.w(TAG, "POST " + path + " attempt " + attempt + " failed: " + e);
                        c.disconnect();
                        c = null;
                        if (attempt >= 1 || call.cancelled) throw e;
                    }
                }
                final int status = code;
                if (status != 200) {
                    post(call, () -> cb.onError("http_" + status, null));
                    return;
                }
                BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                String event = "message", line;
                StringBuilder data = new StringBuilder();
                while ((line = in.readLine()) != null && !call.cancelled) {
                    if (line.isEmpty()) {
                        if (data.length() > 0) finished |= dispatch(call, cb, event, data.toString());
                        event = "message";
                        data.setLength(0);
                    } else if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        data.append(line.substring(5).trim());
                    }
                }
                if (!finished) post(call, () -> cb.onError("stream_interrupted", null));
            } catch (IOException e) {
                Log.w(TAG, "stream " + path + " failed: " + e);
                if (!finished) post(call, () -> cb.onError("network", null));
            } finally {
                HttpURLConnection c = call.conn;
                if (c != null) c.disconnect();
            }
        });
        return call;
    }

    /** @return true when the event ends the turn */
    private boolean dispatch(Call call, Callback cb, String event, String data) {
        JSONObject json;
        try {
            json = new JSONObject(data);
        } catch (JSONException e) {
            return false;
        }
        switch (event) {
            case "status":
                post(call, () -> cb.onStatus(json.optString("stage")));
                return false;
            case "transcript":
                post(call, () -> cb.onTranscript(json.optString("text")));
                return false;
            case "delta":
                post(call, () -> cb.onDelta(json.optString("text")));
                return false;
            case "reasoning":
                post(call, () -> cb.onReasoning(json.optString("text")));
                return false;
            case "search":
                post(call, () -> cb.onSearch(json.optString("state")));
                return false;
            case "citations": {
                JSONArray items = json.optJSONArray("items");
                if (items != null) post(call, () -> cb.onCitations(items));
                return false;
            }
            case "answer_done":
                post(call, cb::onAnswerDone);
                return false;
            case "speech":
                post(call, () -> cb.onSpeech(json.optString("text")));
                return false;
            case "done":
                post(call, cb::onDone);
                return true;
            case "error":
                post(call, () -> cb.onError(json.optString("code"), json.optString("message")));
                return true;
            default:
                return false;
        }
    }

    private void post(Call call, Runnable r) {
        ui.post(() -> { if (!call.cancelled) r.run(); });
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
