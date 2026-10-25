"""HTTP API for the TV. Each panel opening on the TV is one session; history lives here in memory.

All endpoints except /health need `Authorization: Bearer <relay_token>` (see `taiwa-relay token`).
Answers stream back as server-sent events:
  status {stage} -> transcript {text} -> reasoning/search/delta* -> citations -> answer_done
  -> speech {text}* -> done {}      or, at any point, error {code, message}
"""

import asyncio
import hmac
import re
import json
import logging
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from datetime import datetime

import httpx
from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import StreamingResponse

from . import auth, chatgpt, config, discovery, languages, stt

log = logging.getLogger("taiwa")

SESSION_TTL = 30 * 60
MAX_HISTORY_ITEMS = 40  # 20 exchanges; older turns are dropped
MAX_AUDIO_BYTES = 16000 * 2 * 30  # 30 s of 16 kHz mono PCM
KEEPALIVE_INTERVAL = 6 * 3600
MODELS_TTL = 600
EFFORTS = (None, "low", "medium", "high")

INSTRUCTIONS = """You are ChatGPT. The user is in their living room and talks to you through the TV remote's \
microphone; your answer appears in a side panel on the TV screen (Markdown, including tables, renders fine).
- The user's words come from speech recognition and may contain homophones or transcription errors; infer \
what they meant from context.
- Reply in {language} unless the user asks for another language.
- Use web search for anything recent or time-sensitive: news, weather, prices, schedules, release dates.
{location}- Current local time: {now}."""

SPEECH_INSTRUCTIONS = """Rewrite the written answer below as a short spoken version; the TV reads it aloud with \
text-to-speech.
- Talk naturally, the way a friend would explain it, in {language} (if the written answer is in another \
language, use that one).
- Only the essentials: usually 2 to 5 sentences, at most {limit}. If the written answer is already short, \
you may follow it closely.
- No Markdown symbols, list numbers, tables, URLs, source names or parenthetical notes.
- Write numbers, dates and units the way they are said out loud.
- No preamble such as "in summary"; start right away."""

# Sentence ends at which the spoken version is cut and sent to the TV's TTS queue ("3.5" stays whole).
SENTENCE_END = re.compile(r"(?<=[。！？!?；;…])|(?<=\.)\s+|\n+")


def _now_text() -> str:
    return datetime.now().astimezone().strftime("%Y-%m-%d (%A) %H:%M %Z")


def _instructions(settings: dict) -> str:
    lang = languages.get(settings.get("language"))
    location = f"- The user is in {settings['location']}.\n" if settings.get("location") else ""
    return INSTRUCTIONS.format(language=lang.name, location=location, now=_now_text())


@dataclass
class Session:
    history: list[dict] = field(default_factory=list)
    last_used: float = field(default_factory=time.time)
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)


class State:
    client: httpx.AsyncClient
    sessions: dict[str, Session]
    token_lock: asyncio.Lock
    models: tuple[float, list[dict]] = (0.0, [])


state = State()


async def _keepalive() -> None:
    """Rotate the 30-day refresh token periodically so the sign-in survives idle weeks."""
    while True:
        await asyncio.sleep(KEEPALIVE_INTERVAL)
        try:
            async with state.token_lock:
                await asyncio.to_thread(auth.access_token, True)
        except Exception as e:  # noqa: BLE001 - keep the loop alive
            log.warning("token keepalive failed: %s", e)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    state.client = httpx.AsyncClient()
    state.sessions = {}
    state.token_lock = asyncio.Lock()
    tasks = [asyncio.create_task(_keepalive()), asyncio.create_task(discovery.advertise())]
    yield
    for t in tasks:
        t.cancel()
    await asyncio.gather(*tasks, return_exceptions=True)
    await state.client.aclose()


app = FastAPI(title="Taiwa relay", lifespan=lifespan, docs_url=None, redoc_url=None)


def require_token(request: Request) -> None:
    expected = config.load_settings()["relay_token"]
    got = request.headers.get("authorization", "")
    if not hmac.compare_digest(got.encode(), f"Bearer {expected}".encode()):
        raise HTTPException(status_code=401, detail="bad relay token")


def _sse(event: str, data: dict) -> bytes:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n".encode()


def _session(sid: str) -> Session:
    now = time.time()
    for key in [k for k, s in state.sessions.items() if now - s.last_used > SESSION_TTL]:
        del state.sessions[key]
    if len(sid) > 64:
        raise HTTPException(status_code=400, detail="bad session id")
    s = state.sessions.setdefault(sid, Session())
    s.last_used = now
    return s


async def _access_token() -> str:
    async with state.token_lock:
        return await asyncio.to_thread(auth.access_token)


