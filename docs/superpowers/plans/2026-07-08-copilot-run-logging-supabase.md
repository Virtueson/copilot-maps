# Copilot Run Logging to Supabase — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist one audit row per Copilot question to a Supabase table — session, the exact memory sent to the LLM, tools used, loop count, answer, latency — without changing where memory lives (phone) and without ever breaking the Copilot if logging fails.

**Architecture:** The agent loop starts returning metadata (`tools_used`, `loop_count`) alongside the reply. The `/copilot/ask` router times the run, builds a row, and fires a best-effort insert to Supabase's REST API as a FastAPI background task (zero added latency). The phone sends a `session_id` + `turn_index` so runs are grouped and missing rows show up as gaps. Logging is config-gated: no Supabase env → silent no-op.

**Tech Stack:** Backend — FastAPI (BackgroundTasks), Pydantic v2, httpx, pytest. App — Kotlin, Jetpack Compose, MVVM, Moshi/Retrofit, JUnit4.

## Global Constraints

- **NEVER stage or commit `backend/.env` or `local.properties`.** `SUPABASE_SERVICE_KEY` is a service-role key → only in git-ignored `backend/.env`, never client-side. Confirm `git status` before every commit. Never stage scratch files (`test_output.txt`, `README.md`, anything under `.superpowers/` or `docs/kaggle/`).
- **Backend tests never hit live Supabase or a live LLM** — stubs/fakes/DI only.
- **Logging is best-effort:** unconfigured (`SUPABASE_URL`/`SUPABASE_SERVICE_KEY` unset) → `log_run` is a no-op; a POST failure is swallowed with a warning naming `(session_id, turn_index, user_message[:40])`. A logging failure must never change the Copilot's HTTP response.
- **Fire-and-forget:** the insert runs via FastAPI `BackgroundTasks` (after the response is sent) so it adds no latency.
- **App unit tests are plain JVM JUnit** — no Android framework types.
- **Response contract is unchanged:** `/copilot/ask` still returns exactly `{reply, language}`. `session_id`/`turn_index` are request-only, used for logging.
- **Backend test command:** `cd backend && .\.venv\Scripts\python.exe -m pytest -q`
- **Android test command (JAVA_HOME required):**
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  cd "D:\Russell\Data Science Job\AI Maps\android"
  .\gradlew.bat compileDebugKotlin --console=plain
  .\gradlew.bat testDebugUnitTest --console=plain
  ```

---

## Task 1: Agent loop + providers return `AskResult(reply, tools_used, loop_count)`

**Files:**
- Modify: `backend/app/copilot/base.py` (add `AskResult`, update protocol)
- Modify: `backend/app/copilot/openai_compat.py` (loop returns metadata)
- Modify: `backend/app/copilot/anthropic_agent.py` (loop returns metadata)
- Modify: `backend/app/copilot/stub.py` (returns `AskResult`)
- Modify: `backend/app/routers/copilot.py` (unpack `.reply`)
- Test: `backend/tests/test_agent_loop.py`, `backend/tests/test_copilot.py`

**Interfaces:**
- Produces: `AskResult(reply: str, tools_used: list[str], loop_count: int)`; every provider's `ask(...) -> AskResult`; both agent loops return `AskResult`.

- [ ] **Step 1: Write the failing test — loop reports tools + loop count**

Update `backend/tests/test_agent_loop.py`'s two tests to read `.reply` and assert metadata. Replace the two `reply = asyncio.run(...)` / `assert reply == ...` blocks:

```python
    result = asyncio.run(run_agent_loop(
        client=client, model="m", system="s", tools=[], messages=[],
        execute_tool=fake_execute,
    ))
    assert executed == {"name": "search_places", "query": "gas"}
    assert result.reply == "There's a Shell ahead."
    assert result.tools_used == ["search_places"]
    assert result.loop_count == 2
    assert client.messages.calls == 2
