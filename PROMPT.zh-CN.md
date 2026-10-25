# Prompt

把分隔线以下的全部内容粘贴到 Claude Code（或其他能执行 shell 命令的编程 agent）里，在那台**同时要运行中继**的电脑上执行：
同一台电脑负责编译电视端 App、登录 ChatGPT，并常驻后台运行。已测试的是 Apple Silicon 上的 macOS；
Linux 和 Windows 代码层面支持但未实测。电脑和电视必须在同一个家庭网络里。

开始之前，在电视上打开开发者选项：Android TV：设置 → 设备偏好设置 → 关于 → 在 **版本号（Build）** 上按 7 次 OK；
Google TV：设置 → 系统 → 关于 → **Android TV OS 版本** 按 7 次。然后在开发者选项里打开 **USB 调试** 和
**网络调试**（ADB over network），较新的机型则是 **无线调试**。保持电视开机。
你需要一个其套餐可以通过 Sign in with ChatGPT 使用的 ChatGPT 账号，并且要在这台电脑前花几分钟完成登录。

> English: [PROMPT.md](PROMPT.md)

---

我想在我的 Android TV 上用上 **Taiwa**：按下遥控器上的 Assistant（麦克风）键说话，ChatGPT 的回答出现在一个
KIDŌ 风格的面板里，面板从右侧滑入，盖在当前画面上；面板打开期间每按一次都是在同一个对话里继续；返回键结束对话；
每个回答之后，用我的语言朗读一段简短的口语版本。代码在 https://github.com/Nial4/taiwa（如果这里还没有就 clone 下来，
先读 README.md、AGENTS.md 和 docs/）。直接使用其中的 `android/` 和 `relay/`，不要从零开始写。

我的电视型号：**<型号>**。我的语言（我说的和想听的语言）：**<语言，例如 简体中文、English、日本語>**。
用这个语言和我交流，除非我用别的语言跟你说话。如果上面哪个占位符我没填，先问我。

在真机上一步一步来，每一步都用从电视上拉下来的截图确认。凡是取决于我个人偏好的事，以及电视上难以撤销的操作，
都先问我。把你对电视和这台电脑做的每一处修改都记录下来，放在仓库外面的文件夹里（例如 `~/taiwa-setup/`），
确保它们永远不会被提交。

## 基本规则

- 永远不要索要、保存或代为输入我的 ChatGPT 密码或任何其他密码。我自己在这台电脑的浏览器里登录。
  永远不要使用 chatgpt.com 的私有 Web API：Taiwa 只使用官方的 Sign in with ChatGPT 流程
  （docs/sign-in-with-chatgpt.zh-CN.md）和 `api.openai.com/v1/responses`。
- 机密：中继令牌（`taiwa-relay token`）、`~/.config/taiwa/credentials.json` 和 `~/.config/taiwa/settings.json`
  （里面也有令牌；Windows 上目录是 `%APPDATA%\taiwa`）。按下文的方式把令牌直接传进 adb 命令；不要打印它，
  不要 `cat` 这些文件，不要用 `run-as` 读取 App 的 `shared_prefs`，不要提交，也不要贴到聊天里。
  `taiwa-relay status` 可以显示设置而不包含令牌。
- 需要 `sudo`、管理员确认、系统设置或在这台电脑上同意许可条款时（Xcode 许可、Android SDK 许可、安装 Homebrew、
  防火墙、FileVault、Windows 的 PATH 或防火墙规则），告诉我确切的命令，让我自己运行或同意。
- 不要扫描我的网络。问我电视的 IP 地址（电视上的 设置 → 网络）。
- 未经我同意，不要启用、禁用或卸载电视上的任何东西，也不要改电视的设置。改之前记下原来的值，以便恢复。
- 永远不要启动名字里带 factory、production 或 test menu 的包，哪怕只是看看。
- 我正在使用时不要重装 App：在 `adb install -r` 之前，确认前台没有我打开的 Taiwa 面板
  （`dumpsys activity activities | grep -E "Intent \{.*taiwa"` 中，我打开的面板会显示 `act=android.intent.action.ASSIST`）。
- 有些命令运行很久或者不会自己结束：`taiwa-relay login` 最多等我 10 分钟，`fetch-model` 要下载约 550 MB，
  第一次 Gradle 编译要好几分钟，`getevent` 和 `logcat` 会一直输出。给它们足够长的超时，或者放到后台写进文件，
  拿到需要的信息后就停掉持续输出的那些。
