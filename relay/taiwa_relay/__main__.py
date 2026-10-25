"""taiwa-relay: the LAN relay between the TV and ChatGPT.

  serve | whisper | login | logout | status | doctor | token
  models | set-model | set-speech-model | set-effort | set-language | set-location | benchmark | ask
  fetch-model | install-service | uninstall-service
"""

import argparse
import asyncio
import hashlib
import logging
import os
import plistlib
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

import httpx

from . import auth, chatgpt, config, discovery, languages

LABEL = "io.github.nial4.taiwa"
WHISPER_PORT = 8178
WHISPER_REPO = "ggerganov/whisper.cpp"
DEFAULT_WHISPER_MODEL = "large-v3-turbo-q5_0"


def _models() -> list[dict]:
    async def run():
        async with httpx.AsyncClient() as client:
            return await chatgpt.list_models(client, auth.access_token())
    return asyncio.run(run())


# ---------------------------------------------------------------- running

def cmd_serve(_args) -> None:
    import uvicorn
    from .server import app
    config.load_settings()  # creates the relay token on first start
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    uvicorn.run(app, host=config.LISTEN_HOST, port=config.LISTEN_PORT, log_level="info", access_log=False)


def cmd_whisper(args) -> None:
    """whisper-server in the foreground, with the same arguments install-service uses."""
    sys.exit(subprocess.run(_jobs(args.model)["whisper"]).returncode)


def cmd_login(args) -> None:
    try:
        creds = auth.login(open_browser=not args.no_browser)
    except auth.PlanUsageDisabled as e:
        sys.exit(f"Not enabled: {e}")
    print(f"Signed in as {creds.get('email')} (client {creds['client_id']}).")
    if not config.load_settings().get("model"):
        print("Next: `taiwa-relay models`, then `taiwa-relay set-model <slug>`.")


def cmd_logout(_args) -> None:
    print(auth.logout())


def cmd_token(_args) -> None:
    print(config.load_settings()["relay_token"])


def cmd_status(_args) -> None:
    creds = auth.load_credentials() or {}
    s = config.load_settings()
    rows = [
        ("config dir", config.CONFIG_DIR),
        ("host id", config.host_id()),
        ("account", creds.get("email") or "-"),
        ("client id", creds.get("client_id") or "-"),
        ("signed in", bool(creds.get("refresh_token"))),
        ("plan usage", auth.PLAN_SCOPE in creds.get("scopes", [])),
        ("model", s.get("model") or "-"),
        ("effort", s.get("reasoning_effort") or "(default)"),
        ("speech model", f"{s.get('speech_model') or '(same as model)'}, effort {s.get('speech_effort') or 'default'}"),
        ("language", s.get("language")),
        ("location", s.get("location") or "-"),
        ("web search", s.get("web_search")),
        ("whisper model", s.get("whisper_model") or DEFAULT_WHISPER_MODEL),
        ("listen", f"{config.LISTEN_HOST}:{config.LISTEN_PORT}"),
    ]
    for k, v in rows:
        print(f"{k:13}: {v}")