```

and for the no-tool test:

```python
    result = asyncio.run(run_agent_loop(
        client=client, model="m", system="s", tools=[], messages=[],
        execute_tool=fake_execute,
    ))
    assert result.reply == "Take the expressway."
    assert result.tools_used == []
    assert result.loop_count == 1
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && .\.venv\Scripts\python.exe -m pytest tests/test_agent_loop.py -q`
Expected: FAIL — `run_agent_loop` returns a `str` (no `.reply`).

- [ ] **Step 3: Add `AskResult` and update the protocol**

`backend/app/copilot/base.py`:

```python
from dataclasses import dataclass, field
from typing import Protocol

from app.models import ChatMessage, CopilotContext


class CopilotError(Exception):
    """Raised when the copilot provider fails (mapped to HTTP 502)."""


@dataclass(frozen=True)
class AskResult:
    reply: str
    tools_used: list[str] = field(default_factory=list)
    loop_count: int = 0


class CopilotProvider(Protocol):
    async def ask(self, messages: list[ChatMessage], context: CopilotContext) -> "AskResult": ...
```

- [ ] **Step 4: Thread metadata through the Anthropic loop**

`backend/app/copilot/anthropic_agent.py` — import `AskResult` (`from app.copilot.base import CopilotError, AskResult`) and rewrite `run_agent_loop` to track tools + loops and return `AskResult`:

```python
async def run_agent_loop(client, model, system, tools, messages, execute_tool, max_iterations=_MAX_ITERATIONS):
    convo = list(messages)
    tools_used: list[str] = []
    loops = 0
    for _ in range(max_iterations):
        loops += 1
        response = await client.messages.create(
            model=model, max_tokens=_MAX_TOKENS, system=system, tools=tools, messages=convo,
        )
        if response.stop_reason == "tool_use":
            convo.append({"role": "assistant", "content": response.content})
            tool_results = []
            for block in response.content:
                if getattr(block, "type", None) == "tool_use":
                    tools_used.append(block.name)
                    result = await execute_tool(block.name, block.input)
                    tool_results.append({
                        "type": "tool_result", "tool_use_id": block.id, "content": result,
                    })
            convo.append({"role": "user", "content": tool_results})
            continue
        text = "".join(b.text for b in response.content if getattr(b, "type", None) == "text").strip()
        return AskResult(text, tools_used, loops)
    return AskResult("Sorry, I couldn't work that out just now.", tools_used, loops)
```

`AnthropicCopilot.ask` already `return await run_agent_loop(...)` — it now returns `AskResult` unchanged.

- [ ] **Step 5: Run the agent-loop test to verify it passes**

Run: `cd backend && .\.venv\Scripts\python.exe -m pytest tests/test_agent_loop.py -q`
Expected: PASS.

- [ ] **Step 6: Thread metadata through the OpenAI-compatible loop**

`backend/app/copilot/openai_compat.py` — import `AskResult` (`from app.copilot.base import CopilotError, AskResult`) and rewrite `run_openai_agent_loop` to track tools + loops:

```python
async def run_openai_agent_loop(client, model, messages, tools, execute_tool, max_iterations=_MAX_ITERATIONS):
    convo = list(messages)
    tools_used: list[str] = []
    loops = 0
    for _ in range(max_iterations):
        loops += 1
        response = await client.chat.completions.create(
            model=model, max_tokens=_MAX_TOKENS, messages=convo, tools=tools,
        )
        message = response.choices[0].message
        tool_calls = getattr(message, "tool_calls", None)
        if tool_calls:
            convo.append({
                "role": "assistant",
                "content": message.content or "",
                "tool_calls": [
                    {"id": tc.id, "type": "function",
                     "function": {"name": tc.function.name, "arguments": tc.function.arguments}}
                    for tc in tool_calls
                ],
            })
            for tc in tool_calls:
                tools_used.append(tc.function.name)
                try:
                    args = json.loads(tc.function.arguments or "{}")
                except json.JSONDecodeError:
                    args = {}
                result = await execute_tool(tc.function.name, args)
                convo.append({"role": "tool", "tool_call_id": tc.id, "content": result})
            continue
        return AskResult((message.content or "").strip(), tools_used, loops)
    return AskResult("Sorry, I couldn't work that out just now.", tools_used, loops)
