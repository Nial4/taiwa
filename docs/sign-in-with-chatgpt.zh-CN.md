# Sign in with ChatGPT 分步说明

> English: [sign-in-with-chatgpt.md](sign-in-with-chatgpt.md)

Taiwa 不会看到你的 ChatGPT 密码，也不使用 chatgpt.com 的私有 API。它使用的是
[Sign in with ChatGPT](https://developers.openai.com/siwc/token-sharing-open-source)（SIWC）：
OpenAI 官方为开源和本地托管应用提供的方式，让应用用**你自己的 ChatGPT 套餐**调用 `api.openai.com/v1/responses`。
不需要 API Key，也没有单独计费：回答计入你套餐的用量，你可以在 ChatGPT → 设置 → 用量（Usage）里查看和限制。

## 一张图看懂

```
 taiwa-relay login                         同一台电脑上的浏览器
 ───────────────────                       ──────────────────────────────
 1. 监听 127.0.0.1:1455
 2. 打开 auth.openai.com/api/accounts/authorize?…  ──► 你自己登录 ChatGPT
                                                        批准应用 “Taiwa” 和
                                                        “Use your ChatGPT plan”
 3. 127.0.0.1:1455/auth/callback?code=…&client_id=…  ◄── 浏览器被重定向回来
 4. 在 auth.openai.com/api/accounts/oauth/token 用 PKCE 换取令牌
 5. 校验 ID 令牌，把令牌保存到 ~/.config/taiwa/credentials.json（仅所有者可读）
```

这就是带 PKCE 和**回环回调（loopback callback）**的 OAuth 2.0：浏览器把一次性授权码交给中继在 `127.0.0.1`
上临时运行一分钟的小型 Web 服务器。`127.0.0.1` 永远指“这台电脑”，所以**浏览器必须运行在运行中继的那台电脑上**。
电视完全不参与：它只和你的中继通信，用的是中继自己的令牌。

## 开始之前

- 中继已安装（`taiwa-relay doctor` 能运行）。
- 这台电脑的 1455 端口空闲（macOS/Linux 上 `lsof -i :1455` 没有输出）。
- 这台电脑能打开桌面浏览器。没有屏幕的机器见[中继电脑没有屏幕](#中继电脑没有屏幕)。

## 登录

```bash
taiwa-relay login
```

1. 终端提示 *"Finish signing in to ChatGPT in the browser on this computer…"*，默认浏览器打开 OpenAI 的登录页。
2. 用你的 ChatGPT 账号登录（邮箱密码、Google、Apple、通行密钥：平时怎么登就怎么登）。两步验证照常出现。
   **所有内容都由你自己输入**；帮你操作的 agent 绝不能代劳。
3. 如果你属于多个工作区，选择想让 Taiwa 使用其套餐的那个。
4. 授权页会显示应用名（**Taiwa**，可以改名）和它请求的权限。请允许 **“Use your ChatGPT plan”**；
   不允许的话 Taiwa 能登录但无法回答。
5. 浏览器停在一个黑色页面：*"Done. You can close this page."* 回到终端会看到：
   `Signed in as you@example.com (client oaiapp_…)`。

然后选择模型：

```bash
taiwa-relay models            # 你的账号能用哪些模型
taiwa-relay benchmark         # 每个模型在默认和 low 推理强度下的首字时间和总时间
taiwa-relay set-model <slug>
taiwa-relay set-speech-model <快速模型> --effort low   # 用于简短的口语版本
```

## 保存了什么、保存在哪里

| 文件（macOS/Linux；Windows：`%APPDATA%\taiwa`） | 内容 |
|---|---|
| `~/.config/taiwa/host_id` | 随机的 `urn:uuid:…`，把这台电脑标识为一个 “agent host” |
| `~/.config/taiwa/credentials.json`（0600） | 你的邮箱、签发的 client ID、access / refresh / ID 令牌、已授权的 scope |
| `~/.config/taiwa/settings.json`（0600） | 模型、语言、电视必须携带的中继令牌 |

这个目录仅所有者可访问（0700）。不会写到其他任何地方，你 ChatGPT 账号的任何信息都不会到达电视。

## 登录状态能保持多久

- **access 令牌**有效期 1 小时；每次需要时中继会提前自动刷新。
- **refresh 令牌**有效期 30 天，每次刷新都会返回一个新的 30 天令牌。中继还会每隔几小时在后台轮换一次，
  所以只要中继每个月至少运行一次，你就不需要重新登录。
- 刷新操作用文件锁串行化，CLI 和后台服务不会互相抢（refresh 令牌只能用一次）。
- 如果 OpenAI 拒绝刷新（你断开了应用、改了密码，或中继关了一个多月），电视会显示“ChatGPT 未登录”，
  重新运行 `taiwa-relay login` 即可。会复用同一个客户端注册，所以只需选一下账号，不会再出现授权页。

## 退出登录或断开

```bash
taiwa-relay logout     # 在 OpenAI 撤销 refresh 令牌，并删除本地令牌
```

也可以在 ChatGPT → 设置 →（已连接的）应用 / 用量里找到 **Taiwa**，断开它或限制它能用的套餐份额。
断开后，下一次请求会失败，中继会提示你重新登录。

## 中继电脑没有屏幕

回调必须到达中继电脑上的 `127.0.0.1:1455`，但只要把这个端口转发过去，浏览器可以在别的电脑上。在有浏览器的电脑上：

```bash
ssh -L 1455:127.0.0.1:1455 you@relay-computer
# 在这个 SSH 会话里：
taiwa-relay login --no-browser      # 不打开浏览器，而是打印登录 URL
```

在本地浏览器里打开打印出来的 URL。重定向到 `127.0.0.1:1455` 的请求会通过隧道到达中继，令牌保存在中继电脑上。
在终端显示 `Signed in as …` 之前不要关闭 SSH 会话；中继最多等待十分钟。

## 套餐能用什么（不能用什么）

根据 OpenAI 对这个流程的预览版限制：

- 只能用 `POST /v1/responses`，且必须 `store: false`、`stream: true`；中继每次都会发送当前面板的完整对话。
- 工具：**联网搜索**和开发者自定义的 function 工具。**不能**用图片生成、文件搜索、代码解释器、computer use 或托管连接器（会返回 `400`）。
- 没有音频输入或转写 API，所以语音识别在本地用 whisper.cpp 完成。
- 能用哪些模型、哪些推理强度取决于你的账号；`taiwa-relay benchmark` 会显示哪些组合可用。
- ChatGPT Plus 的 5 小时用量限额与所有使用你套餐的其他应用共享。

## 可能遇到的错误

| 位置 | 消息 / 错误码 | 怎么办 |
|---|---|---|
| 终端 | `Not enabled: authorization was declined` | 你点了取消；重新运行 `login` |
| 终端 | `permission … chatgpt.tokens.use.direct was not granted` | 在授权页允许 “Use your ChatGPT plan” |
| 终端 | `Address already in use`（1455 端口） | 另一个登录（或其他应用的登录）还在等待；关掉它 |
| 终端 | `timed out waiting for the sign-in callback` | 浏览器一直没回来；确认它是在这台电脑上打开的 |
| 电视 | ChatGPT 未登录 | `taiwa-relay login` |
| 电视 | `subscription_sharing_usage_limit_exceeded` | 达到套餐上限；等一等，或在 ChatGPT → 设置 → 用量里提高 Taiwa 的份额 |
| 电视 | `subscription_sharing_user_not_eligible` | 这个账号 / 工作区不能在第三方应用里使用套餐 |
| 电视 | 与推理强度相关的 `unsupported_value` | 该模型不支持这个强度：设置 → 推理强度 → 默认 |
| 电视 / 中继日志 | 回答开始前出现 HTTP 403 | 账号受地区或政策限制 |

官方参考：<https://developers.openai.com/siwc/token-sharing-open-source>（登录、账号与会话、模型与推理、令牌参考、错误、预览版限制）。
