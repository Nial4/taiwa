"""Speech-to-text via the local whisper.cpp server (audio never leaves the LAN).

Sign in with ChatGPT plan usage does not cover the transcription API, so STT runs on this computer.
"""

import io
import re
import wave
from functools import lru_cache

import httpx
from opencc import OpenCC

from . import config, languages

# Whisper hallucinates subtitle credits from its training data on silence or noise. These substrings
# are specific enough never to occur in a real question; short phrases only count as a whole utterance.
_HALLUCINATION_PARTS = re.compile(
    r"(amara\.org|字幕由|字幕志愿者|请不吝点赞|订阅转发|明镜与点点|优优独播剧场|yoyo television|subtitles by)",
    re.IGNORECASE,
)


@lru_cache(maxsize=4)
def _opencc(conversion: str) -> OpenCC:
    return OpenCC(conversion)


def _bare(text: str) -> str:
    return re.sub(r"[\W_]+", "", text).lower()


def pcm_to_wav(pcm: bytes, rate: int = 16000) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm)
    return buf.getvalue()


async def transcribe(client: httpx.AsyncClient, wav: bytes, language: languages.Language) -> str:
    data = {"temperature": "0.0", "response_format": "json", "language": language.whisper}
    if language.prompt:
        data["prompt"] = language.prompt
    r = await client.post(f"{config.WHISPER_URL}/inference",
                          files={"file": ("speech.wav", wav, "audio/wav")}, data=data, timeout=60)
    r.raise_for_status()
    text = (r.json().get("text") or "").strip()
    if language.opencc:
        text = _opencc(language.opencc).convert(text)
    text = re.sub(r"\s+", " ", text).strip(" 　")
    bare = _bare(text)
    if (not bare or _HALLUCINATION_PARTS.search(text)
            or bare in {_bare(h) for h in language.hallucinations}):
        return ""
    return text
