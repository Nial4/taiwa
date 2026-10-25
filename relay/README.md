# taiwa-relay

The LAN relay of [Taiwa](../README.md): the TV sends recorded speech here, this computer transcribes it
with whisper.cpp, asks ChatGPT through Sign in with ChatGPT, and streams the answer (and a short
spoken version) back to the TV.

```bash
uv tool install ./relay        # or: python -m venv .venv && .venv/bin/pip install ./relay
taiwa-relay doctor
```

Commands: `serve`, `whisper`, `login`, `logout`, `status`, `doctor`, `token`, `models`, `set-model`,
`set-speech-model`, `set-effort`, `set-language`, `set-location`, `benchmark`, `ask`, `fetch-model`,
`install-service`, `uninstall-service`. See the main [README](../README.md) and
[docs/sign-in-with-chatgpt.md](../docs/sign-in-with-chatgpt.md).

Environment overrides: `TAIWA_PORT` (8787), `TAIWA_HOST` (0.0.0.0), `TAIWA_CALLBACK_PORT` (1455),
`TAIWA_WHISPER_URL` (http://127.0.0.1:8178), `TAIWA_CONFIG_DIR`, `TAIWA_DATA_DIR`, `TAIWA_MDNS=0`
(don't advertise on the LAN).
