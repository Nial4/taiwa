# Sign in with ChatGPT, step by step

> 中文：[sign-in-with-chatgpt.zh-CN.md](sign-in-with-chatgpt.zh-CN.md)

Taiwa never sees your ChatGPT password and never uses chatgpt.com's private API. It uses
[Sign in with ChatGPT](https://developers.openai.com/siwc/token-sharing-open-source) (SIWC), OpenAI's
official way for open-source and locally hosted apps to use **your own ChatGPT plan** for
`api.openai.com/v1/responses` requests. No API key and no separate billing: answers count against your
plan's usage, which you can see and limit in ChatGPT → Settings → Usage.

## The idea in one picture

```
 taiwa-relay login                         browser on the SAME computer
 ───────────────────                       ──────────────────────────────
 1. listens on 127.0.0.1:1455
 2. opens auth.openai.com/api/accounts/authorize?…  ──► you sign in to ChatGPT yourself
                                                        you approve the app "Taiwa" and
                                                        "Use your ChatGPT plan"
 3. 127.0.0.1:1455/auth/callback?code=…&client_id=…  ◄── the browser is redirected back
 4. exchanges the code (PKCE) at auth.openai.com/api/accounts/oauth/token
 5. checks the ID token, saves tokens to ~/.config/taiwa/credentials.json (owner-only)
```

That is OAuth 2.0 with PKCE and a **loopback callback**: the browser hands the one-time code to a
small web server that the relay runs on `127.0.0.1` for a minute. `127.0.0.1` always means "this
computer", which is why **the browser has to run on the computer that runs the relay**. The TV is
never involved: it only ever talks to your relay, with the relay's own token.

## Before you start

- The relay is installed (`taiwa-relay doctor` runs).
- Port 1455 on this computer is free (`lsof -i :1455` on macOS/Linux shows nothing).
- A desktop browser on this computer can be opened. On a headless machine, see
  [No screen on the relay computer](#no-screen-on-the-relay-computer).

## Sign in

```bash
taiwa-relay login
```

1. The terminal says *"Finish signing in to ChatGPT in the browser on this computer…"* and your
   default browser opens OpenAI's sign-in page.
2. Sign in with your ChatGPT account (email/password, Google, Apple, passkey: whatever you normally
   use). Two-factor prompts appear as usual. **Type everything yourself**; an agent helping you must
   never do it for you.
3. If you belong to several workspaces, pick the one whose plan you want Taiwa to use.
4. The consent page names the app (**Taiwa**, you may rename it) and lists what it asks for. Allow
   **"Use your ChatGPT plan"**; without it Taiwa can sign in but can't answer.
5. The browser lands on a black page: *"Done. You can close this page."* Back in the terminal:
   `Signed in as you@example.com (client oaiapp_…)`.

Then pick a model:

```bash
taiwa-relay models            # what your account offers
taiwa-relay benchmark         # first-token and total time per model, default and low effort
taiwa-relay set-model <slug>
taiwa-relay set-speech-model <fast-slug> --effort low   # the short spoken version
```

## What gets stored, and where

| File (macOS/Linux; Windows: `%APPDATA%\taiwa`) | Contents |
|---|---|
| `~/.config/taiwa/host_id` | a random `urn:uuid:…` that identifies this computer as an "agent host" |
| `~/.config/taiwa/credentials.json` (0600) | your email, the issued client ID, access, refresh and ID tokens, granted scopes |
| `~/.config/taiwa/settings.json` (0600) | model, language, the relay token the TV must send |

The directory is owner-only (0700). Nothing is written anywhere else, and nothing about your ChatGPT
account ever reaches the TV.

## How long it stays signed in

- The **access token** lasts an hour; the relay refreshes it automatically before each request
  that needs it.
- The **refresh token** lasts 30 days, and every refresh returns a new 30-day one. The relay also
  rotates it in the background every few hours, so as long as the relay runs at least once a month
  you never sign in again.
- Refreshes are serialized with a file lock, so the CLI and the background service never race
  (refresh tokens are single-use).
- If OpenAI rejects the refresh (you disconnected the app, changed your password, or a month passed
  with the relay off), the TV says "Not signed in" and you run `taiwa-relay login` again. The same
  client registration is reused, so it is a quick account pick without the consent page.

## Sign out or disconnect

```bash
taiwa-relay logout     # revokes the refresh token at OpenAI and deletes the tokens locally
```

You can also go to ChatGPT → Settings → (Connected) Apps / Usage, find **Taiwa** and disconnect it or
limit its share of your plan. After a disconnect, the next request fails and the relay asks you to sign in.

## No screen on the relay computer

The callback must reach `127.0.0.1:1455` on the relay computer, but the browser can live elsewhere if
you forward that port. From the computer with the browser:

```bash
ssh -L 1455:127.0.0.1:1455 you@relay-computer
# in that SSH session:
taiwa-relay login --no-browser      # prints the sign-in URL instead of opening a browser
```

Open the printed URL in your local browser. The redirect to `127.0.0.1:1455` goes through the tunnel
to the relay, which stores the tokens on the relay computer. Keep the SSH session open until the
terminal says `Signed in as …`; the relay waits up to ten minutes.

## What the plan covers (and what it doesn't)

Per OpenAI's preview limitations for this flow:

- `POST /v1/responses` only, with `store: false` and `stream: true`; the relay sends the whole
  conversation of the open panel each time.
- Tools: **web search** and developer-defined function tools. **Not** image generation, file search,
  code interpreter, computer use or hosted connectors (they return `400`).
- No audio input or transcription API, so speech-to-text runs locally with whisper.cpp.
- Model availability and reasoning-effort levels depend on your account; `taiwa-relay benchmark` shows
  which combinations work.
- On ChatGPT Plus, the five-hour usage limit is shared with every other app using your plan.

## Errors you might see

| Where | Message / code | What to do |
|---|---|---|
| terminal | `Not enabled: authorization was declined` | you clicked Cancel; run `login` again |
| terminal | `permission … chatgpt.tokens.use.direct was not granted` | allow "Use your ChatGPT plan" on the consent page |
| terminal | `Address already in use` (port 1455) | another login (or another app's sign-in) is still waiting; close it |
| terminal | `timed out waiting for the sign-in callback` | the browser never came back; check it opened on this computer |
| TV | Not signed in to ChatGPT | `taiwa-relay login` |
| TV | `subscription_sharing_usage_limit_exceeded` | plan limit reached; wait, or raise Taiwa's share in ChatGPT → Settings → Usage |
| TV | `subscription_sharing_user_not_eligible` | this account/workspace can't use its plan in third-party apps |
| TV | `unsupported_value` with a reasoning effort | that model doesn't support that effort: Settings → Reasoning effort → Default |
| TV / relay log | HTTP 403 before the answer starts | region or policy restriction on the account |

Official reference: <https://developers.openai.com/siwc/token-sharing-open-source> (sign-in, accounts
and sessions, models and inference, token reference, errors, preview limitations).