- 在 Windows 上，请在 PowerShell 或 Git Bash 里运行命令（`"$(taiwa-relay token)"` 在 cmd.exe 里不能用；
  在 Git Bash 里要设置 `MSYS_NO_PATHCONV=1`，这样 `/data/local/tmp/...` 路径才能原样传给 adb）。

## 连接电视

- adb：使用 Android SDK 的 `platform-tools`（随下文的 SDK 一起安装；也可以先装它）或
  `brew install --cask android-platform-tools`，并且只用一个 adb：两个不同版本会互相杀掉对方的 server。
- 网络调试：`adb connect <ip>:5555`。无线调试：电视会显示配对码和端口；由我自己运行 `adb pair <ip>:<配对端口>`
  并输入配对码，然后你再 `adb connect <ip>:<电视上显示的端口>`。告诉我电视上什么时候会弹出“允许调试吗？”，让我去确认。
- 在 macOS 上，如果 adb 报 “No route to host”，而 `nc -vz <ip> 5555` 能连上，说明 adb server 失去了“本地网络”权限：
  `adb kill-server` 后重新连接。如果一直失败，就在 venv 里用 Python 的 `adb-shell` 包作为客户端。
- 截图：`adb shell screencap -p /data/local/tmp/s.png` 然后 `adb pull`（有些电视通过 `exec-out` 得到的 PNG 是坏的）。
  视频区域（YouTube、Netflix、HDMI、电视直播）截出来是黑的或空的：这是 DRM / 硬件图层，不是 bug。
  用 `dumpsys media_session` 查看视频是否还在播放。
- 记录：型号、Android 版本（`getprop ro.build.version.sdk`，Taiwa 需要 30+）、屏幕尺寸和密度、当前的主屏应用、
  `settings get secure assistant`、`voice_interaction_service`、`voice_recognition_service`、
  `cmd role get-role-holders android.app.role.ASSISTANT`、`pm list packages -d` 和 `pm list packages -u`
  （针对当前用户卸载的包），以及电视的语言区域。
- 检查是否装了 KIDŌ 启动器（`dev.glidian.kido`）。Taiwa 的风格是为它设计的，但没有它也能用。
  如果没装，问我要不要装（https://github.com/glidiandev/kido，MIT），按它的 README 操作，其中任何会禁用原厂启动器的步骤都要先问我。
  后面的步骤都不依赖它。

## 先备份

保存到备份文件夹：上面的包列表、助理相关设置和角色持有者、
`dumpsys package com.google.android.tv.remote.service | grep enabled=`，以及 `com.google.android.katniss` 的同样信息。
写一个恢复脚本，能把你改过的每个值都改回去。注意细节：原来是 `null` 的设置要用 `settings delete secure <名称>` 恢复，
而不是 `put … null`；原来是 `enabled=3`（被用户禁用）的包用 `pm disable-user --user 0 <包名>` 恢复。

## 查明 Assistant 键和它的麦克风是怎么到达电视的

这一步决定后面的一切；在登录和编译 release 版之前先做。通过 ADB 注入的按键永远无法复现真实的按键，
所以请让我按真实的按钮，你在旁边观察。

1. `adb shell dumpsys input`：找到遥控器（通常是蓝牙，`bus=0x0005`），记下 `HasMic`、vendor/product ID 和按键布局文件。
2. 先清空日志（`adb logcat -c`），然后在后台运行 `adb shell -tt 'getevent -lt'`（`-tt`：没有 pty 时 getevent 会缓冲，
   你什么都看不到）和 `adb logcat -v time`，各自写入文件，然后让我先按一次**方向键右**（对照），再按三次 **Assistant 键**。
   - 方向键能看到，Assistant 键看不到：说明遥控器通过 **ATVV**（Google 的 BLE 语音 GATT 服务，`AB5E0001-…`）发送它。
     Android 只允许持有 `BLUETOOTH_PRIVILEGED` 的应用使用这个服务（尝试的应用会从 `BtGatt.GattService` 得到
     `registerForNotification() - permission check failed!`），所以只有预装的
     **`com.google.android.tv.remote.service`**（Android TV Remote Service）能接收这个按键和麦克风。
     精简列表经常会禁用或卸载它。向我解释这一点，并在 `pm enable com.google.android.tv.remote.service`（被禁用时）
     或 `cmd package install-existing com.google.android.tv.remote.service`（被针对用户卸载时）之前征得我同意。
     副作用：它同时提供手机遥控器配对功能（需要在电视上输入配对码，所以不会有东西自己连上来）。
   - Assistant 键以按键形式出现（`KEY_SEARCH`、`KEY_VOICECOMMAND`、`KEY_ASSISTANT` 或厂商自定义键）：
     系统会把它转成 assist 启动或搜索键；Taiwa 两种都能处理（`ACTION_ASSIST`、`VOICE_ASSIST`、`KEYCODE_SEARCH/ASSIST/VOICE_ASSIST`）。
     如果这个键只送到前台应用（在主屏上按什么也打不开），Taiwa 没有全局钩子能接住它：你可以加一个
     AccessibilityService 按键过滤器来启动 `ChatActivity`，但要先问我（这是敏感权限；把它追加到
     `enabled_accessibility_services`，绝不能覆盖那个列表），而且麦克风能不能用仍由第 3 步决定。
