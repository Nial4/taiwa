# Notes for coding agents

If you were asked to set Taiwa up on someone's TV, follow [PROMPT.md](PROMPT.md) (or
[PROMPT.zh-CN.md](PROMPT.zh-CN.md)); it is the source of truth for the order of steps and the rules.

## Layout

- `relay/taiwa_relay/`: the LAN relay (Python 3.11+, FastAPI, httpx). `__main__.py` is the CLI,
  `server.py` the HTTP API and the instructions sent to ChatGPT, `auth.py` Sign in with ChatGPT,
  `chatgpt.py` the Responses API stream, `stt.py` whisper.cpp, `languages.py` per-language settings,
  `discovery.py` mDNS, `config.py` paths and defaults.
- `android/`: the TV app, package `io.github.nial4.taiwa`, plain Java on the platform SDK (no
  AndroidX, no Kotlin), minSdk 30. `ChatActivity` is the panel, `SettingsView` the settings pages,
  `AnswerView` + `Markdown` the answer rendering, `Recorder` the microphone and endpointing,
  `Speaker` the TTS, `RelayClient` the HTTP/SSE client, `Discovery` NSD, `ConfigReceiver` the adb
  configuration hook, `Eva` / `HudFrame` the KIDŌ look.
- `docs/`: how it works, Sign in with ChatGPT, troubleshooting. English and `*.zh-CN.md`.

## Conventions

- UI strings live in `res/values*/strings.xml` (English, `zh-rCN`, `ja`); add every new string to all three.
- Keep the app small: no new dependencies on the Android side without a good reason.
- Never log, print or commit the relay token or anything from `~/.config/taiwa/`.
- Build: `cd android && ./gradlew assembleDebug assembleRelease`. Check the relay with
  `taiwa-relay doctor` and `taiwa-relay ask "hello"`.
