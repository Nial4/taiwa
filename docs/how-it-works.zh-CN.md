# Taiwa 的工作原理

> English: [how-it-works.md](how-it-works.md)

## 组成部分

```
┌─ 电视（Android TV 11+）────────────────────────────────────────────┐
│ 遥控器 ─ATVV/BLE─► Android TV Remote Service（预装）                │
│                     │ START_SEARCH → 启动默认助理                   │
│                     │ 遥控器音频 → AudioPolicy 注入到它的录音        │
│                     ▼                                              │
│               Taiwa 面板（io.github.nial4.taiwa）                   │
│               录制 16 kHz PCM、判断说话结束、渲染回答、             │
│               用电视的 TTS 朗读口语版本                              │
└──────────────────────────────┬─────────────────────────────────────┘
                     HTTP + 中继令牌（仅局域网），SSE 返回
┌──────────────────────────────▼──── 你的电脑 ───────────────────────┐
│ taiwa-relay :8787（FastAPI）                                        │
│   ├─ whisper-server :8178（whisper.cpp，只监听本机）                 │
│   ├─ Sign in with ChatGPT 令牌（~/.config/taiwa）                    │
│   └─ api.openai.com/v1/responses（store=false, stream=true）         │
│ 在局域网广播 _taiwa._tcp（dns-sd / Avahi / zeroconf）                 │
└────────────────────────────────────────────────────────────────────┘
```

## Assistant 键与麦克风

这是每台电视差别最大的部分，也是 Taiwa 提供 prompt 而不是一键安装包的原因。

**Google 认证的语音遥控器**（这里测试的 TCL RC610 J4，由科大讯飞制造）使用 *ATVV*，
即 Google 的 “Android TV Voice over BLE” GATT 服务（`AB5E0001-5A21-4F05-BC7D-AF01F617B664`）：

- Assistant 键**不是 HID 按键**。遥控器在 ATVV 的控制特征值上发送一条 `START_SEARCH` 通知。
  在遥控器的输入设备上用 `getevent` 什么也看不到，无障碍服务和按键重映射工具也永远收不到它。
- 麦克风音频同样走 ATVV（电视发 `MIC_OPEN`，遥控器回传 ADPCM 帧）。
- Android 只允许持有 `BLUETOOTH_PRIVILEGED` 的应用订阅这个服务。普通应用尝试订阅会被拒绝
  （`BtGatt.GattService: registerForNotification() - permission check failed!`）。

预装的 **Android TV Remote Service**（`com.google.android.tv.remote.service`）就是那个有特权的客户端。
它收到 `START_SEARCH` 时会：

1. 通过 SystemUI 的 assist manager，用 `ACTION_ASSIST` 启动**默认助理**（`Settings.Secure.assistant`）
   （同时会在屏幕底部显示一个小小的助理光球，图标来自助理应用的 `com.android.systemui.action_assist_icon` meta-data）；
2. 注册一个临时的 `AudioPolicy`，在助理的 `AudioRecord` 开始录音时把遥控器的麦克风注入进去。

所以 Taiwa 声明了 `ACTION_ASSIST`，成为默认助理，然后用 `MediaRecorder.AudioSource.VOICE_RECOGNITION`
正常录音即可。在其他任何时候开始的录音只会得到**数字静音**（全是 0）；一轮对话后约十秒，服务会注销它的 policy。
这就是为什么每说一轮话都要按一次键，也是为什么 Taiwa 把一串完全为 0 的采样视为“麦克风没有接通”，而不是“你没说话”。

精简列表（包括这台电视原来用的那份）经常会禁用 Remote Service 和 Google 应用（`katniss`）。
Taiwa 需要前者，不需要后者。没有 Google Assistant 时，服务会记录 `Non-legacy assistant mode is suppressed…`，
然后退回到 Taiwa 所用的普通 assist 启动方式。

**其他遥控器和电视**可能把这个键作为 HID 按键发送（`KEY_SEARCH`、`KEY_VOICECOMMAND`、`KEY_ASSISTANT`），
可能有厂商的语音 HAL 把遥控器当作普通输入设备暴露出来（这台电视上 TCL 的 MVS HAL 会从 `/dev/hidraw*` 读取讯飞的语音报告，
但只认一个遥控器厂商 ID），也可能电视本身带远场麦克风。Taiwa 支持按键和 intent 这几种情况；你的电视属于哪一种，由 prompt 去查明。