3. 用 debug 版测试麦克风。这只需要下文的一部分准备：安装中继（“搭建中继”的第 1 步，这样才有 `taiwa-relay token`；
   登录可以稍后），编译并安装 debug APK，并且在打开面板**之前**发送配置广播（没有地址和令牌时，面板只会显示
   “还没有配置中继地址”，不会录音）。把 Taiwa 设为默认助理（`settings put secure assistant io.github.nial4.taiwa/.ChatActivity`，
   旧值要保存），然后让我按 Assistant 键说一句话。观察（`adb logcat -v time | grep -E 'Taiwa|AtvRemote|ActivityTaskManager: START'`）：
   - `AtvRemote.BleRemoteDevice: Assistant has been requested`，接着是 `ActivityTaskManager: START u0
     {act=android.intent.action.ASSIST … cmp=io.github.nial4.taiwa/.ChatActivity}` 和
     `AtvRemote.AudioBridgeX: Ad hoc audio policy is being registered`（ATVV 路径），以及类似
     `TaiwaRec: utterance 2.3s, noise=87.5` 的一行。中继能否回答此时无关紧要。
   - 有底噪（大约 50～150）且录到了一段有长度的语音，说明麦克风正常。**全是 0** 时会以 `TaiwaRec: recorder error 2`
     和“麦克风没有打开”结束：说明麦克风没有接通。走 Remote Service 时，遥控器的音频只会注入到按下 Assistant 键后
     立即开始的录音里，所以每说一轮话都要按一次键。
   - 如果打开的仍然是 Google 助理而不是 Taiwa，说明 Google 应用（`com.google.android.katniss`）或厂商助理仍占着这个键：
     把你的发现告诉我，禁用之前先问我。
4. 只有上面都不行时才考虑其他路径：有些厂商的语音 HAL 会自己读取遥控器音频
   （`dumpsys media.audio_policy` 里有 “TV Remote Mic” / `AUDIO_DEVICE_IN_BLUETOOTH_BLE` 输入；某个 TCL 的 HAL 只接受一个遥控器厂商 ID），
   USB 麦克风则对任何录音都有效。如果没有任何麦克风通路可用，直接告诉我并停下，不要瞎猜。

## 在这台电脑上搭建中继

中继是电视唯一通信的对象。它运行 whisper.cpp 做语音识别，保管 ChatGPT 登录，并把回答流式送回电视。
先读 relay/README.md 和 docs/sign-in-with-chatgpt.zh-CN.md。

1. Python 3.11+ 和 `uv`：`uv tool install --editable ./relay` 会把 `taiwa-relay` 放进 PATH，
   并且之后对 `relay/` 的修改重启即可生效（或者用 venv 加 `pip install -e ./relay`）。
2. whisper.cpp 的 `whisper-server`，最终必须在 PATH 上：
   - macOS：`brew install whisper-cpp`。如果 Homebrew 因为从未同意 Xcode 许可而拒绝，请我自己运行 `sudo xcodebuild -license accept`。
   - Linux：用 cmake 从源码编译 whisper.cpp（可执行文件在 `build/bin/`），或使用带 `whisper-server` 的发行版软件包。
     Windows：使用 `whisper-bin-x64.zip` 发行包；永久修改 PATH 之前先问我。两者均未实测。