def cmd_doctor(_args) -> None:
    """One look at everything the TV needs; exits 1 when something essential is missing."""
    ok = True

    def check(name: str, good: bool, detail: str = "", essential: bool = True) -> None:
        nonlocal ok
        mark = "OK  " if good else ("FAIL" if essential else "WARN")
        ok &= good or not essential
        print(f"[{mark}] {name}" + (f": {detail}" if detail else ""))

    creds = auth.load_credentials() or {}
    s = config.load_settings()
    check("python", sys.version_info >= (3, 11), sys.version.split()[0])
    check("signed in to ChatGPT", bool(creds.get("refresh_token")), creds.get("email") or "run `taiwa-relay login`")
    check("ChatGPT plan usage granted", auth.PLAN_SCOPE in creds.get("scopes", []))
    check("model selected", bool(s.get("model")), s.get("model") or "run `taiwa-relay models` / `set-model`")
    check("language", s.get("language") in languages.BY_CODE, s.get("language"))
    server = _whisper_server()
    check("whisper-server installed", bool(server), server or "see README: install whisper.cpp")
    model = _whisper_model_path()
    check("whisper model downloaded", model.exists(), str(model) if model.exists() else "run `taiwa-relay fetch-model`")
    try:
        whisper_up = httpx.get(config.WHISPER_URL + "/", timeout=3).status_code < 500
    except httpx.HTTPError:
        whisper_up = False
    check("whisper-server running", whisper_up,
          config.WHISPER_URL if whisper_up else "`taiwa-relay install-service`, or `taiwa-relay whisper` in a terminal")
    try:
        socket.create_connection(("127.0.0.1", config.LISTEN_PORT), timeout=2).close()
        relay_up = True
    except OSError:
        relay_up = False
    check("relay running", relay_up,
          f"port {config.LISTEN_PORT}" if relay_up else "`taiwa-relay install-service`, or `taiwa-relay serve` in a terminal")
    ip = discovery.lan_ip()
    check("LAN address", bool(ip), f"http://{ip}:{config.LISTEN_PORT}" if ip else "no LAN interface found")
    check("mDNS advertising", discovery.method() != "none", discovery.method(), essential=False)
    check("auto-start installed", _service_installed(), _service_kind(), essential=False)
    sys.exit(0 if ok else 1)


# ---------------------------------------------------------------- ChatGPT settings

def cmd_models(_args) -> None:
    current = config.load_settings().get("model")
    for m in _models():
        print(f"{'*' if m['slug'] == current else ' '} {m['slug']:32} {m['display_name']}")


def _set(key: str, value) -> None:
    s = config.load_settings()
    s[key] = value
    config.save_settings(s)
    print(f"{key} = {value}")


def cmd_set_model(args) -> None:
    slugs = [m["slug"] for m in _models()]
    if args.slug not in slugs:
        sys.exit(f"unknown model {args.slug!r}; available: {', '.join(slugs)}")
    _set("model", args.slug)


def cmd_set_speech_model(args) -> None:
    if args.slug != "same":
        slugs = [m["slug"] for m in _models()]
        if args.slug not in slugs:
            sys.exit(f"unknown model {args.slug!r}; available: {', '.join(slugs)}")
    _set("speech_model", None if args.slug == "same" else args.slug)
    if args.effort:
        _set("speech_effort", None if args.effort == "default" else args.effort)


def cmd_set_effort(args) -> None:
    _set("reasoning_effort", None if args.effort == "default" else args.effort)


def cmd_set_language(args) -> None:
    if args.code not in languages.BY_CODE:
        sys.exit("unknown language; choose one of: " + ", ".join(languages.BY_CODE))
    _set("language", args.code)


def cmd_set_location(args) -> None:
    _set("location", args.location or None)


def cmd_benchmark(args) -> None:
    """First-token and total latency of each model, to pick a fast one for the TV and the spoken version."""
    from .server import _instructions
    settings = config.load_settings()
    question = args.question or "Suggest two places for a walk this weekend, briefly."

    async def run():
        token = auth.access_token()
        async with httpx.AsyncClient() as client:
            slugs = args.models or [m["slug"] for m in await chatgpt.list_models(client, token)]
            for slug in slugs:
                for effort in (None, "low"):
                    t0, first, n, err = time.monotonic(), None, 0, ""
                    async for ev in chatgpt.stream_response(client, token, slug, _instructions(settings),
                                                            [{"role": "user", "content": question}], effort):
                        if ev.kind == "delta":
                            first = first or time.monotonic() - t0
                            n += len(ev.text)
                        elif ev.kind == "error":
                            err = ev.code
                    print(f"{slug:24} effort={effort or 'default':8} first={first or 0:5.2f}s "
                          f"total={time.monotonic() - t0:5.2f}s chars={n:4d} {err}", flush=True)
    asyncio.run(run())