```

`OpenAICompatCopilot.ask` already `return await run_openai_agent_loop(...)` — now returns `AskResult`.

- [ ] **Step 7: Update the stub copilot**

`backend/app/copilot/stub.py` — import `AskResult` and wrap the two returns:

```python
from app.copilot.base import AskResult
from app.models import ChatMessage, CopilotContext


class StubCopilot:
    async def ask(self, messages: list[ChatMessage], context: CopilotContext) -> AskResult:
        last_user = next((m.content for m in reversed(messages) if m.role == "user"), "")
        n = len(context.routes)
        if n == 0:
            return AskResult(f"(stub) You asked: '{last_user}'. No route is planned yet.", [], 1)
        fastest = min(context.routes, key=lambda r: r.duration_seconds)
        mins = round(fastest.duration_seconds / 60)
        return AskResult(
            f"(stub) You asked: '{last_user}'. You have {n} route(s); "
            f"the fastest is {fastest.summary} at about {mins} min.",
            [], 1,
        )
```

- [ ] **Step 8: Unpack `.reply` in the router**

`backend/app/routers/copilot.py` — change the call + response to use the result's reply:

```python
    try:
        result = await copilot.ask(request.messages, request.context)
    except CopilotError as exc:
        raise HTTPException(status_code=502, detail=f"Copilot error: {exc}") from exc
    return CopilotAskResponse(reply=result.reply, language=detect_language(result.reply))
```

- [ ] **Step 9: Fix the existing copilot tests to the new return type**

`backend/tests/test_copilot.py`:
- `test_stub_reply_mentions_route_count`: change to
  ```python
      result = asyncio.run(StubCopilot().ask(messages, _context()))
      assert isinstance(result.reply, str)
      assert result.reply
      assert "1" in result.reply
  ```
- `_FixedCopilot.ask`: `return AskResult("fixed reply about " + str(len(context.routes)) + " routes", [], 1)`
- `_FakeCopilot.ask`: `return AskResult(self._reply, [], 1)`
- Add the import: `from app.copilot.base import AskResult`

(The `/copilot/ask` assertions — `reply == "fixed reply about 1 routes"`, `language == ...` — stay valid because the router now reads `result.reply`.)

- [ ] **Step 10: Run the full backend suite**

Run: `cd backend && .\.venv\Scripts\python.exe -m pytest -q`
Expected: PASS (all existing + updated).

- [ ] **Step 11: Commit**

```bash
git add backend/app/copilot/base.py backend/app/copilot/openai_compat.py backend/app/copilot/anthropic_agent.py backend/app/copilot/stub.py backend/app/routers/copilot.py backend/tests/test_agent_loop.py backend/tests/test_copilot.py
git commit -m "feat(backend): agent loop returns reply + tools_used + loop_count"
```

---

## Task 2: Supabase config + `run_log` module (`build_run_row` + `log_run`)

**Files:**
- Modify: `backend/app/config.py` (Supabase settings)
- Create: `backend/app/copilot/run_log.py`
- Test: `backend/tests/test_run_log.py` (new)

**Interfaces:**
- Consumes: `AskResult` (Task 1), `CopilotContext`, `ChatMessage`.
- Produces: `Settings.supabase_url`, `Settings.supabase_service_key`; `build_run_row(session_id, turn_index, messages, context, result, answer_language, model, provider, latency_ms, error) -> dict`; `async log_run(row, client=None) -> None`.

- [ ] **Step 1: Add Supabase settings**

`backend/app/config.py` — add two fields to `Settings` (after `model_name`):

```python
    supabase_url: str = ""
    supabase_service_key: str = ""
```

- [ ] **Step 2: Write the failing tests**

Create `backend/tests/test_run_log.py`:

```python
import asyncio

import pytest

from app.copilot import run_log
from app.copilot.base import AskResult
from app.models import ChatMessage, CopilotContext, LatLng, RouteSummary


def _messages():
    return [
        ChatMessage(role="user", content="any traffic?"),
        ChatMessage(role="assistant", content="Yes, heavy."),
        ChatMessage(role="user", content="find gas"),
    ]


def _context():
    return CopilotContext(
        origin=LatLng(lat=-6.2, lng=106.8),
        selected_route_polyline="abc",
        routes=[RouteSummary(summary="A", distance_meters=8200, duration_seconds=1320,
                             traffic="heavy", selected=True)],
    )