3. `taiwa-relay fetch-model` 下载 `ggml-large-v3-turbo-q5_0.bin`（约 550 MB）并校验 SHA-256。
   机器较弱时问我 `fetch-model --model small` 是否够用；中继会使用最后一次下载的模型。
4. Sign in with ChatGPT。先用一段话向我解释接下来会发生什么，然后运行 `taiwa-relay login`：浏览器会在**这台电脑上**打开，
   我自己登录，并为应用 “Taiwa” 允许 **“Use your ChatGPT plan”**。回调地址是 `http://127.0.0.1:1455`，
   所以浏览器必须和中继在同一台电脑上（无屏幕的中继请看 docs/sign-in-with-chatgpt.zh-CN.md 里的 SSH 隧道）。
   如果最后显示 “permission … was not granted”，说明我拒绝了套餐权限：重新运行，并告诉我该勾选哪一项。
5. `taiwa-relay models`，然后 `taiwa-relay benchmark`：把每个模型（默认和 `low` 推理强度）的首字时间和总时间做成表给我看，
   问我回答要用哪个模型（`set-model`、`set-effort`）。推荐最快的那个用于口语版本
   （`taiwa-relay set-speech-model <slug> --effort low`）。各模型支持的推理强度不同；不支持的值会返回 `unsupported_value`。
6. 问我的对话语言，然后 `taiwa-relay set-language <code>`（`en-US`、`zh-CN`、`zh-TW`、`ja-JP`、`ko-KR`……
   见 `relay/taiwa_relay/languages.py`）；问我是否要告诉 ChatGPT 我在哪里（`set-location "Tokyo, Japan"`；可选）。
7. `taiwa-relay install-service` 之前先问我：它会在登录时启动 whisper-server 和中继（macOS 上是 launchd 用户代理，
   Linux 上是 systemd 用户单元，Windows 上是计划任务），再次运行会重启它们。告诉我它的限制：只在我登录期间运行；
   开启 FileVault 时，Mac 重启后要有人解锁；Linux 上 `loginctl enable-linger` 可以让它不登录也运行；
   Windows 上创建计划任务可能需要管理员确认，且防火墙必须放行 TCP 8787；Linux 上防火墙必须放行 TCP 8787 和 UDP 5353（mDNS）。
   如果我不同意，就改为在后台运行 `taiwa-relay whisper` 和 `taiwa-relay serve`。
8. `taiwa-relay doctor` 的所有关键项都必须是 OK，`taiwa-relay ask "hello"` 必须能回答。
   在 macOS 上，如果防火墙询问是否允许 Python 接受传入连接，由我来允许。
   中继日志：macOS 上是 `~/.local/share/taiwa/logs/relay.log`，Linux 上用 `journalctl --user -u taiwa-relay`，
   `taiwa-relay serve` 则在它自己的终端里。

## 编译并安装电视端 App

- JDK 17 或 21（Gradle 8.9 无法在更新的 Java 上运行；macOS 上 `brew install openjdk@17` 并设置
  `JAVA_HOME=$(brew --prefix openjdk@17)`），以及 Android SDK 命令行工具，用
  `sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"` 安装组件。SDK 许可由我来同意。
  通过 `ANDROID_HOME` 或 `android/local.properties` 里的 `sdk.dir=`（已被 git 忽略）告诉 Gradle SDK 在哪里。安装前先问我（约 1 GB）。
- `cd android && ./gradlew assembleDebug assembleRelease`。APK 在 `android/app/build/outputs/apk/debug/app-debug.apk`
  和 `…/release/app-release.apk`，都用本地 debug 密钥签名，侧载没问题。测试时用 debug 版，最后安装 release 版。
  debug 版可以用文字提问，方便截图：
  `adb shell "am start -n io.github.nial4.taiwa/.ChatActivity --es debug_text 'What is the weather today'"`。
- `adb install -r -g <apk>`（`-g` 授予麦克风权限；App 自己不会申请）。如果在较新的 Android 上卡住，
  就 `adb push` 到 `/data/local/tmp/`，再 `adb shell pm install -r -g /data/local/tmp/<apk>`。
- 这台电脑的局域网地址就是 `taiwa-relay doctor` 里 “LAN address” 那一行。开着 VPN 或 Tailscale 时可能选错网卡：
  确认它和电视在同一网段。
