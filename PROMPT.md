# The prompt

Paste everything below the line into Claude Code (or another coding agent that can run shell
commands) on the computer that will **also run the relay**: the same machine builds the TV app, signs
in to ChatGPT and keeps running in the background. macOS on Apple Silicon is the tested path; Linux
and Windows are supported by the code but untested. The computer and the TV must be on the same home
network.

Before you start, on the TV, turn on developer options: Android TV: Settings → Device Preferences →
About → press OK on **Build** 7 times; Google TV: Settings → System → About → **Android TV OS build**
7 times. Then in Developer options turn on **USB debugging** and **Network debugging** (ADB over
network), or on newer models **Wireless debugging**. Keep the TV on. You need a ChatGPT account whose
plan can be used through Sign in with ChatGPT, and a few minutes at this computer's screen to sign in.

> 中文版：[PROMPT.zh-CN.md](PROMPT.zh-CN.md)

---

I want **Taiwa** on my Android TV: press the Assistant (microphone) button on the remote, speak,
and ChatGPT answers in a KIDŌ-style panel that slides in over whatever is on screen; every press
while the panel is open continues the same conversation; Back ends it; after each answer a short
spoken version is read aloud in my language. The code is at https://github.com/Nial4/taiwa (clone it
if it is not here yet, and read README.md, AGENTS.md and docs/ first). Reuse its `android/` and
`relay/` instead of writing anything from scratch.

My TV model: **<MODEL>**. My language (what I speak and want to hear): **<LANGUAGE, e.g. English,
简体中文, 日本語>**. Talk to me in that language unless I write to you in another one. If I left
either placeholder unfilled, ask me first.

Work on the real device, step by step, and check each step with screenshots pulled from the TV.
Ask me before anything that depends on my preferences, and before anything on the TV that is hard
to undo. Keep a written log of every change you make to the TV and to this computer, in a folder
outside the repository (for example `~/taiwa-setup/`), so it never ends up in a commit.

## Ground rules

- Never ask for, store or type my ChatGPT password or any other password. I sign in myself, in the
  browser on this computer. Never use chatgpt.com's private web API: Taiwa only uses the official
  Sign in with ChatGPT flow (docs/sign-in-with-chatgpt.md) and `api.openai.com/v1/responses`.
- Secrets: the relay token (`taiwa-relay token`), `~/.config/taiwa/credentials.json` and
  `~/.config/taiwa/settings.json` (it holds the token too; on Windows the folder is
  `%APPDATA%\taiwa`). Pass the token straight into the adb command as shown below; never print it,
  `cat` those files, read the app's `shared_prefs` with `run-as`, commit them or paste them into chat.
  `taiwa-relay status` shows the settings without the token.
- If something needs `sudo`, an admin prompt, a system setting or accepting licence terms on this
  computer (Xcode licence, Android SDK licences, Homebrew installation, firewall, FileVault, Windows
  PATH or firewall rule), tell me the exact command and let me run it or agree to it.
- Don't scan my network. Ask me for the TV's IP address (Settings → Network on the TV).
- Don't enable, disable or uninstall anything on the TV, and don't change its settings, without
  asking. Note the previous value of everything you change so it can be restored.
- Never start a package whose name says factory, production or test menu, even to look at it.
- Don't reinstall the app while I am using it: check that no Taiwa panel I opened is in front
  (`dumpsys activity activities | grep -E "Intent \{.*taiwa"` shows `act=android.intent.action.ASSIST`
  for one I opened) before `adb install -r`.
- Some commands run long or never end by themselves: `taiwa-relay login` waits up to 10 minutes for
  me, `fetch-model` downloads ~550 MB, the first Gradle build takes minutes, `getevent` and
  `logcat` stream forever. Give them a long timeout or run them in the background writing to a file,
  and stop the streaming ones when you have what you need.
- On Windows, run the commands in PowerShell or Git Bash (`"$(taiwa-relay token)"` doesn't work in
  cmd.exe; in Git Bash set `MSYS_NO_PATHCONV=1` so `/data/local/tmp/...` paths reach adb unchanged).

## Connect to the TV

- adb: use the Android SDK's `platform-tools` (installed with the SDK below; you can install it
  first) or `brew install --cask android-platform-tools`, and stick to one adb binary: two different
  versions keep killing each other's server.
- Network debugging: `adb connect <ip>:5555`. Wireless debugging: the TV shows a pairing code and a
  port; I run `adb pair <ip>:<pairing port>` and type the code myself, then you `adb connect
  <ip>:<port shown on the TV>`. Tell me when the "Allow debugging?" prompt will appear on the TV so
  I can accept it.
- On macOS, if adb says "No route to host" while `nc -vz <ip> 5555` connects, the adb server lost
  Local Network access: `adb kill-server` and connect again. If that keeps failing, use the Python
  `adb-shell` package in a venv as the client.
