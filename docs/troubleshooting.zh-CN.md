# 故障排查

> English: [troubleshooting.md](troubleshooting.md)

先在电脑上运行 `taiwa-relay doctor`，在电视上看 `adb logcat -s Taiwa TaiwaRec TaiwaTts TaiwaNsd`。
中继日志记录每一轮的耗时，但不记录内容：macOS 上在 `~/.local/share/taiwa/logs/relay.log`，Linux 上用
`journalctl --user -u taiwa-relay`，自己运行 `taiwa-relay serve` 时就在那个终端里（Windows 的计划任务不保留日志）。

## Assistant 键

| 现象 | 原因 | 解决 |
|---|---|---|
| 完全没反应，按键时 `getevent` 什么也没有 | ATVV 遥控器，而 Android TV Remote Service 被禁用了 | `pm enable com.google.android.tv.remote.service`（先征得同意），然后再按 |
| `pm enable` 说找不到 Remote Service | 它被针对当前用户卸载了（`pm list packages -u` 里能看到） | `cmd package install-existing com.google.android.tv.remote.service`（先征得同意） |
| 打开的是 Google 助理（或厂商助理） | 另一个助理仍占着这个键，或 Taiwa 不是默认助理 | `settings put secure assistant io.github.nial4.taiwa/.ChatActivity`；如果仍是 Google 应用响应，再决定是否禁用 `com.google.android.katniss` |
| 屏幕底部闪过一个白色圆球 | SystemUI 的助理光球，正常现象 | Taiwa 成为默认助理后会显示 Taiwa 的图标 |
| 每次按都打开一个新的空面板 | 上一个面板被关闭（返回键）或被盖住了 | 正常：新面板就是新对话 |

## 麦克风

| 现象 | 原因 | 解决 |
|---|---|---|
| “麦克风没有打开” | 录到的全是 0：录音不是在按下 Assistant 键之后立刻开始的（从启动器或 adb 打开） | 按 Assistant 键；每说一轮话都要按一次 |
| “没有听到声音” | 8 s 内音量一直没超过阈值 | 离遥控器近一点说；看 `TaiwaRec: utterance … noise=` 的数值 |
| 转写的文字或字形不对 | 对话语言和你说的语言不一致 | 设置 ▶ 语言，或 `taiwa-relay set-language` |
| 莫名出现“谢谢观看”、字幕署名之类 | Whisper 在噪音上产生幻觉 | 按语言在 `languages.py` 中过滤；把那句话加进去 |

## 连接中继

| 现象 | 原因 | 解决 |
|---|---|---|
| “无法连接 Taiwa 中继” | 中继没运行、IP 不对、防火墙 | `taiwa-relay doctor`；设置 ▶ 中继 ▶ 自动查找；允许 Python 接受传入连接（macOS）或放行 TCP 8787（Windows） |
| “中继令牌不匹配” | 令牌变了或 App 重装了 | `adb shell am broadcast -n io.github.nial4.taiwa/.ConfigReceiver --es token "$(taiwa-relay token)"` |
| 能用，但电脑重启后不行了 | 服务只在登录后启动；开启 FileVault 时要等解锁 | 登录 / 解锁；`taiwa-relay install-service`；Linux 上 `loginctl enable-linger` |
| “自动查找”找不到 | mDNS 被拦截，或中继以 `TAIWA_MDNS=0` 运行 | 手动输入地址；macOS 上中继用 `dns-sd`，Linux 上请安装 Avahi |

## ChatGPT

登录和套餐相关的错误见 [sign-in-with-chatgpt.zh-CN.md](sign-in-with-chatgpt.zh-CN.md#可能遇到的错误)。另外：

| 现象 | 原因 | 解决 |
|---|---|---|
| 回答要 10～20 s 才开始 | 模型先思考再回答；联网搜索也要时间 | `taiwa-relay benchmark`，然后换更快的模型或 `low` 推理强度 |
| `unsupported_value` | 该模型不支持这个推理强度（测试过的模型都拒绝 `minimal`，部分拒绝 `none`） | 设置 ▶ 推理强度 ▶ 默认 |
| `image_generation is not supported` | ChatGPT 套餐用量不支持 | — |

## 朗读

| 现象 | 原因 | 解决 |
|---|---|---|
| 第一个回答读得很晚，之后正常 | 电视 TTS 引擎冷启动（测试电视上约 14 s） | 正常；之后会保持热启动 |
| 声音不好听 | 每个引擎每种语言都有好几个声音 | 设置 ▶ 朗读声音：移动光标就会试听 |
| 声音列表是空的 | 电视没有这种语言的声音 | 在电视的“文字转语音”设置里安装；Google TTS 第一次使用时也会下载语音包 |
| 什么都不读 | 自动朗读关了 | 设置 ▶ 自动朗读，或回答后按 OK |
| 回答出来了但从不朗读，中继日志里有 `speech failed` | 朗读用的模型不接受该推理强度 | `taiwa-relay set-speech-model <slug> --effort default` |

## 编译与安装

| 现象 | 原因 | 解决 |
|---|---|---|
| `brew install whisper-cpp` 失败：没有同意 Xcode 许可 | 不同意之前 Homebrew 拒绝工作 | 自己运行 `sudo xcodebuild -license accept` |
| Gradle 报 `Unsupported class file major version` | JDK 对 Gradle 8.9 来说太新 | 使用 JDK 17 或 21（`JAVA_HOME=$(brew --prefix openjdk@17)`） |
| macOS 上 adb 报 “No route to host”，但端口是通的 | adb server 失去了“本地网络”访问权限 | `adb kill-server` 后重新连接 |
| `getevent` 什么都不输出 | 没有终端时输出被缓冲 | `adb shell -tt 'getevent -lt'` |
| `launchctl bootstrap … 5: Input/output error` | 上一个实例还没退出就 bootstrap 了 | `taiwa-relay install-service` 会等待它退出；再运行一次 |
| 视频画面截图是黑的或空的 | DRM / 硬件视频图层 | 正常；改用 `dumpsys media_session` 查看 |
