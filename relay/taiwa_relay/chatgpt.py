"""Responses API calls billed to the user's ChatGPT plan.

Preview constraints (developers.openai.com/siwc/token-sharing-open-source/preview-limitations):
store=false, stream=true, full history in `input`, no system-role items,
and none of temperature / max_output_tokens / previous_response_id / ...
"""

import json
from collections.abc import AsyncIterator
from dataclasses import dataclass

import httpx

API = "https://api.openai.com/v1"

# Plain-English explanations for the documented plan-usage error codes. The TV shows its own
# translation when it knows the code and falls back to these otherwise.
ERROR_TEXT = {
    "subscription_sharing_usage_limit_exceeded": "ChatGPT plan usage limit reached. Try again later (see chatgpt.com/settings/usage).",
    "subscription_sharing_usage_unavailable": "ChatGPT plan usage could not be checked right now. Try again later.",
    "subscription_sharing_user_not_eligible": "This ChatGPT account or workspace can't use its plan in this app.",
    "subscription_sharing_unsupported_capability": "The selected model or option isn't supported.",
    "subscription_sharing_route_not_supported": "This endpoint isn't supported.",
    "subscription_sharing_invalid_user": "The ChatGPT sign-in is no longer valid. Run `taiwa-relay login` again.",
    "subscription_sharing_user_unavailable": "ChatGPT is temporarily unavailable. Try again later.",
}


@dataclass
class Event:
    kind: str  # "delta" | "reasoning" | "search" | "citation" | "done" | "error"
    text: str = ""
    code: str = ""
    request_id: str = ""
    url: str = ""


async def list_models(client: httpx.AsyncClient, token: str) -> list[dict]:
    r = await client.get(f"{API}/models", headers={"Authorization": f"Bearer {token}"}, timeout=20)
    r.raise_for_status()
    data = r.json()
    models = data.get("models") if isinstance(data.get("models"), list) else data.get("data", [])
    return [{"slug": m.get("slug") or m.get("id"), "display_name": m.get("display_name") or m.get("slug") or m.get("id")}
            for m in models if m.get("visibility", "list") == "list"]


def _error_from_body(status: int, body: bytes, request_id: str) -> Event:
    code, message = f"http_{status}", ""
    try:
        data = json.loads(body)
        if isinstance(data.get("error"), dict):
            code = data["error"].get("code") or code
            message = data["error"].get("message") or ""
        elif "detail" in data:  # direct-route admission errors
            message = str(data["detail"])
    except ValueError:
        message = body[:200].decode(errors="replace")
    text = ERROR_TEXT.get(code) or {
        401: "ChatGPT did not accept the sign-in. Run `taiwa-relay login` again.",
        403: "ChatGPT refused the request (permission or region).",
        429: "Too many requests or usage limit reached. Try again later.",
        503: "ChatGPT is temporarily unavailable. Try again later.",
    }.get(status, "The ChatGPT request failed.")
    return Event("error", text=text + (f"\n({message})" if message and code not in ERROR_TEXT else ""),
                 code=code, request_id=request_id)


def _citations(annotations) -> list[Event]:
    return [Event("citation", text=a.get("title") or "", url=a["url"])
            for a in annotations or [] if a.get("type") == "url_citation" and a.get("url")]


async def stream_response(client: httpx.AsyncClient, token: str, model: str, instructions: str,
                          history: list[dict], reasoning_effort: str | None = None,
                          reasoning_summary: str | None = None,
                          web_search: bool = False) -> AsyncIterator[Event]:
    body = {
        "model": model,
        "instructions": instructions,
        "input": history,
        "store": False,
        "stream": True,
    }
    reasoning = {}
    if reasoning_effort:
        reasoning["effort"] = reasoning_effort
    if reasoning_summary:
        reasoning["summary"] = reasoning_summary
    if reasoning:
        body["reasoning"] = reasoning
    if web_search:
        # The only hosted tool that ChatGPT plan usage allows (besides developer-defined functions).
        body["tools"] = [{"type": "web_search"}]
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json",
               "Accept": "text/event-stream"}
    async with client.stream("POST", f"{API}/responses", json=body, headers=headers,
                             timeout=httpx.Timeout(120, connect=15)) as r:
        request_id = r.headers.get("x-request-id", "")
        if r.status_code != 200:
            yield _error_from_body(r.status_code, await r.aread(), request_id)
            return
        completed = False
        async for line in r.aiter_lines():
            if not line.startswith("data:"):
                continue
            payload = line[5:].strip()
            if not payload or payload == "[DONE]":
                continue
            try:
                ev = json.loads(payload)
            except ValueError:
                continue
            t = ev.get("type", "")
            if t == "response.output_text.delta":
                yield Event("delta", text=ev.get("delta", ""))
            elif t == "response.reasoning_summary_text.delta":
                yield Event("reasoning", text=ev.get("delta", ""))
            elif t == "response.reasoning_summary_part.added":
                yield Event("reasoning", text="\n\n")
            elif t in ("response.web_search_call.in_progress", "response.web_search_call.searching"):
                yield Event("search", text="searching")
            elif t == "response.web_search_call.completed":
                yield Event("search", text="done")
            elif t == "response.output_text.annotation.added":
                for c in _citations([ev.get("annotation") or {}]):
                    yield c
            elif t == "response.completed":
                completed = True
                for item in (ev.get("response") or {}).get("output", []):
                    for part in item.get("content") or []:
                        for c in _citations(part.get("annotations")):
                            yield c
                yield Event("done", request_id=request_id)
                return
            elif t in ("response.failed", "error"):
                err = (ev.get("response") or {}).get("error") or ev.get("error") or ev
                code = err.get("code") or "response_failed"
                yield Event("error", text=ERROR_TEXT.get(code, f"ChatGPT failed: {err.get('message') or code}"),
                            code=code, request_id=request_id)
                return
            elif t == "response.incomplete":
                reason = ((ev.get("response") or {}).get("incomplete_details") or {}).get("reason", "")
                yield Event("error", text=f"The answer is incomplete ({reason or 'incomplete'}).", code="incomplete",
                            request_id=request_id)
                return
        if not completed:
            yield Event("error", text="The connection dropped before the answer finished.", code="stream_interrupted",
                    request_id=request_id)