## 一轮对话

1. **听。** `Recorder` 每次读取 100 ms 的帧。音量连续 200 ms 高于 max(350, 3.5 × 底噪) 时认为开始说话；
   连续 1.2 s 低于 max(180, 2.2 × 底噪)、或按下 OK、或满 25 s 时结束。前后各保留 400 ms。LED 电平条显示音量。
2. **发送。** PCM 连同中继令牌和 `X-Speech`（是否自动朗读）发到 `POST /v1/sessions/{id}/turn`。
   每次请求都使用新连接（`Connection: close`）：连接池里已被 uvicorn 关闭的 keep-alive 连接，无法为流式 POST 重放。
3. **转写。** 中继把 PCM 包成 WAV，带上对话语言和一句简短的初始提示调用 whisper-server，必要时用 OpenCC 转换中文字形，
   并丢弃整句的幻觉文本（Whisper 在静音时编出来的字幕署名之类）。
4. **回答。** 中继把会话历史连同 `web_search` 和推理摘要一起发给 Responses API，再以 server-sent events 流式返回：
   `status`、`transcript`、`reasoning`、`search`、`delta`、`citations`、`answer_done`。
5. **朗读。** 如果开启了自动朗读，第二个请求让一个快速模型把回答改写成 2～5 句口语；中继按句切分，每句一完整就作为 `speech` 发出。
   电视把它们排进 TTS 引擎，朗读期间持有 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`。
6. **下一轮。** 面板是一个 `singleTask` Activity：再按 Assistant 会以 `onNewIntent` 进入同一个实例，因此沿用同一个会话 ID。
   返回键、Home 键或任何盖住面板的东西都会结束会话（`DELETE /v1/sessions/{id}`）。

## 面板

- 一个半透明 Activity，边框是手绘的（`HudFrame`）：黑色面板、金色细线、琥珀色角括号；
  配色和三种 OFL 字体沿用 KIDŌ（`Eva.java`）。下面的应用继续运行，正在播放的视频继续播放。
- Markdown 被拆成块（文字、表格、代码、引用、分隔线、图片）；流式输出时只重建变化的那一块，最快每 80 ms 一次。
  表格在窄面板里显示为卡片，在宽屏模式里显示为网格，宽屏模式的文字缩放到 85 %。
- 链接显示为来源标签；中继会把 `url_citation` 注释收集成来源列表。
- TTS 引擎在整个进程生命周期里由所有面板共用：在测试电视上，Google TTS 冷启动约 14 s，热启动不到 1 s。

## 找到中继

中继通过 macOS 的 mDNSResponder（`dns-sd -R`）注册 `_taiwa._tcp`。Python 进程自己发组播，在作为后台 agent 运行时
会被 macOS 的“本地网络”隐私权限拦住；Linux 上用 Avahi，其他情况用 python-zeroconf。
请求因网络错误失败时，电视会做一次 NSD 查找，用中继令牌验证候选地址，保存新地址并重新发送同一轮对话。中继令牌本身永远不会被广播。

## 中继 API

除 `/health` 外，所有接口都需要 `Authorization: Bearer <中继令牌>`。

| 方法 | 路径 | |
|---|---|---|
| GET | `/health` | 存活检查 |
| GET | `/v1/status` | 是否登录、套餐用量授权、模型、语言、whisper 是否可达 |
| POST | `/v1/sessions/{id}/turn` | 请求体：16 kHz 单声道 s16le PCM；请求头 `X-Sample-Rate`、`X-Speech` → SSE |
| POST | `/v1/sessions/{id}/text` | `{"text": "…"}` → SSE（debug 版使用） |
| POST | `/v1/sessions/{id}/speech` | 上一个回答的口语版本 → SSE |
| DELETE | `/v1/sessions/{id}` | 丢弃这段对话 |
| GET / PUT | `/v1/settings` | 模型、推理强度、联网搜索、语言、所在地；列出可用模型和语言 |

错误以 `error {code, message}` 返回；对已知的错误码，电视会显示自己的本地化文字。