- 配置时不要打印令牌：
  `adb shell am broadcast -n io.github.nial4.taiwa/.ConfigReceiver --es url http://<局域网IP>:8787 --es token "$(taiwa-relay token)"`。
  结果必须显示 `data="ok url token"`。中继还会以 `_taiwa._tcp` 广播自己，所以以后 IP 变了也会自动找到。
- 按上面第 3 步把 Taiwa 设为默认助理。

## 按我的需要调整

在我的电视或我的喜好需要的地方，你可以直接改代码；每次改完重新编译，并给我看截图。
改了 `relay/` 之后要重启它（再运行一次 `taiwa-relay install-service`，或重启 `taiwa-relay serve`）。

- **语言。** 如果我的语言不在 `relay/taiwa_relay/languages.py` 里，就加进去：whisper.cpp 语言代码、一句用该语言写的初始提示
  （用来引导字形和标点）、一句用于试听声音的简短问候语、口语版本的长度按字符而不是按单词计算时设 `cjk=True`
  （中文、日语、韩语），以及之后实际遇到的 Whisper 静音幻觉短语。面板本身的界面文字有 English、简体中文、日本語；
  如果是其他语言，主动提出把 `values/strings.xml` 翻译到 `android/app/src/main/res/values-<lang>/`
  （带地区时是 `values-<lang>-r<地区>`，例如 `values-pt-rBR`）。
- **声音。** 不要自己去逐个试听所有语言或所有声音。告诉我电视上我这种语言有哪些声音（离线的，以及会把朗读文字发给 Google 的“在线”声音），
  让我在“朗读声音”页面里试听并挑选；都不合适的话，告诉我怎么在电视的“文字转语音”设置里安装别的声音。
  语速、要不要自动朗读，也同样让我来决定。
- **我提出的其他任何要求**：换一个按键、面板的宽度或颜色、口语版本更长或更短（`relay/taiwa_relay/server.py` 里的
  `SPEECH_INSTRUCTIONS`）、告诉 ChatGPT 关于我的哪些信息（`INSTRUCTIONS`、`set-location`）。改动保持小而精，风格与周围代码一致。

## 在电视上验证

让我用真实的遥控器逐项操作，你同时看电视日志（`adb logcat -v time | grep -E 'Taiwa|AtvRemote|ActivityTaskManager: START'`）
和中继日志，每项完成后截图：

1. 按 Assistant 提一个问题：面板滑入，状态栏显示 `録音 REC` 和跳动的 LED 电平条，然后回答流式出现，接着朗读口语版本。
   如果回答一直不朗读、中继日志里有 `speech failed`，说明朗读用的模型不接受该推理强度：
   `taiwa-relay set-speech-model <slug> --effort default`。
2. 再按 Assistant 追问：必须在同一个会话里（`onNewIntent`，中继日志显示 `history 4`）。
3. 在播放 YouTube 视频时再来一次：视频继续播放，Taiwa 说话时视频声音被压低。
4. ◀ 展开面板，▶ 收窄；在窄面板里 ▶ 打开设置。确认“语言”、“朗读声音”、“模型”和“中继”页面显示的值对我来说是合理的
   （在“朗读声音”页面移动光标会试听；Google TTS 第一次启动可能要约 15 s，第一次使用时可能还会下载语音包）。
5. 一个需要联网的问题（“<我的城市>今天的天气”）会显示“已搜索网页”那一行（随面板语言变化）和来源。

如果哪里失败了，docs/troubleshooting.zh-CN.md 列出了我们遇到过的问题和解决办法。

## 交接

安装 release 版，然后用一段简短的总结告诉我：

- 你在电视和这台电脑上改了什么，以及每处修改怎么撤销：电视的恢复脚本、`adb uninstall io.github.nial4.taiwa`；
  电脑上 `taiwa-relay logout`、`taiwa-relay uninstall-service`、`uv tool uninstall taiwa-relay`，删除 `~/.config/taiwa`
  和 `~/.local/share/taiwa`（语音识别模型约 550 MB 在那里），以及 `brew uninstall whisper-cpp` 或对应的卸载方式；
- 我可能会用到的中继命令（`taiwa-relay doctor`、`status`、`models`、`set-model`、`set-language`、`login`、`logout`）；
- 我可以重新关闭网络调试，但以后重新配置 App 时需要再打开；
- 哪些数据会离开我的网络（问题和回答的文字会发给 OpenAI；音频在本地转写；“在线” TTS 声音会把朗读文字发给 Google），
  以及回答会占用我 ChatGPT 套餐的用量限额。