def test_build_run_row_maps_all_fields():
    result = AskResult("There's a Shell ahead.", ["search_places"], 2)
    row = run_log.build_run_row(
        session_id="s1", turn_index=3, messages=_messages(), context=_context(),
        result=result, answer_language="en", model="deepseek", provider="openai",
        latency_ms=1840, error=None,
    )
    assert row["session_id"] == "s1"
    assert row["turn_index"] == 3
    assert row["user_message"] == "find gas"
    assert row["memory_messages"] == [
        {"role": "user", "content": "any traffic?"},
        {"role": "assistant", "content": "Yes, heavy."},
        {"role": "user", "content": "find gas"},
    ]
    assert row["trip_context"]["routes"][0]["summary"] == "A"
    assert row["tools_used"] == ["search_places"]
    assert row["loop_count"] == 2
    assert row["answer"] == "There's a Shell ahead."
    assert row["answer_language"] == "en"
    assert row["latency_ms"] == 1840
    assert row["error"] is None


def test_build_run_row_error_case_has_empty_answer():
    row = run_log.build_run_row(
        session_id="s1", turn_index=0, messages=_messages(), context=_context(),
        result=None, answer_language=None, model="deepseek", provider="openai",
        latency_ms=12, error="boom",
    )
    assert row["answer"] == ""
    assert row["tools_used"] == []
    assert row["loop_count"] is None
    assert row["error"] == "boom"


class _RaisingClient:
    async def post(self, *a, **k):
        raise RuntimeError("network down")


def test_log_run_noop_when_unconfigured(monkeypatch):
    # No SUPABASE_URL/KEY -> must not touch the client at all.
    monkeypatch.setattr(run_log, "get_settings", lambda: _FakeSettings("", ""))
    asyncio.run(run_log.log_run({"session_id": "s"}, client=_RaisingClient()))  # no raise


def test_log_run_swallows_post_failure(monkeypatch):
    monkeypatch.setattr(run_log, "get_settings", lambda: _FakeSettings("https://x", "key"))
    # Must swallow the RuntimeError, not propagate.
    asyncio.run(run_log.log_run({"session_id": "s", "turn_index": 1, "user_message": "hi"},
                                client=_RaisingClient()))


class _FakeSettings:
    def __init__(self, url, key):
        self.supabase_url = url
        self.supabase_service_key = key
```

- [ ] **Step 3: Run it to verify it fails**

Run: `cd backend && .\.venv\Scripts\python.exe -m pytest tests/test_run_log.py -q`
Expected: FAIL — module `app.copilot.run_log` does not exist.

- [ ] **Step 4: Implement `run_log`**

Create `backend/app/copilot/run_log.py`:

```python
"""Best-effort audit logging of Copilot runs to Supabase (PostgREST).

`build_run_row` is pure (assembles the row). `log_run` is fire-and-forget: it
returns immediately when Supabase is unconfigured, and swallows any POST failure
(logging a warning that names the run) so logging can never break the Copilot.
"""

import logging

import httpx

from app.config import get_settings

logger = logging.getLogger("copilot.run_log")


def build_run_row(
    session_id, turn_index, messages, context, result, answer_language,
    model, provider, latency_ms, error,
) -> dict:
    last_user = next((m.content for m in reversed(messages) if m.role == "user"), "")
    return {
        "session_id": session_id,
        "turn_index": turn_index,
        "user_message": last_user,
        "memory_messages": [{"role": m.role, "content": m.content} for m in messages],
        "trip_context": {
            "origin": {"lat": context.origin.lat, "lng": context.origin.lng},
            "selected_route_polyline": context.selected_route_polyline,
            "routes": [
                {
                    "summary": r.summary,
                    "distance_meters": r.distance_meters,
                    "duration_seconds": r.duration_seconds,
                    "traffic": r.traffic,
                    "selected": r.selected,
                }
                for r in context.routes
            ],
        },
        "tools_used": result.tools_used if result else [],
        "loop_count": result.loop_count if result else None,
        "answer": result.reply if result else "",
        "answer_language": answer_language,
        "model": model,
        "provider": provider,
        "latency_ms": latency_ms,
        "error": error,
    }


