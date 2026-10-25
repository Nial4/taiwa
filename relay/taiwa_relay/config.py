"""Paths and persisted settings. Everything sensitive lives in the config dir (owner-only)."""

import json
import os
import secrets
import sys
import tempfile
import uuid
from pathlib import Path

APP_NAME = "Taiwa"  # agent_name_hint shown on the ChatGPT consent screen

if sys.platform == "win32":
    _config_home = Path(os.environ.get("APPDATA", Path.home() / "AppData" / "Roaming"))
    _data_home = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
else:
    _config_home = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    _data_home = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share"))

CONFIG_DIR = Path(os.environ.get("TAIWA_CONFIG_DIR", _config_home / "taiwa"))
DATA_DIR = Path(os.environ.get("TAIWA_DATA_DIR", _data_home / "taiwa"))
MODELS_DIR = DATA_DIR / "models"
LOG_DIR = DATA_DIR / "logs"
CREDENTIALS_FILE = CONFIG_DIR / "credentials.json"
HOST_ID_FILE = CONFIG_DIR / "host_id"
SETTINGS_FILE = CONFIG_DIR / "settings.json"
LOCK_FILE = CONFIG_DIR / "credentials.lock"

LISTEN_HOST = os.environ.get("TAIWA_HOST", "0.0.0.0")
LISTEN_PORT = int(os.environ.get("TAIWA_PORT", "8787"))
CALLBACK_PORT = int(os.environ.get("TAIWA_CALLBACK_PORT", "1455"))
WHISPER_URL = os.environ.get("TAIWA_WHISPER_URL", "http://127.0.0.1:8178")

DEFAULT_SETTINGS = {
    "model": None,
    "reasoning_effort": None,
    "reasoning_summary": "auto",
    "web_search": True,
    # Conversation language (see languages.py): speech recognition, answers and the spoken version.
    "language": "en-US",
    # Optional, e.g. "Tokyo, Japan": lets answers about weather, shops or schedules be local.
    "location": None,
    # Spoken version read aloud by the TV after each answer (a second, fast request).
    "speech": True,
    "speech_model": None,  # None: the main model is used
    "speech_effort": "low",
    # whisper.cpp model name, set by `fetch-model`; None means large-v3-turbo-q5_0.
    "whisper_model": None,
    "relay_token": None,
}


def ensure_dir(path: Path = CONFIG_DIR) -> None:
    path.mkdir(parents=True, exist_ok=True)
    if path == CONFIG_DIR and sys.platform != "win32":
        os.chmod(path, 0o700)


def write_private(path: Path, data: str) -> None:
    """Atomic write with owner-only permissions (on Windows the profile directory's ACL applies)."""
    ensure_dir(path.parent)
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=path.name + ".")
    try:
        if sys.platform != "win32":
            os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(data)
        os.replace(tmp, path)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise


def host_id() -> str:
    """Stable ext_agent_host_id for this computer, chosen once before the first sign-in."""
    if HOST_ID_FILE.exists():
        return HOST_ID_FILE.read_text().strip()
    value = f"urn:uuid:{uuid.uuid4()}"
    write_private(HOST_ID_FILE, value + "\n")
    return value


def load_settings() -> dict:
    settings = dict(DEFAULT_SETTINGS)
    if SETTINGS_FILE.exists():
        settings.update(json.loads(SETTINGS_FILE.read_text(encoding="utf-8")))
    if not settings.get("relay_token"):
        settings["relay_token"] = secrets.token_urlsafe(24)
        save_settings(settings)
    return settings


def save_settings(settings: dict) -> None:
    write_private(SETTINGS_FILE, json.dumps(settings, indent=2, ensure_ascii=False) + "\n")