async def _answer(session: Session, text: str, speak: bool):
    settings = config.load_settings()
    try:
        token = await _access_token()
    except auth.ReauthRequired:
        yield _sse("error", {"code": "not_signed_in",
                             "message": "Not signed in to ChatGPT. Run `taiwa-relay login` on the relay computer."})
        return
    except auth.PlanUsageDisabled:
        yield _sse("error", {"code": "plan_usage_disabled",
                             "message": "Using the ChatGPT plan was not allowed. Run `taiwa-relay login` again and allow it."})
        return
    except Exception as e:  # noqa: BLE001
        log.warning("token error: %s", e)
        yield _sse("error", {"code": "token_error", "message": "Could not get a ChatGPT access token. Try again later."})
        return
    if not settings.get("model"):
        yield _sse("error", {"code": "no_model", "message": "No model selected. Run `taiwa-relay models` and `set-model`."})
        return

    history = session.history + [{"role": "user", "content": text}]
    parts: list[str] = []
    sources: dict[str, str] = {}
    t0 = time.monotonic()
    first = None
    yield _sse("status", {"stage": "thinking"})
    async for ev in chatgpt.stream_response(state.client, token, settings["model"], _instructions(settings),
                                            history, settings.get("reasoning_effort"),
                                            settings.get("reasoning_summary", "auto"),
                                            settings.get("web_search", True)):
        if ev.kind == "reasoning":
            yield _sse("reasoning", {"text": ev.text})
        elif ev.kind == "search":
            yield _sse("search", {"state": ev.text})
        elif ev.kind == "citation":
            sources.setdefault(ev.url, ev.text)
        elif ev.kind == "delta":
            if first is None:
                first = time.monotonic() - t0
            parts.append(ev.text)
            yield _sse("delta", {"text": ev.text})
        elif ev.kind == "done":
            session.history = (history + [{"role": "assistant", "content": "".join(parts)}])[-MAX_HISTORY_ITEMS:]
            log.info("answer ok: first token %.2fs, total %.2fs, %d chars, %d sources, history %d",
                     first or 0, time.monotonic() - t0, sum(map(len, parts)), len(sources), len(session.history))
            if sources:
                yield _sse("citations", {"items": [{"url": u, "title": t} for u, t in sources.items()]})
            yield _sse("answer_done", {})
            if speak:
                async for chunk in _speech(token, settings, text, "".join(parts)):
                    yield chunk
            yield _sse("done", {})
        else:
            log.warning("answer failed: code=%s request_id=%s", ev.code, ev.request_id)
            yield _sse("error", {"code": ev.code, "message": ev.text})


async def _speech(token: str, settings: dict, question: str, answer: str):
    """Stream a short spoken rendition of the answer, one sentence per `speech` event."""
    buf = ""
    t0 = time.monotonic()
    sentences = 0
    lang = languages.get(settings.get("language"))
    instructions = SPEECH_INSTRUCTIONS.format(
        language=lang.name, limit="about 150 characters" if lang.cjk else "about 60 words")
    prompt = f"Question: {question}\n\nWritten answer:\n{answer}"
    async for ev in chatgpt.stream_response(state.client, token, settings.get("speech_model") or settings["model"],
                                            instructions, [{"role": "user", "content": prompt}],
                                            settings.get("speech_effort")):
        if ev.kind == "delta":
            buf += ev.text
            *done, buf = SENTENCE_END.split(buf)
            for s in done:
                if s.strip():
                    sentences += 1
                    yield _sse("speech", {"text": s.strip()})
        elif ev.kind == "error":
            log.warning("speech failed: code=%s request_id=%s", ev.code, ev.request_id)
            return
    if buf.strip():
        sentences += 1
        yield _sse("speech", {"text": buf.strip()})
    log.info("speech ok: %d sentences in %.2fs", sentences, time.monotonic() - t0)


@app.get("/health")
async def health():
    return {"ok": True}


@app.get("/v1/status", dependencies=[Depends(require_token)])
async def status():
    creds = auth.load_credentials() or {}
    settings = config.load_settings()
    try:
        whisper_ok = (await state.client.get(config.WHISPER_URL + "/", timeout=3)).status_code < 500
    except httpx.HTTPError:
        whisper_ok = False
    return {
        "signed_in": bool(creds.get("refresh_token")),
        "plan_usage": auth.PLAN_SCOPE in creds.get("scopes", []),
        "email": creds.get("email"),
        "model": settings.get("model"),
        "language": settings.get("language"),
        "whisper": whisper_ok,
    }