async def log_run(row: dict, client=None) -> None:
    settings = get_settings()
    if not (settings.supabase_url and settings.supabase_service_key):
        return  # logging disabled — silent no-op

    owns_client = client is None
    client = client or httpx.AsyncClient(timeout=5.0)
    try:
        resp = await client.post(
            f"{settings.supabase_url}/rest/v1/copilot_runs",
            json=row,
            headers={
                "apikey": settings.supabase_service_key,
                "Authorization": f"Bearer {settings.supabase_service_key}",
                "Content-Type": "application/json",
                "Prefer": "return=minimal",
            },
        )
        resp.raise_for_status()
    except Exception as e:  # never let logging break the request
        logger.warning(
            "copilot run logging FAILED session=%s turn=%s q=%r: %s",
            row.get("session_id"), row.get("turn_index"),
            (row.get("user_message") or "")[:40], e,
        )
    finally:
        if owns_client:
            await client.aclose()
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && .\.venv\Scripts\python.exe -m pytest tests/test_run_log.py -q`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/app/config.py backend/app/copilot/run_log.py backend/tests/test_run_log.py
git commit -m "feat(backend): Supabase run-log module (build_run_row + best-effort log_run)"
```

---

## Task 3: Wire logging into `/copilot/ask` (session_id, turn_index, timing, fire-and-forget)

**Files:**
- Modify: `backend/app/models.py` (`CopilotAskRequest`)
- Modify: `backend/app/routers/copilot.py` (time, build row, background insert, error-path log)
- Test: `backend/tests/test_copilot.py`

**Interfaces:**
- Consumes: `build_run_row`, `log_run` (Task 2), `AskResult` (Task 1), `get_settings`.
- Produces: `CopilotAskRequest.session_id`, `CopilotAskRequest.turn_index`.

- [ ] **Step 1: Add request fields**

`backend/app/models.py` — extend `CopilotAskRequest`:

```python
class CopilotAskRequest(BaseModel):
    messages: list[ChatMessage]
    context: CopilotContext
    session_id: str | None = None
    turn_index: int | None = None
```

- [ ] **Step 2: Write the failing tests**

Add to `backend/tests/test_copilot.py`:

```python
def test_ask_accepts_session_and_turn_and_still_returns_reply():
    client = _client()  # _FixedCopilot, Supabase unconfigured
    body = {
        "messages": [{"role": "user", "content": "hi"}],
        "context": {"origin": {"lat": 1.0, "lng": 2.0}, "routes": []},
        "session_id": "sess-1",
        "turn_index": 0,
    }
    resp = client.post("/copilot/ask", json=body)
    assert resp.status_code == 200
    assert resp.json()["reply"] == "fixed reply about 0 routes"
    assert set(resp.json().keys()) == {"reply", "language"}  # contract unchanged
```

