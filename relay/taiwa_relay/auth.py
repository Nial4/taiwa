"""Sign in with ChatGPT (ChatGPT plan usage for open-source / locally hosted apps).

Implements https://developers.openai.com/siwc/token-sharing-open-source :
dynamic client registration (client_id=dynamic_agent_client on first sign-in),
PKCE + loopback callback on 127.0.0.1, ID-token validation, 0600 credential
storage, serialized refresh-token rotation and revocation on sign-out.
"""

import base64
import hashlib
import http.server
import json
import secrets
import sys
import threading
import time
import urllib.parse
import webbrowser
from contextlib import contextmanager
from datetime import datetime, timezone

import httpx
import jwt

from . import config

ISSUER = "https://auth.openai.com"
AUTHORIZE_URL = ISSUER + "/api/accounts/authorize"
TOKEN_URL = ISSUER + "/api/accounts/oauth/token"
OIDC_CONFIG_URL = ISSUER + "/.well-known/openid-configuration"
RESOURCE = "https://api.openai.com/v1"
PLAN_SCOPE = "chatgpt.tokens.use.direct"
SCOPES = "openid profile email offline_access resource.invoke " + PLAN_SCOPE
NEW_CLIENT = "dynamic_agent_client"

# Refresh errors after which the token set is unusable and the user must sign in again.
UNUSABLE_REFRESH = {
    "invalid_grant", "invalid_refresh_token", "token_expired",
    "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused",
}
REFRESH_MARGIN = 300  # seconds before access-token expiry
KEEPALIVE_AGE = 20 * 86400  # refresh tokens live 30 days; rotate well before that


class ReauthRequired(Exception):
    """The saved token set cannot be used; run `taiwa-relay login` on this computer."""


class PlanUsageDisabled(Exception):
    """Signed in, but the ChatGPT plan-usage scope was not granted."""


def _b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def _now() -> float:
    return time.time()