def cmd_ask(args) -> None:
    from .server import _instructions
    settings = config.load_settings()

    async def run():
        async with httpx.AsyncClient() as client:
            async for ev in chatgpt.stream_response(
                    client, auth.access_token(), settings["model"], _instructions(settings),
                    [{"role": "user", "content": args.text}], settings.get("reasoning_effort"),
                    None, settings.get("web_search", True)):
                if ev.kind == "delta":
                    print(ev.text, end="", flush=True)
                elif ev.kind == "error":
                    print(f"\n[error {ev.code}] {ev.text} (request {ev.request_id})")
        print()
    asyncio.run(run())


# ---------------------------------------------------------------- whisper model

def _whisper_server() -> str | None:
    found = shutil.which("whisper-server")
    for candidate in ("/opt/homebrew/bin/whisper-server", "/usr/local/bin/whisper-server"):
        if not found and Path(candidate).exists():
            found = candidate
    return found


def _whisper_model_path(name: str | None = None) -> Path:
    name = name or config.load_settings().get("whisper_model") or DEFAULT_WHISPER_MODEL
    return config.MODELS_DIR / f"ggml-{name}.bin"


def cmd_fetch_model(args) -> None:
    name = args.model
    filename = f"ggml-{name}.bin"
    dest = _whisper_model_path(name)
    tree = httpx.get(f"https://huggingface.co/api/models/{WHISPER_REPO}/tree/main", timeout=30).json()
    entry = next((f for f in tree if f.get("path") == filename), None)
    if entry is None:
        sys.exit(f"{filename} not found in {WHISPER_REPO}")
    sha = (entry.get("lfs") or {}).get("oid")
    size = (entry.get("lfs") or {}).get("size") or entry.get("size")
    if dest.exists() and _sha256(dest) == sha:
        print(f"already downloaded and verified: {dest}")
        _set("whisper_model", name)
        return
    config.ensure_dir(config.MODELS_DIR)
    part = dest.with_suffix(".part")
    url = f"https://huggingface.co/{WHISPER_REPO}/resolve/main/{filename}"
    print(f"downloading {filename} ({(size or 0) / 1e6:.0f} MB) to {dest}")
    with httpx.stream("GET", url, follow_redirects=True, timeout=httpx.Timeout(60, read=300)) as r:
        r.raise_for_status()
        done = 0
        with open(part, "wb") as f:
            for chunk in r.iter_bytes(1 << 20):
                f.write(chunk)
                done += len(chunk)
                if size:
                    print(f"\r  {done * 100 // size:3d}%", end="", flush=True)
    print()
    if sha and _sha256(part) != sha:
        part.unlink()
        sys.exit("checksum mismatch; download removed, try again")
    part.replace(dest)
    print(f"verified sha256 {sha}\n{dest}")
    _set("whisper_model", name)  # doctor and install-service use the model fetched last


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------- auto-start

def _service_kind() -> str:
    return {"darwin": "launchd user agents", "linux": "systemd user units", "win32": "scheduled tasks"}.get(
        sys.platform, "unsupported")