- Screenshots: `adb shell screencap -p /data/local/tmp/s.png` then `adb pull` (some TVs return broken
  PNGs through `exec-out`). Video areas (YouTube, Netflix, HDMI, Live TV) capture as black or empty:
  that is DRM / hardware overlays, not a bug. Use `dumpsys media_session` to see whether a video is
  still playing.
- Record: model, Android version (`getprop ro.build.version.sdk`, Taiwa needs 30+), screen size and
  density, the current home app, `settings get secure assistant`, `voice_interaction_service`,
  `voice_recognition_service`, `cmd role get-role-holders android.app.role.ASSISTANT`, `pm list
  packages -d` and `pm list packages -u` (uninstalled for the user), and the TV's locale.
- Check whether the KIDŌ launcher (`dev.glidian.kido`) is installed. Taiwa is styled for it but works
  without it. If it is missing, ask whether I want it (https://github.com/glidiandev/kido, MIT) and
  follow its README, asking me before any step that disables the stock launcher. Nothing below
  depends on it.

## Back up first

Save to the backup folder: the package lists above, the assistant settings and role holder,
`dumpsys package com.google.android.tv.remote.service | grep enabled=`, and the same for
`com.google.android.katniss`. Write a restore script that puts back every value you change. Mind
the details: a setting that was `null` is restored with `settings delete secure <name>`, not `put …
null`; a package that was `enabled=3` (disabled by the user) with `pm disable-user --user 0 <pkg>`.

## Find out how the Assistant button and its microphone reach the TV

This decides everything else; do it before signing in or building the release. Keys injected over
ADB never reproduce a real press, so ask me to press the real button while you watch.

1. `adb shell dumpsys input`: find the remote (usually Bluetooth, `bus=0x0005`), note
   `HasMic`, vendor/product IDs and the key layout file.
2. Clear the log (`adb logcat -c`), then run `adb shell -tt 'getevent -lt'` (`-tt`: without a pty
   getevent buffers and you see nothing) and `adb logcat -v time` in the background, each into a
   file, and ask me to press **D-pad right once** (control) and then the **Assistant button** three
   times.
   - The D-pad shows up but the Assistant button does not: the remote sends it over **ATVV**,
     Google's voice-over-BLE GATT service (`AB5E0001-…`). Android only lets apps holding
     `BLUETOOTH_PRIVILEGED` use it (an app that tries gets `registerForNotification() - permission
     check failed!` from `BtGatt.GattService`), so only the preinstalled
     **`com.google.android.tv.remote.service`** (Android TV Remote Service) can receive the button and
     the microphone. Debloat lists often disable or uninstall it. Explain this and ask me before
     `pm enable com.google.android.tv.remote.service` (disabled) or `cmd package install-existing
     com.google.android.tv.remote.service` (uninstalled for the user). Side effect: it also serves
     phone-remote pairing (a code must be entered on the TV, so nothing connects by itself).
   - The Assistant button arrives as a key (`KEY_SEARCH`, `KEY_VOICECOMMAND`, `KEY_ASSISTANT`, or a
     vendor key): the system turns it into an assist launch or a search key; Taiwa handles both
     (`ACTION_ASSIST`, `VOICE_ASSIST`, `KEYCODE_SEARCH/ASSIST/VOICE_ASSIST`). If the key only reaches
     the app in front (nothing opens from the home screen), Taiwa has no global hook for it: you may
     add an AccessibilityService key filter that starts `ChatActivity`, but ask me first (it is a
     sensitive permission; append it to `enabled_accessibility_services`, never overwrite that
     list), and step 3 still decides whether the microphone works.
3. Test the microphone with the debug build. This needs only part of the setup below: install the
   relay (step 1 of "Set up the relay", so `taiwa-relay token` exists; signing in can wait), build
   and install the debug APK, and send the configuration broadcast, **before** the panel is opened
   (without an address and token the panel only says "No relay address yet" and doesn't record).
   Make Taiwa the default assistant (`settings put secure assistant
   io.github.nial4.taiwa/.ChatActivity`, old value saved) and ask me to press the Assistant button
   and say a sentence. Watch (`adb logcat -v time | grep -E 'Taiwa|AtvRemote|ActivityTaskManager: START'`):
   - `AtvRemote.BleRemoteDevice: Assistant has been requested`, then `ActivityTaskManager: START u0
     {act=android.intent.action.ASSIST … cmp=io.github.nial4.taiwa/.ChatActivity}` and
     `AtvRemote.AudioBridgeX: Ad hoc audio policy is being registered` (ATVV path), and
     `TaiwaRec: utterance 2.3s, noise=87.5` or similar. Whether the relay answers doesn't matter yet.
   - The microphone works when there is a noise floor (around 50–150) and the utterance has a
     length. **Exact zeros** end as `TaiwaRec: recorder error 2` and "The microphone didn't open":
     the microphone is not routed. With the Remote Service, the remote's audio is only injected into
     a recording that starts right after an Assistant press, which is why every spoken turn needs
     one press.
   - If Google Assistant still opens instead of Taiwa, the Google app (`com.google.android.katniss`)
     or a vendor assistant still owns the button: show me what you found and ask before disabling it.
4. Other routes, only if the above fails: some vendor voice HALs read the remote's audio themselves
   (`dumpsys media.audio_policy` lists a "TV Remote Mic" / `AUDIO_DEVICE_IN_BLUETOOTH_BLE` input; a
   TCL HAL only accepted one remote vendor ID), and a USB microphone works with any recording. If no
   microphone path works, tell me plainly and stop instead of guessing.

## Set up the relay on this computer

The relay is what the TV talks to. It runs whisper.cpp for speech-to-text, holds the ChatGPT sign-in
and streams answers back. Read relay/README.md and docs/sign-in-with-chatgpt.md.

1. Python 3.11+ and `uv`: `uv tool install --editable ./relay` puts `taiwa-relay` on the PATH and
   makes later edits to `relay/` take effect after a restart (or use a venv with `pip install -e
   ./relay`).
2. whisper.cpp's `whisper-server`, which must end up on the PATH:
   - macOS: `brew install whisper-cpp`. If Homebrew refuses because the Xcode licence was never
     accepted, ask me to run `sudo xcodebuild -license accept` myself.
   - Linux: build whisper.cpp from source with cmake (the binary is in `build/bin/`) or use a
     distribution package that ships `whisper-server`. Windows: the `whisper-bin-x64.zip` release;
     ask me before changing the PATH permanently. Both untested.
3. `taiwa-relay fetch-model` downloads `ggml-large-v3-turbo-q5_0.bin` (~550 MB) and verifies its
   SHA-256. On a small machine ask me whether `fetch-model --model small` is enough; the relay uses
   the model fetched last.
4. Sign in with ChatGPT. First explain to me in one paragraph what will happen, then run
   `taiwa-relay login`: the browser opens **on this computer**, I sign in and allow **"Use your ChatGPT
   plan"** for the app "Taiwa". The callback goes to `http://127.0.0.1:1455`, so the browser must be
   on the same computer as the relay (docs/sign-in-with-chatgpt.md has the SSH tunnel for a headless
   relay). If it ends with "permission … was not granted", I declined the plan permission: run it
   again and tell me which box to allow.
5. `taiwa-relay models`, then `taiwa-relay benchmark`: show me a table of first-token and total
   times for each model (default and `low` reasoning effort) and ask which model I want for answers
   (`set-model`, `set-effort`). Suggest the fastest one for the spoken version
   (`taiwa-relay set-speech-model <slug> --effort low`). Reasoning-effort support differs between
   models; an unsupported value comes back as `unsupported_value`.
6. Ask my conversation language and `taiwa-relay set-language <code>` (`en-US`, `zh-CN`, `zh-TW`,
   `ja-JP`, `ko-KR`, …, see `relay/taiwa_relay/languages.py`); ask whether to tell ChatGPT where I am
   (`set-location "Tokyo, Japan"`; optional).
7. Ask before `taiwa-relay install-service`: it starts whisper-server and the relay at login
   (launchd user agents on macOS, systemd user units on Linux, scheduled tasks on Windows) and
   restarts them when run again. Tell me the limits: they run only while I am logged in; with
   FileVault on, someone has to unlock the Mac after a restart; on Linux `loginctl enable-linger`
   keeps them running without a login; on Windows creating the tasks may need an admin prompt and the
   firewall must allow TCP 8787; on Linux a firewall must allow TCP 8787 and UDP 5353 (mDNS). If I
   say no, run `taiwa-relay whisper` and `taiwa-relay serve` in the background instead.
8. `taiwa-relay doctor` must show OK for everything essential, and `taiwa-relay ask "hello"` must
   answer. On macOS, if the firewall asks whether Python may accept incoming connections, I allow it.
   The relay log: `~/.local/share/taiwa/logs/relay.log` on macOS, `journalctl --user -u taiwa-relay`
   on Linux, the terminal for `taiwa-relay serve`.

## Build and install the TV app

- JDK 17 or 21 (Gradle 8.9 doesn't run on newer Java; on macOS `brew install openjdk@17` and
  `JAVA_HOME=$(brew --prefix openjdk@17)`), and the Android SDK command-line tools with
  `sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"`. Accepting the SDK
  licences is mine to do. Point Gradle at the SDK with `ANDROID_HOME` or `sdk.dir=` in
  `android/local.properties` (ignored by git). Ask before installing (around 1 GB).
- `cd android && ./gradlew assembleDebug assembleRelease`. The APKs are
  `android/app/build/outputs/apk/debug/app-debug.apk` and `…/release/app-release.apk`, both signed
  with the local debug key, which is fine for sideloading. Use the debug build while testing and
  install the release build at the end. The debug build can ask a typed question, handy for
  screenshots:
  `adb shell "am start -n io.github.nial4.taiwa/.ChatActivity --es debug_text 'What is the weather today'"`.
- `adb install -r -g <apk>` (`-g` grants the microphone permission; the app never asks for it). If
  that hangs on newer Android, `adb push` the APK to `/data/local/tmp/` and `adb shell pm install -r
  -g /data/local/tmp/<apk>`.
- This computer's LAN address is the "LAN address" line of `taiwa-relay doctor`. With a VPN or
  Tailscale running it can pick the wrong interface: check it is on the TV's subnet.
- Configure without printing the token:
  `adb shell am broadcast -n io.github.nial4.taiwa/.ConfigReceiver --es url http://<LAN IP>:8787 --es token "$(taiwa-relay token)"`.
  The result must say `data="ok url token"`. The relay also advertises itself as `_taiwa._tcp`, so
  later IP changes are found automatically.
- Make Taiwa the default assistant as in step 3 above.

## Adapt it to me

You are expected to change the code where my TV or my taste needs it; rebuild and show me a
screenshot after each change. After changing `relay/`, restart it (`taiwa-relay install-service`
again, or restart `taiwa-relay serve`).

- **Language.** If my language is not in `relay/taiwa_relay/languages.py`, add it: whisper.cpp code, a
  one-sentence initial prompt written in that language (it nudges script and punctuation), a short
  greeting for the voice preview, `cjk=True` when the spoken version's length should be counted in
  characters rather than words (Chinese, Japanese, Korean), and any phrases Whisper invents on
  silence once we see them. The panel's own text exists in English, 简体中文 and 日本語; for another
  language offer to translate `values/strings.xml` into `android/app/src/main/res/values-<lang>/` (with
  a region it is `values-<lang>-r<REGION>`, e.g. `values-pt-rBR`).
- **Voice.** Don't audition every language or every voice yourself. Tell me which voices the TV has
  for my language (offline vs. "online", which sends the spoken text to Google), let me listen on
  the Voice page and pick; if none fits, tell me how to install another in the TV's text-to-speech
  settings. Same for the speaking rate and whether answers are read aloud at all.
- **Anything else I ask for**: another button, the panel's width or colours, a longer or shorter
  spoken version (`SPEECH_INSTRUCTIONS` in `relay/taiwa_relay/server.py`), what ChatGPT is told about
  me (`INSTRUCTIONS`, `set-location`). Keep changes small and in the style of the surrounding code.

## Verify on the TV

Ask me to do each of these with the real remote while you watch the TV log
(`adb logcat -v time | grep -E 'Taiwa|AtvRemote|ActivityTaskManager: START'`) and the relay log, and
take a screenshot after each:

1. Press Assistant, ask a question: the panel slides in, the status strip shows `録音 REC` with a
   moving LED meter, then the answer streams, then the spoken version is read aloud. If the answer
   is never read and the relay log says `speech failed`, the speech model rejects its effort:
   `taiwa-relay set-speech-model <slug> --effort default`.
2. Press Assistant again for a follow-up: it must stay in the same session (`onNewIntent`, and the
   relay log shows `history 4`).
3. Do it again over a playing YouTube video: the video keeps playing and is ducked while Taiwa speaks.
4. ◀ widens the panel, ▶ narrows it; in the narrow panel ▶ opens settings. Check that the Language,
   Voice, Model and Relay pages show sensible values for me (on the Voice page moving the cursor
   previews a voice; the first Google TTS start can take ~15 s and a voice pack may download on first
   use).
5. A question that needs the web ("today's weather in <my city>") shows the "searched the web" line
   (in the panel's language) and sources.

If something fails, docs/troubleshooting.md lists what we have seen and what fixed it.

## Hand over

Install the release build, then tell me in a short summary:

- what you changed on the TV and on this computer, and how to undo each change: the TV restore
  script, `adb uninstall io.github.nial4.taiwa`; on this computer `taiwa-relay logout`,
  `taiwa-relay uninstall-service`, `uv tool uninstall taiwa-relay`, deleting `~/.config/taiwa` and
  `~/.local/share/taiwa` (the speech model is ~550 MB there), and `brew uninstall whisper-cpp` or
  its equivalent;
- the relay commands I may need (`taiwa-relay doctor`, `status`, `models`, `set-model`,
  `set-language`, `login`, `logout`);
- that I may turn network debugging off again, but need it back to reconfigure the app;
- what leaves my network (the text of my questions and answers goes to OpenAI; audio is transcribed
  locally; "online" TTS voices send the spoken text to Google), and that answers use my ChatGPT
  plan's limits.