@contextmanager
def _file_lock():
    """Serialize refreshes across processes (server + CLI) so rotating tokens never race."""
    config.ensure_dir()
    with open(config.LOCK_FILE, "a+") as fh:
        if sys.platform == "win32":
            import msvcrt
            fh.seek(0)
            while True:
                try:
                    msvcrt.locking(fh.fileno(), msvcrt.LK_LOCK, 1)
                    break
                except OSError:  # LK_LOCK gives up after ~10 s; keep waiting
                    pass
            try:
                yield
            finally:
                fh.seek(0)
                msvcrt.locking(fh.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            fcntl.flock(fh, fcntl.LOCK_EX)
            try:
                yield
            finally:
                fcntl.flock(fh, fcntl.LOCK_UN)


def load_credentials() -> dict | None:
    if not config.CREDENTIALS_FILE.exists():
        return None
    return json.loads(config.CREDENTIALS_FILE.read_text())


def _save_credentials(creds: dict) -> None:
    config.write_private(config.CREDENTIALS_FILE, json.dumps(creds, indent=2) + "\n")


def _oidc() -> dict:
    return httpx.get(OIDC_CONFIG_URL, timeout=15).raise_for_status().json()


def _validate_id_token(id_token: str, client_id: str, nonce: str | None) -> dict:
    jwks = jwt.PyJWKClient(_oidc()["jwks_uri"])
    key = jwks.get_signing_key_from_jwt(id_token)
    claims = jwt.decode(
        id_token,
        key.key,
        algorithms=["RS256", "RS384", "RS512", "ES256", "ES384", "PS256"],
        audience=client_id,
        issuer=ISSUER,
        options={"require": ["exp", "iss", "aud", "sub"]},
    )
    if nonce is not None and claims.get("nonce") != nonce:
        raise ValueError("ID token nonce mismatch")
    return claims


def _record(tokens: dict, client_id: str, claims: dict, previous: dict | None) -> dict:
    saved_at = _now()
    scopes = sorted(tokens.get("scope", "").split())
    return {
        "email": claims.get("email") or (previous or {}).get("email"),
        "issuer": ISSUER,
        "subject": claims["sub"],
        "client_id": client_id,
        "ext_agent_host_id": config.host_id(),
        "id_token": tokens.get("id_token") or (previous or {}).get("id_token"),
        "access_token": tokens["access_token"],
        "refresh_token": tokens.get("refresh_token") or (previous or {}).get("refresh_token"),
        "token_type": tokens.get("token_type", "Bearer"),
        "expires_in": tokens.get("expires_in", 3600),
        "expires_at": saved_at + int(tokens.get("expires_in", 3600)),
        "earliest_refresh_at": tokens.get("earliest_refresh_at"),
        "scopes": scopes,
        "saved_at": datetime.fromtimestamp(saved_at, timezone.utc).isoformat(),
        "saved_at_epoch": saved_at,
    }


# ---------------------------------------------------------------------------
# Interactive sign-in (the browser must run on the same computer: the callback is 127.0.0.1)
# ---------------------------------------------------------------------------

class _CallbackHandler(http.server.BaseHTTPRequestHandler):
    result: dict | None = None
    done = threading.Event()

    def do_GET(self):  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path != "/auth/callback":
            self.send_error(404)
            return
        type(self).result = dict(urllib.parse.parse_qsl(parsed.query))
        body = ("<!doctype html><meta charset=utf-8><title>Taiwa</title>"
                "<body style='font-family:system-ui;background:#000;color:#e9dec9;"
                "display:grid;place-items:center;height:100vh;margin:0'>"
                "<div><h2 style='color:#ffa62b'>Done. You can close this page.</h2>"
                "<p style='color:#b9ac95'>Go back to the terminal to see the result. "
                "完成，可以关闭此页面。完了しました。このページを閉じてください。</p></div>").encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        type(self).done.set()

    def log_message(self, *args):  # keep codes and tokens out of logs
        pass


def login(open_browser: bool = True, timeout: int = 600) -> dict:
    previous = load_credentials()
    host = config.host_id()
    state = secrets.token_urlsafe(32)
    nonce = secrets.token_urlsafe(32)
    verifier = secrets.token_urlsafe(64)
    challenge = _b64url(hashlib.sha256(verifier.encode()).digest())
    redirect_uri = f"http://127.0.0.1:{config.CALLBACK_PORT}/auth/callback"

    saved_client = (previous or {}).get("client_id")
    params = {
        "client_id": saved_client or NEW_CLIENT,
        "ext_agent_host_id": host,
        "response_type": "code",
        "redirect_uri": redirect_uri,
        "scope": SCOPES,
        "resource": RESOURCE,
        "state": state,
        "nonce": nonce,
        "code_challenge_method": "S256",
        "code_challenge": challenge,
    }
    if saved_client:
        if previous.get("id_token"):
            params["id_token_hint"] = previous["id_token"]
        if previous.get("email"):
            params["login_hint"] = previous["email"]
    else:
        params["agent_name_hint"] = config.APP_NAME

    _CallbackHandler.result = None
    _CallbackHandler.done.clear()
    server = http.server.HTTPServer(("127.0.0.1", config.CALLBACK_PORT), _CallbackHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        url = AUTHORIZE_URL + "?" + urllib.parse.urlencode(params, quote_via=urllib.parse.quote)
        print("Finish signing in to ChatGPT in the browser on this computer…")
        if not open_browser or not webbrowser.open(url):
            # id_token_hint makes the URL sensitive: print it without (you pick the account instead).
            shown = {k: v for k, v in params.items() if k != "id_token_hint"}
            print(AUTHORIZE_URL + "?" + urllib.parse.urlencode(shown, quote_via=urllib.parse.quote))
        if not _CallbackHandler.done.wait(timeout):
            raise TimeoutError("timed out waiting for the sign-in callback")
    finally:
        server.shutdown()
        server.server_close()

    cb = _CallbackHandler.result or {}
    if cb.get("state") != state:
        raise ValueError("state mismatch; ignoring callback")
    if cb.get("error"):
        if cb["error"] == "access_denied":
            raise PlanUsageDisabled("authorization was declined in the browser")
        raise ValueError(f"authorization failed: {cb.get('error')} {cb.get('error_description', '')}")

    if saved_client:
        if cb.get("client_id") and cb["client_id"] != saved_client:
            raise ValueError("callback returned a different client_id; rejecting")
        client_id = saved_client
    else:
        client_id = cb.get("client_id")
        if not client_id or client_id == NEW_CLIENT:
            raise ValueError("registration incomplete: no issued client_id in callback")

    resp = httpx.post(TOKEN_URL, data={
        "grant_type": "authorization_code",
        "client_id": client_id,
        "code": cb["code"],
        "code_verifier": verifier,
        "redirect_uri": redirect_uri,
        "resource": RESOURCE,
    }, timeout=30)
    if resp.status_code != 200:
        raise ValueError(f"code exchange failed: HTTP {resp.status_code} {resp.text[:300]}")
    tokens = resp.json()
    claims = _validate_id_token(tokens["id_token"], client_id, nonce)
    if previous and previous.get("subject") and previous.get("client_id") == client_id \
            and previous["subject"] != claims["sub"]:
        raise ValueError("signed in as a different account than the saved registration")

    creds = _record(tokens, client_id, claims, None)
    with _file_lock():
        _save_credentials(creds)
    if PLAN_SCOPE not in creds["scopes"]:
        raise PlanUsageDisabled("signed in, but permission to use the ChatGPT plan "
                                "(chatgpt.tokens.use.direct) was not granted")
    return creds


# ---------------------------------------------------------------------------
# Refresh / access
# ---------------------------------------------------------------------------

def _refresh_locked(creds: dict) -> dict:
    resp = httpx.post(TOKEN_URL, data={
        "grant_type": "refresh_token",
        "client_id": creds["client_id"],
        "refresh_token": creds["refresh_token"],
        "resource": RESOURCE,
    }, timeout=30)
    if resp.status_code != 200:
        try:
            err = resp.json()
        except ValueError:
            err = {}
        code = err.get("error") if isinstance(err.get("error"), str) else (err.get("error") or {}).get("code")
        if code in UNUSABLE_REFRESH:
            cleared = {k: v for k, v in creds.items()
                       if k not in ("access_token", "refresh_token", "expires_at")}
            _save_credentials(cleared)
            raise ReauthRequired(f"refresh rejected ({code}); sign in again")
        raise RuntimeError(f"refresh failed: HTTP {resp.status_code} {code or resp.text[:200]}")
    tokens = resp.json()
    claims = {"sub": creds["subject"], "email": creds.get("email")}
    if tokens.get("id_token"):
        claims = _validate_id_token(tokens["id_token"], creds["client_id"], None)
        if claims["sub"] != creds["subject"]:
            raise RuntimeError("refreshed ID token belongs to a different account")
    new = _record(tokens, creds["client_id"], claims, creds)
    _save_credentials(new)
    return new


def _needs_refresh(creds: dict, keepalive: bool) -> bool:
    now = _now()
    if now >= creds.get("expires_at", 0) - REFRESH_MARGIN:
        return True
    return keepalive and now - creds.get("saved_at_epoch", now) >= KEEPALIVE_AGE


def access_token(keepalive: bool = False) -> str:
    """Return a valid access token, refreshing (serialized via file lock) when needed."""
    with _file_lock():
        creds = load_credentials()
        if not creds or not creds.get("refresh_token"):
            raise ReauthRequired("not signed in")
        if PLAN_SCOPE not in creds.get("scopes", []):
            raise PlanUsageDisabled("ChatGPT plan usage not granted")
        if _needs_refresh(creds, keepalive):
            creds = _refresh_locked(creds)
        return creds["access_token"]


def force_refresh() -> dict:
    with _file_lock():
        creds = load_credentials()
        if not creds or not creds.get("refresh_token"):
            raise ReauthRequired("not signed in")
        return _refresh_locked(creds)


def logout() -> str:
    with _file_lock():
        creds = load_credentials()
        if not creds:
            return "not signed in"
        note = "remote session revoked"
        if creds.get("refresh_token"):
            try:
                endpoint = _oidc()["revocation_endpoint"]
                r = httpx.post(endpoint, data={
                    "token": creds["refresh_token"],
                    "token_type_hint": "refresh_token",
                    "client_id": creds["client_id"],
                }, timeout=30)
                if r.status_code != 200:
                    note = f"revocation not confirmed (HTTP {r.status_code}); disconnect the app in ChatGPT settings"
            except httpx.HTTPError as e:
                note = f"revocation not confirmed ({e.__class__.__name__}); disconnect the app in ChatGPT settings"
        # Keep the account/client mapping and host ID for a later sign-in.
        kept = {k: creds[k] for k in ("email", "issuer", "subject", "client_id", "ext_agent_host_id") if k in creds}
        _save_credentials(kept)
        return note