def _jobs(model: str | None) -> dict[str, list[str]]:
    server = _whisper_server()
    if not server:
        sys.exit("whisper-server not found on PATH; install whisper.cpp first (see README)")
    model_path = _whisper_model_path(model)
    if not model_path.exists():
        sys.exit(f"{model_path} missing; run `taiwa-relay fetch-model` first")
    threads = str(max(2, min(8, (os.cpu_count() or 4) // 2)))
    return {
        "whisper": [server, "--model", str(model_path), "--host", "127.0.0.1", "--port", str(WHISPER_PORT),
                    "--language", "auto", "--threads", threads],
        "relay": [sys.executable, "-m", "taiwa_relay", "serve"],
    }


def _launchd_path(name: str) -> Path:
    return Path.home() / "Library" / "LaunchAgents" / f"{LABEL}.{name}.plist"


def _systemd_path(name: str) -> Path:
    return Path.home() / ".config" / "systemd" / "user" / f"taiwa-{name}.service"


def _service_installed() -> bool:
    if sys.platform == "darwin":
        return _launchd_path("relay").exists()
    if sys.platform == "linux":
        return _systemd_path("relay").exists()
    if sys.platform == "win32":
        return subprocess.run(["schtasks", "/Query", "/TN", "Taiwa relay"], capture_output=True).returncode == 0
    return False


def cmd_install_service(args) -> None:
    jobs = _jobs(args.model)
    config.ensure_dir(config.LOG_DIR)
    if sys.platform == "darwin":
        domain = f"gui/{os.getuid()}"
        for name, argv in jobs.items():
            path = _launchd_path(name)
            path.parent.mkdir(parents=True, exist_ok=True)
            log_file = str(config.LOG_DIR / f"{name}.log")
            with open(path, "wb") as f:
                plistlib.dump({
                    "Label": f"{LABEL}.{name}", "ProgramArguments": argv,
                    "RunAtLoad": True, "KeepAlive": True, "ThrottleInterval": 10,
                    "StandardOutPath": log_file, "StandardErrorPath": log_file,
                    "EnvironmentVariables": {"PATH": "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"},
                }, f)
            subprocess.run(["launchctl", "bootout", f"{domain}/{LABEL}.{name}"], capture_output=True)
            for _ in range(50):  # bootout returns before the job is gone
                if subprocess.run(["launchctl", "print", f"{domain}/{LABEL}.{name}"],
                                  capture_output=True).returncode != 0:
                    break
                time.sleep(0.2)
            subprocess.run(["launchctl", "bootstrap", domain, str(path)], check=True)
            print(f"loaded {path}")
        print("They start whenever this user logs in. With FileVault on, someone has to unlock the Mac after "
              "a restart; the agents start right after that.")
    elif sys.platform == "linux":
        for name, argv in jobs.items():
            path = _systemd_path(name)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"[Unit]\nDescription=Taiwa {name}\nAfter=network-online.target\n\n"
                            f"[Service]\nExecStart={' '.join(_quote(a) for a in argv)}\n"
                            "Restart=always\nRestartSec=5\n\n[Install]\nWantedBy=default.target\n")
            print(f"wrote {path}")
        subprocess.run(["systemctl", "--user", "daemon-reload"], check=True)
        units = ["taiwa-whisper.service", "taiwa-relay.service"]
        subprocess.run(["systemctl", "--user", "enable", *units], check=True)
        subprocess.run(["systemctl", "--user", "restart", *units], check=True)  # picks up code and model changes
        print("To keep them running while nobody is logged in: loginctl enable-linger $USER")
    elif sys.platform == "win32":
        for name, argv in jobs.items():
            exe = argv[0]
            if name == "relay" and exe.lower().endswith("python.exe"):
                windowless = exe[:-len("python.exe")] + "pythonw.exe"
                exe = windowless if Path(windowless).exists() else exe
            command = " ".join(_quote(a) for a in [exe] + argv[1:])
            task = f"Taiwa {name}"
            subprocess.run(["schtasks", "/Create", "/F", "/SC", "ONLOGON", "/TN", task, "/TR", command], check=True)
            subprocess.run(["schtasks", "/End", "/TN", task], capture_output=True)  # restart a running copy
            subprocess.run(["schtasks", "/Run", "/TN", task], check=True)
            print(f"created and started scheduled task {task!r}")
        print(f"Allow inbound TCP {config.LISTEN_PORT} in Windows Defender Firewall (private networks) "
              "so the TV can connect.")
    else:
        sys.exit(f"auto-start is not implemented for {sys.platform}; run `taiwa-relay whisper` and `taiwa-relay serve` yourself")


def cmd_uninstall_service(_args) -> None:
    if sys.platform == "darwin":
        for name in ("relay", "whisper"):
            subprocess.run(["launchctl", "bootout", f"gui/{os.getuid()}/{LABEL}.{name}"], capture_output=True)
            _launchd_path(name).unlink(missing_ok=True)
    elif sys.platform == "linux":
        subprocess.run(["systemctl", "--user", "disable", "--now", "taiwa-relay.service", "taiwa-whisper.service"])
        for name in ("relay", "whisper"):
            _systemd_path(name).unlink(missing_ok=True)
    elif sys.platform == "win32":
        for name in ("relay", "whisper"):
            subprocess.run(["schtasks", "/End", "/TN", f"Taiwa {name}"], capture_output=True)
            subprocess.run(["schtasks", "/Delete", "/F", "/TN", f"Taiwa {name}"], capture_output=True)
    print("auto-start removed (settings, sign-in and models are kept)")


def _quote(arg: str) -> str:
    return f'"{arg}"' if " " in arg else arg


# ---------------------------------------------------------------- CLI

def main() -> None:
    p = argparse.ArgumentParser(prog="taiwa-relay", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("serve", help="run the relay (normally started by install-service)").set_defaults(fn=cmd_serve)
    wp = sub.add_parser("whisper", help="run whisper-server in the foreground (normally started by install-service)")
    wp.add_argument("--model", default=None, help="whisper model name (default: the one fetched last)")
    wp.set_defaults(fn=cmd_whisper)
    lp = sub.add_parser("login", help="Sign in with ChatGPT in this computer's browser")
    lp.add_argument("--no-browser", action="store_true")
    lp.set_defaults(fn=cmd_login)
    sub.add_parser("logout", help="revoke the sign-in and delete tokens").set_defaults(fn=cmd_logout)
    sub.add_parser("status").set_defaults(fn=cmd_status)
    sub.add_parser("doctor", help="check everything the TV needs").set_defaults(fn=cmd_doctor)
    sub.add_parser("token", help="print the relay token the TV must send").set_defaults(fn=cmd_token)
    sub.add_parser("models", help="models available to the signed-in account").set_defaults(fn=cmd_models)
    mp = sub.add_parser("set-model")
    mp.add_argument("slug")
    mp.set_defaults(fn=cmd_set_model)
    sp = sub.add_parser("set-speech-model", help="model for the short spoken version ('same' = main model)")
    sp.add_argument("slug")
    sp.add_argument("--effort", choices=["default", "none", "low", "medium", "high"])
    sp.set_defaults(fn=cmd_set_speech_model)
    ep = sub.add_parser("set-effort")
    ep.add_argument("effort", choices=["default", "low", "medium", "high"])
    ep.set_defaults(fn=cmd_set_effort)
    gp = sub.add_parser("set-language", help="conversation language, e.g. en-US, zh-CN, ja-JP")
    gp.add_argument("code")
    gp.set_defaults(fn=cmd_set_language)
    op = sub.add_parser("set-location", help='e.g. "Tokyo, Japan" (empty to clear)')
    op.add_argument("location", nargs="?", default="")
    op.set_defaults(fn=cmd_set_location)
    bp = sub.add_parser("benchmark", help="latency of each model, default and low reasoning effort")
    bp.add_argument("models", nargs="*")
    bp.add_argument("--question")
    bp.set_defaults(fn=cmd_benchmark)
    ap = sub.add_parser("ask", help="one question from the terminal (test)")
    ap.add_argument("text")
    ap.set_defaults(fn=cmd_ask)
    fp = sub.add_parser("fetch-model", help="download and verify a whisper.cpp model")
    fp.add_argument("--model", default=DEFAULT_WHISPER_MODEL,
                    help="e.g. large-v3-turbo-q5_0 (default), large-v3-turbo, small, base")
    fp.set_defaults(fn=cmd_fetch_model)
    ip = sub.add_parser("install-service", help="start whisper-server and the relay at login")
    ip.add_argument("--model", default=None, help="whisper model name (default: the one fetched last)")
    ip.set_defaults(fn=cmd_install_service)
    sub.add_parser("uninstall-service").set_defaults(fn=cmd_uninstall_service)
    args = p.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