(Supabase is unconfigured in tests, so `log_run` is a no-op — this asserts logging wiring doesn't alter or break the response.)

- [ ] **Step 3: Run it to verify it fails**

Run: `cd backend && .\.venv\Scripts\python.exe -m pytest tests/test_copilot.py::test_ask_accepts_session_and_turn_and_still_returns_reply -q`
Expected: FAIL — `CopilotAskRequest` rejects `session_id`/`turn_index` (422) or the test predates the field... run to confirm the actual failure, then implement.

- [ ] **Step 4: Wire the router**

`backend/app/routers/copilot.py` — full file:

```python
import time

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException

from app.config import get_copilot, get_settings
from app.copilot.base import CopilotError
from app.copilot.language import detect_language
from app.copilot.run_log import build_run_row, log_run
from app.models import CopilotAskRequest, CopilotAskResponse

router = APIRouter()


@router.post("/copilot/ask", response_model=CopilotAskResponse)
async def copilot_ask(
    request: CopilotAskRequest,
    background_tasks: BackgroundTasks,
    copilot=Depends(get_copilot),
    settings=Depends(get_settings),
) -> CopilotAskResponse:
    t0 = time.monotonic()
    try:
        result = await copilot.ask(request.messages, request.context)
    except CopilotError as exc:
        latency_ms = int((time.monotonic() - t0) * 1000)
        background_tasks.add_task(log_run, build_run_row(
            session_id=request.session_id, turn_index=request.turn_index,
            messages=request.messages, context=request.context, result=None,
            answer_language=None, model=settings.model_name,
            provider=settings.copilot_provider, latency_ms=latency_ms, error=str(exc),
        ))
        raise HTTPException(status_code=502, detail=f"Copilot error: {exc}") from exc

    latency_ms = int((time.monotonic() - t0) * 1000)
    language = detect_language(result.reply)
    background_tasks.add_task(log_run, build_run_row(
        session_id=request.session_id, turn_index=request.turn_index,
        messages=request.messages, context=request.context, result=result,
        answer_language=language, model=settings.model_name,
        provider=settings.copilot_provider, latency_ms=latency_ms, error=None,
    ))
    return CopilotAskResponse(reply=result.reply, language=language)
```

- [ ] **Step 5: Run the full backend suite**

Run: `cd backend && .\.venv\Scripts\python.exe -m pytest -q`
Expected: PASS (all existing + new). Logging is unconfigured in tests, so background tasks are no-ops.

- [ ] **Step 6: Commit**

```bash
git add backend/app/models.py backend/app/routers/copilot.py backend/tests/test_copilot.py
git commit -m "feat(backend): log each /copilot/ask run to Supabase (fire-and-forget)"
```

---

## Task 4: App sends `session_id` + `turn_index`

**Files:**
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/network/CopilotDtos.kt`
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/data/CopilotRepository.kt`
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/copilot/CopilotViewModel.kt`
- Test: `android/app/src/test/java/com/virtueson/copilotmaps/ui/copilot/CopilotViewModelTest.kt`, `CopilotVoiceViewModelTest.kt` (fake signature)

**Interfaces:**
- Produces: `CopilotAskRequestDto.session_id` + `turn_index`; `CopilotRepository.ask(messages, context, sessionId, turnIndex)`.

- [ ] **Step 1: Add DTO fields**

`network/CopilotDtos.kt` — extend `CopilotAskRequestDto`:

```kotlin
data class CopilotAskRequestDto(
    val messages: List<ChatMessageDto>,
    val context: CopilotContextDto,
    @Json(name = "session_id") val sessionId: String? = null,
    @Json(name = "turn_index") val turnIndex: Int? = null,
)
```

- [ ] **Step 2: Widen the repository interface**

`data/CopilotRepository.kt`:

```kotlin
interface CopilotRepository {
    suspend fun ask(
        messages: List<ChatMessage>,
        context: TripContext,
        sessionId: String,
        turnIndex: Int,
    ): CopilotResult
}
```

In `DefaultCopilotRepository.ask`, accept the two params and set them on the DTO:

```kotlin
    override suspend fun ask(
        messages: List<ChatMessage>,
        context: TripContext,
        sessionId: String,
        turnIndex: Int,
    ): CopilotResult {
        return try {
            val response = api.ask(
                CopilotAskRequestDto(
                    messages = messages.map { ChatMessageDto(it.role.name.lowercase(), it.content) },
                    context = CopilotContextDto( /* unchanged */
                        origin = LatLngDto(context.origin.lat, context.origin.lng),
                        selectedRoutePolyline = context.selectedRoutePolyline,
                        routes = context.routes.map {
                            RouteSummaryDto(
                                summary = it.summary,
                                distanceMeters = it.distanceMeters,
                                durationSeconds = it.durationSeconds,
                                traffic = it.traffic,
                                selected = it.selected,
                            )
                        },
                    ),
                    sessionId = sessionId,
                    turnIndex = turnIndex,
                )
            )
            CopilotResult.Success(response.reply, response.language)
        } catch (e: Exception) {
            CopilotResult.Failure("Can't reach the server — is the backend running and adb reverse set?")
        }
    }
```

- [ ] **Step 3: Generate and send session/turn in the ViewModel**

`ui/copilot/CopilotViewModel.kt`:
- Add imports: `import java.util.UUID`.
- Add fields to the class body:
  ```kotlin
  private val sessionId: String = UUID.randomUUID().toString()
  private var turnIndex: Int = 0
  ```
- In `sendMessage`, capture the turn synchronously (before the launch) and pass both, then increment. Change the start of the `viewModelScope.launch` block's `ask` call:
  ```kotlin
      val turn = turnIndex
      turnIndex += 1
      viewModelScope.launch {
          val history = withUser.takeLast(MAX_TURNS)
          when (val result = repository.ask(history, context, sessionId, turn)) {
              // ... unchanged ...
  ```
  (Keep everything else in `sendMessage`/the `when` exactly as-is.)

> Note: there is no separate "clear conversation" path in `CopilotViewModel` today, so `sessionId` is created once per ViewModel and `turnIndex` increases monotonically. If a conversation-reset action is added later, regenerate `sessionId` and reset `turnIndex = 0` there.

- [ ] **Step 4: Update the test fakes to the new signature**

In `CopilotViewModelTest.kt` and `CopilotVoiceViewModelTest.kt`, update `FakeCopilotRepository.ask`:

```kotlin
    override suspend fun ask(
        messages: List<ChatMessage>,
        context: TripContext,
        sessionId: String,
        turnIndex: Int,
    ): CopilotResult = result
```

- [ ] **Step 5: Compile + run the app unit suite**

Run (JAVA_HOME set):
```
.\gradlew.bat compileDebugKotlin --console=plain
.\gradlew.bat testDebugUnitTest --console=plain
```
Expected: BUILD SUCCESSFUL; existing Copilot ViewModel tests green with the new fake signature.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/virtueson/copilotmaps/network/CopilotDtos.kt android/app/src/main/java/com/virtueson/copilotmaps/data/CopilotRepository.kt android/app/src/main/java/com/virtueson/copilotmaps/ui/copilot/CopilotViewModel.kt android/app/src/test/java/com/virtueson/copilotmaps/ui/copilot/CopilotViewModelTest.kt android/app/src/test/java/com/virtueson/copilotmaps/ui/copilot/CopilotVoiceViewModelTest.kt
git commit -m "feat(app): send session_id + turn_index with each Copilot request"
```

---

## Developer setup (one-time, manual — not a code task)

Before the feature does anything, the developer creates the table and sets env:

1. In the Supabase SQL editor, run the `copilot_runs` DDL from the spec (§ "The table").
2. Add to git-ignored `backend/.env`:
   ```
   SUPABASE_URL=https://<project>.supabase.co
   SUPABASE_SERVICE_KEY=<service-role key>
   ```
3. Restart the backend. Ask the Copilot something that triggers `search_places`; confirm a row appears with `memory_messages`, `tools_used`, `loop_count`, and `answer` populated, and that turns share a `session_id` with incrementing `turn_index`.

Until env is set, logging is a silent no-op — the app and tests behave exactly as before.

---

## Self-Review

- **Spec coverage:** `copilot_runs` schema (Task 2 `build_run_row` + manual DDL) ✓; `memory_messages` verbatim (Task 2) ✓; tools_used + loop_count from the loop (Task 1) ✓; session_id + turn_index (Tasks 3, 4) ✓; best-effort/no-op-unconfigured/swallow-with-named-warning (Task 2) ✓; fire-and-forget via BackgroundTasks (Task 3) ✓; error-path row (Task 3) ✓; response contract unchanged (Task 3 test) ✓; config-gated env (Task 2) ✓; app session/turn generation + reset note (Task 4) ✓.
- **Type consistency:** `AskResult(reply, tools_used, loop_count)` used by both loops, all providers, the router, and `build_run_row` (Tasks 1–3); `build_run_row(session_id, turn_index, messages, context, result, answer_language, model, provider, latency_ms, error)` identical in Task 2 impl and Task 3 call sites; `ask(messages, context, sessionId, turnIndex)` identical across interface, impl, and fakes (Task 4).
- **Placeholders:** none — every code step shows real code.
- **Deviation note:** the spec's "fire-and-forget via `asyncio.create_task`" is realized with FastAPI `BackgroundTasks` — same zero-latency intent, and testable under `TestClient`.
