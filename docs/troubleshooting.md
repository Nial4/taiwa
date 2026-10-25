# Troubleshooting

> 中文：[troubleshooting.zh-CN.md](troubleshooting.zh-CN.md)

Start with `taiwa-relay doctor` on the computer and `adb logcat -s Taiwa TaiwaRec TaiwaTts TaiwaNsd`
on the TV. The relay log lists every turn's timings without its text: `~/.local/share/taiwa/logs/relay.log`
on macOS, `journalctl --user -u taiwa-relay` on Linux, the terminal when you run `taiwa-relay serve`
yourself (the Windows scheduled task keeps no log).

## The Assistant button

| Symptom | Cause | Fix |
|---|---|---|
| Nothing happens at all, `getevent` shows nothing when the button is pressed | ATVV remote, and the Android TV Remote Service is disabled | `pm enable com.google.android.tv.remote.service` (ask first), then press again |
| `pm enable` says the Remote Service is unknown | it was uninstalled for the user (`pm list packages -u` lists it) | `cmd package install-existing com.google.android.tv.remote.service` (ask first) |
| Google Assistant (or a vendor assistant) opens instead | another assistant still owns the button, or Taiwa isn't the default | `settings put secure assistant io.github.nial4.taiwa/.ChatActivity`; if the Google app still answers, decide whether to disable `com.google.android.katniss` |
| A white circle flashes at the bottom of the screen | SystemUI's assist orb; normal | it shows Taiwa's icon once Taiwa is the default assistant |
| Each press opens a new, empty panel | the previous panel was closed (Back) or covered | normal: a new panel is a new conversation |

## The microphone

| Symptom | Cause | Fix |
|---|---|---|
| "The microphone didn't open" | the recording got exact zeros: it didn't start right after an Assistant press (opened from the launcher or adb) | press the Assistant button; each spoken turn needs one press |
| "Didn't hear anything" | speech never rose above the threshold within 8 s | speak closer to the remote; check `TaiwaRec: utterance … noise=` levels |
| Transcripts in the wrong script or language | conversation language doesn't match what you speak | Settings ▶ Language, or `taiwa-relay set-language` |
| Random "Thank you for watching" / subtitle credits | Whisper hallucinating on noise | filtered per language in `languages.py`; add the phrase there |

## Reaching the relay

| Symptom | Cause | Fix |
|---|---|---|
| "Can't reach the Taiwa relay" | relay not running, wrong IP, firewall | `taiwa-relay doctor`; Settings ▶ Relay ▶ Find on network; allow incoming connections for Python (macOS) or TCP 8787 (Windows) |
| "The relay token doesn't match" | token changed or app reinstalled | `adb shell am broadcast -n io.github.nial4.taiwa/.ConfigReceiver --es token "$(taiwa-relay token)"` |
| Works, then fails after the computer restarts | services start only after a login; FileVault waits for an unlock | log in / unlock; `taiwa-relay install-service`; on Linux `loginctl enable-linger` |
| "Find on network" finds nothing | mDNS blocked, or the relay runs with `TAIWA_MDNS=0` | enter the address by hand; on macOS the relay uses `dns-sd`, on Linux install Avahi |

## ChatGPT

See [sign-in-with-chatgpt.md](sign-in-with-chatgpt.md#errors-you-might-see) for sign-in and plan
errors. Also:

| Symptom | Cause | Fix |
|---|---|---|
| Answers take 10–20 s to start | the model thinks before answering; web search adds time | `taiwa-relay benchmark`, then a faster model or `low` reasoning effort |
| `unsupported_value` | reasoning effort not supported by that model (`minimal` was rejected by every model tested; `none` by some) | Settings ▶ Reasoning effort ▶ Default |
| `image_generation is not supported` | not available through ChatGPT plan usage | — |

## Reading aloud

| Symptom | Cause | Fix |
|---|---|---|
| The first answer is read late, later ones on time | cold start of the TV's TTS engine (~14 s on the test TV) | normal; it stays warm afterwards |
| A voice sounds bad | each engine has several voices per language | Settings ▶ Voice: moving the cursor previews each one |
| No voices listed | the TV has no voice for that language | install it in the TV's text-to-speech settings; Google TTS also downloads a voice pack the first time it is used |
| Nothing is read | auto-read is off | Settings ▶ Read answers aloud, or press OK after an answer |
| Answers appear but are never read, relay log says `speech failed` | the speech model rejects its reasoning effort | `taiwa-relay set-speech-model <slug> --effort default` |

## Building and installing

| Symptom | Cause | Fix |
|---|---|---|
| `brew install whisper-cpp` fails: Xcode licence not accepted | Homebrew refuses until it is | run `sudo xcodebuild -license accept` yourself |
| Gradle fails with `Unsupported class file major version` | JDK too new for Gradle 8.9 | use JDK 17 or 21 (`JAVA_HOME=$(brew --prefix openjdk@17)`) |
| adb: "No route to host" on macOS while the port is open | the adb server lost Local Network access | `adb kill-server` and connect again |
| `getevent` prints nothing | output is buffered without a terminal | `adb shell -tt 'getevent -lt'` |
| `launchctl bootstrap … 5: Input/output error` | bootstrapping before the previous instance is gone | `taiwa-relay install-service` waits for it; run it again |
| Screenshots of videos are black or empty | DRM / hardware video planes | normal; check `dumpsys media_session` instead |
