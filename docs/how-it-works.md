# How Taiwa works

> 中文：[how-it-works.zh-CN.md](how-it-works.zh-CN.md)

## The pieces

```
┌─ TV (Android TV 11+) ─────────────────────────────────────────────┐
│ remote ─ATVV/BLE─► Android TV Remote Service (preinstalled)        │
│                     │ START_SEARCH → launch the default assistant  │
│                     │ remote audio → AudioPolicy into its recording│
│                     ▼                                              │
│               Taiwa panel (io.github.nial4.taiwa)                  │
│               records 16 kHz PCM, endpoints it, renders answers,   │
│               reads the spoken version with the TV's TTS           │
└──────────────────────────────┬─────────────────────────────────────┘
                     HTTP + relay token (LAN only), SSE back
┌──────────────────────────────▼──── your computer ─────────────────┐
│ taiwa-relay :8787 (FastAPI)                                        │
│   ├─ whisper-server :8178 (whisper.cpp, loopback only)             │
│   ├─ Sign in with ChatGPT tokens (~/.config/taiwa)                 │
│   └─ api.openai.com/v1/responses (store=false, stream=true)        │
│ advertises _taiwa._tcp on the LAN (dns-sd / Avahi / zeroconf)       │
└────────────────────────────────────────────────────────────────────┘
```

## The Assistant button and the microphone

This is the part that differs between TVs, and the reason Taiwa comes with a prompt instead of a
one-click installer.

**Google-certified voice remotes** (the TCL RC610 J4 tested here, made by iFlytek) speak *ATVV*,
Google's "Android TV Voice over BLE" GATT service (`AB5E0001-5A21-4F05-BC7D-AF01F617B664`):

- The Assistant button is **not an HID key**. The remote sends a `START_SEARCH` notification on the
  ATVV control characteristic. `getevent` on the remote's input device shows nothing at all, and
  accessibility services and key remappers never see it.
- The microphone audio also travels over ATVV (`MIC_OPEN` from the TV, ADPCM frames back).
- Android lets only apps holding `BLUETOOTH_PRIVILEGED` subscribe to that service. A normal app that
  tries is refused (`BtGatt.GattService: registerForNotification() - permission check failed!`).

The preinstalled **Android TV Remote Service** (`com.google.android.tv.remote.service`) is the
privileged client. When it receives `START_SEARCH` it:

1. launches the **default assistant** (`Settings.Secure.assistant`) with `ACTION_ASSIST`, through
   SystemUI's assist manager (which also shows the small assist orb at the bottom of the screen,
   with the icon from the assistant's `com.android.systemui.action_assist_icon` meta-data), and
2. registers an ad-hoc `AudioPolicy` that injects the remote's microphone into the assistant's
   `AudioRecord` as soon as it starts recording.

So Taiwa declares `ACTION_ASSIST`, becomes the default assistant, and records normally with
`MediaRecorder.AudioSource.VOICE_RECOGNITION`. A recording started at any other time gets **digital
silence** (exact zeros); about ten seconds after a turn the service unregisters its policy. That is
why each spoken turn starts with one press, and why Taiwa treats a run of exact zeros as "microphone
not routed" instead of "you were quiet".

Debloat lists (including the one this TV had) often disable the Remote Service and the Google app
(`katniss`). Taiwa needs the former and not the latter. With Google Assistant missing, the service
logs `Non-legacy assistant mode is suppressed…` and falls back to the plain assist launch Taiwa uses.

**Other remotes and TVs** may send the button as an HID key (`KEY_SEARCH`, `KEY_VOICECOMMAND`,
`KEY_ASSISTANT`), have a vendor voice HAL that exposes the remote as a normal input device (TCL's MVS
HAL on this TV reads iFlytek voice reports from `/dev/hidraw*`, but only for one remote vendor ID), or
a far-field microphone in the TV. Taiwa handles the key and intent variants; the prompt finds out
which case your TV is.

## A turn

1. **Listen.** `Recorder` reads 100 ms frames. Speech starts after 200 ms above
   max(350, 3.5 × noise floor); it ends after 1.2 s below max(180, 2.2 × floor), or on OK, or after 25 s.
   400 ms of pre- and post-roll are kept. The LED meter shows the level.
2. **Send.** The PCM goes to `POST /v1/sessions/{id}/turn` with the relay token and `X-Speech`
   (auto-read on or off). Requests use a fresh connection each time (`Connection: close`): a pooled
   keep-alive socket that uvicorn already closed can't be replayed for a streamed POST.
3. **Transcribe.** The relay wraps the PCM as WAV and calls whisper-server with the conversation
   language and a short initial prompt, converts Chinese script with OpenCC where needed, and drops
   whole-utterance hallucinations (subtitle credits that Whisper produces on silence).
4. **Answer.** The relay sends the session history to the Responses API with `web_search` and a
   reasoning summary, and streams server-sent events back: `status`, `transcript`, `reasoning`,
   `search`, `delta`, `citations`, `answer_done`.
5. **Speak.** If auto-read is on, a second request asks a fast model for a two-to-five-sentence spoken
   rewrite; the relay cuts it into sentences and sends each as `speech` as soon as it is complete.
   The TV queues them in its TTS engine and holds `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` while talking.
6. **Next turn.** The panel is a `singleTask` activity: another Assistant press arrives as
   `onNewIntent` in the same instance, so the same session ID is reused. Back, Home or anything covering
   the panel ends the session (`DELETE /v1/sessions/{id}`).

## The panel

- A translucent activity with a hand-drawn frame (`HudFrame`): black panel, gold hairline, amber
  corner brackets; KIDŌ's palette and its three OFL fonts (`Eva.java`). The app underneath keeps
  running; a playing video keeps playing.
- Markdown is split into blocks (text, table, code, quote, rule, image); while streaming only the
  changed block is rebuilt, at most every 80 ms. Tables are cards in the narrow panel and a grid in
  wide mode, where text is scaled to 85 %.
- Links render as source chips; the relay collects `url_citation` annotations into the sources list.
- The TTS engine is shared by all panels for the life of the process: a cold Google TTS start took
  about 14 s on the test TV, a warm one under a second.

## Finding the relay

The relay registers `_taiwa._tcp` through macOS's mDNSResponder (`dns-sd -R`). A Python process
sending multicast itself is blocked by macOS's Local Network privacy when it runs as a background
agent; on Linux it uses Avahi, otherwise python-zeroconf. When a request fails with a network error,
the TV runs an NSD lookup, checks the candidate with the relay token, saves the new address and
re-sends the same turn. The relay token is never discoverable.

## Relay API

All endpoints except `/health` need `Authorization: Bearer <relay token>`.

| Method | Path | |
|---|---|---|
| GET | `/health` | liveness |
| GET | `/v1/status` | signed in, plan usage, model, language, whisper reachable |
| POST | `/v1/sessions/{id}/turn` | body: 16 kHz mono s16le PCM; headers `X-Sample-Rate`, `X-Speech` → SSE |
| POST | `/v1/sessions/{id}/text` | `{"text": "…"}` → SSE (used by the debug build) |
| POST | `/v1/sessions/{id}/speech` | spoken version of the last answer → SSE |
| DELETE | `/v1/sessions/{id}` | forget the conversation |
| GET / PUT | `/v1/settings` | model, reasoning effort, web search, language, location; lists models and languages |

Errors arrive as `error {code, message}`; the TV shows its own translation for known codes.