@app.post("/v1/sessions/{sid}/turn", dependencies=[Depends(require_token)])
async def voice_turn(sid: str, request: Request):
    session = _session(sid)
    pcm = await request.body()
    if not pcm or len(pcm) > MAX_AUDIO_BYTES:
        raise HTTPException(status_code=400, detail="audio missing or too long")
    rate = int(request.headers.get("x-sample-rate", "16000"))

    async def gen():
        yield _sse("status", {"stage": "transcribing"})
        t0 = time.monotonic()
        lang = languages.get(config.load_settings().get("language"))
        try:
            text = await stt.transcribe(state.client, stt.pcm_to_wav(pcm, rate), lang)
        except httpx.HTTPError as e:
            log.warning("whisper failed: %s", e)
            yield _sse("error", {"code": "stt_failed",
                                 "message": "Speech recognition (whisper-server) is not reachable on the relay computer."})
            return
        log.info("stt %.2fs for %.1fs audio", time.monotonic() - t0, len(pcm) / (2 * rate))
        if not text:
            yield _sse("error", {"code": "no_speech", "message": "Didn't catch that. Press the Assistant button and try again."})
            return
        yield _sse("transcript", {"text": text})
        async with session.lock:
            async for chunk in _answer(session, text, _wants_speech(request)):
                yield chunk

    return StreamingResponse(gen(), media_type="text/event-stream", headers={"Cache-Control": "no-cache"})


def _wants_speech(request: Request) -> bool:
    """The TV sends X-Speech: 0 when auto-read is off; then no spoken version is generated."""
    return request.headers.get("x-speech", "1") != "0" and config.load_settings().get("speech", True)


@app.post("/v1/sessions/{sid}/text", dependencies=[Depends(require_token)])
async def text_turn(sid: str, request: Request):
    session = _session(sid)
    text = ((await request.json()).get("text") or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="text missing")

    async def gen():
        yield _sse("transcript", {"text": text})
        async with session.lock:
            async for chunk in _answer(session, text, _wants_speech(request)):
                yield chunk

    return StreamingResponse(gen(), media_type="text/event-stream", headers={"Cache-Control": "no-cache"})


@app.post("/v1/sessions/{sid}/speech", dependencies=[Depends(require_token)])
async def speech_on_demand(sid: str):
    """Spoken version of the session's last answer (when auto-read is off and the user presses OK)."""
    session = _session(sid)

    async def gen():
        h = session.history
        if len(h) < 2 or h[-1]["role"] != "assistant":
            yield _sse("error", {"code": "no_answer", "message": "There is no answer to read aloud yet."})
            return
        try:
            token = await _access_token()
        except Exception:  # noqa: BLE001
            yield _sse("error", {"code": "token_error", "message": "Could not get a ChatGPT access token."})
            return
        async for chunk in _speech(token, config.load_settings(), h[-2]["content"], h[-1]["content"]):
            yield chunk
        yield _sse("done", {})

    return StreamingResponse(gen(), media_type="text/event-stream", headers={"Cache-Control": "no-cache"})


async def _models() -> list[dict]:
    at, models = state.models
    if time.time() - at > MODELS_TTL or not models:
        models = await chatgpt.list_models(state.client, await _access_token())
        state.models = (time.time(), models)
    return models


@app.get("/v1/settings", dependencies=[Depends(require_token)])
async def get_settings():
    settings = config.load_settings()
    creds = auth.load_credentials() or {}
    try:
        models = await _models()
    except Exception as e:  # noqa: BLE001
        log.warning("model list failed: %s", e)
        models = []
    return {
        "model": settings.get("model"),
        "reasoning_effort": settings.get("reasoning_effort"),
        "web_search": settings.get("web_search", True),
        "language": settings.get("language"),
        "location": settings.get("location"),
        "models": models,
        "efforts": list(EFFORTS),
        "languages": [{"code": lang.code, "native": lang.native, "preview": lang.preview}
                      for lang in languages.LANGUAGES],
        "account": {"email": creds.get("email"), "signed_in": bool(creds.get("refresh_token"))},
    }


@app.put("/v1/settings", dependencies=[Depends(require_token)])
async def put_settings(request: Request):
    body = await request.json()
    settings = config.load_settings()
    if "model" in body:
        if body["model"] not in [m["slug"] for m in await _models()]:
            raise HTTPException(status_code=400, detail="unknown model")
        settings["model"] = body["model"]
    if "reasoning_effort" in body:
        if body["reasoning_effort"] not in EFFORTS:
            raise HTTPException(status_code=400, detail="bad reasoning_effort")
        settings["reasoning_effort"] = body["reasoning_effort"]
    if "web_search" in body:
        settings["web_search"] = bool(body["web_search"])
    if "language" in body:
        if body["language"] not in languages.BY_CODE:
            raise HTTPException(status_code=400, detail="unknown language")
        settings["language"] = body["language"]
    if "location" in body:
        settings["location"] = (str(body["location"]).strip() or None) if body["location"] else None
    config.save_settings(settings)
    log.info("settings updated from TV: %s", {k: body[k] for k in body if k != "relay_token"})
    return await get_settings()


@app.delete("/v1/sessions/{sid}", dependencies=[Depends(require_token)])
async def end_session(sid: str):
    state.sessions.pop(sid, None)
    return {"ok": True}
