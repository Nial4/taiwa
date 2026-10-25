package io.github.nial4.taiwa;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * Records one utterance from the remote's microphone and endpoints it with a simple energy VAD.
 *
 * The Android TV Remote Service only routes the remote mic into recordings that start right after
 * an Assistant-button press; any other recording on this TV yields pure digital silence. A run of
 * exact zeros therefore means "mic not open" rather than "user is quiet".
 */
final class Recorder implements Runnable {
    interface Listener {
        void onLevel(float level);
        void onSpeechEnd(byte[] pcm);
        void onRecorderError(int code);
    }

    static final int ERR_INIT = 1;
    static final int ERR_MIC_CLOSED = 2;
    static final int ERR_NO_SPEECH = 3;

    static final int RATE = 16000;
    private static final String TAG = "TaiwaRec";
    private static final int FRAME = RATE / 10;          // 100 ms
    private static final int START_FRAMES = 2;            // 200 ms above threshold starts speech
    private static final int END_SILENCE_FRAMES = 12;     // 1.2 s of quiet ends the utterance
    private static final int PREROLL_FRAMES = 4;
    private static final int ZERO_TIMEOUT_FRAMES = 30;    // 3 s of exact zeros: mic never opened
    private static final int NO_SPEECH_FRAMES = 80;       // 8 s without speech
    private static final int MAX_SPEECH_FRAMES = 250;     // 25 s per utterance

    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;
    private volatile boolean finishRequested;

    Recorder(Listener listener) {
        this.listener = listener;
    }

    void start() {
        new Thread(this, "recorder").start();
    }

    /** Stop listening and deliver what was said so far (OK button). */
    void finishNow() {
        finishRequested = true;
    }

    /** Stop without any callback. */
    void cancel() {
        cancelled = true;
    }

    @Override
    @SuppressWarnings("MissingPermission")
    public void run() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE * 2));
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            fail(ERR_INIT);
            return;
        }
        rec.startRecording();
        if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release();
            fail(ERR_INIT);
            return;
        }

        ByteArrayOutputStream pcm = new ByteArrayOutputStream(RATE * 2 * 10);
        short[] buf = new short[FRAME];
        byte[] bytes = new byte[FRAME * 2];
        float noise = 90f;
        int frames = 0, loud = 0, quiet = 0, speechStart = -1;
        boolean heard = false;
        int error = 0;

        while (!cancelled) {
            int n = rec.read(buf, 0, FRAME);
            if (n <= 0) {
                if (n < 0) { error = ERR_INIT; break; }
                continue;
            }
            double sum = 0;
            for (int k = 0; k < n; k++) {
                sum += (double) buf[k] * buf[k];
                bytes[2 * k] = (byte) buf[k];
                bytes[2 * k + 1] = (byte) (buf[k] >> 8);
                if (buf[k] != 0) heard = true;
            }
            pcm.write(bytes, 0, n * 2);
            frames++;
            float rms = (float) Math.sqrt(sum / n);
            float level = (float) Math.min(1.0, Math.max(0.0, Math.log10(Math.max(rms, 1) / noise) / 1.3));
            ui.post(() -> { if (!cancelled) listener.onLevel(level); });

            if (speechStart < 0) {
                if (rms > Math.max(350f, noise * 3.5f)) {
                    loud++;
                } else {
                    loud = 0;
                    if (heard) noise = noise * 0.9f + Math.max(40f, rms) * 0.1f;
                }
                if (loud >= START_FRAMES) speechStart = frames - START_FRAMES;
                else if (!heard && frames >= ZERO_TIMEOUT_FRAMES) { error = ERR_MIC_CLOSED; break; }
                else if (frames >= NO_SPEECH_FRAMES || finishRequested) { error = ERR_NO_SPEECH; break; }
            } else {
                quiet = rms < Math.max(180f, noise * 2.2f) ? quiet + 1 : 0;
                if (quiet >= END_SILENCE_FRAMES || finishRequested || frames - speechStart >= MAX_SPEECH_FRAMES) break;
            }
        }
        rec.stop();
        rec.release();
        if (cancelled) return;
        if (error != 0) {
            fail(error);
            return;
        }
        byte[] all = pcm.toByteArray();
        int frameBytes = FRAME * 2;
        int from = Math.max(0, speechStart - PREROLL_FRAMES) * frameBytes;
        int to = Math.min(all.length, (frames - Math.max(0, quiet - PREROLL_FRAMES)) * frameBytes);
        byte[] speech = new byte[Math.max(0, to - from)];
        System.arraycopy(all, from, speech, 0, speech.length);
        Log.i(TAG, "utterance " + speech.length / (RATE * 2f) + "s, noise=" + noise);
        ui.post(() -> { if (!cancelled) listener.onSpeechEnd(speech); });
    }

    private void fail(int code) {
        Log.w(TAG, "recorder error " + code);
        ui.post(() -> { if (!cancelled) listener.onRecorderError(code); });
    }
}
