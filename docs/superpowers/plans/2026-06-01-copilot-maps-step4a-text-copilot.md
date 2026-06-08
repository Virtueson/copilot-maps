# Copilot Maps — Step 4a (Text Copilot) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A text chat where you ask plain-language questions about your trip and a Claude (Haiku) agent answers — reasoning over the current routes/traffic context and calling a `search_places` tool (reusing Step 3) for "what's on my way."

**Architecture:** A `CopilotProvider` protocol (stub + Anthropic) behind `POST /copilot/ask`. The Anthropic provider runs a manual async tool-use loop. The app adds a `CopilotViewModel` holding the in-RAM session chat and a bottom-sheet chat UI; it sends current trip context each turn.

**Tech Stack:** Existing stack + `anthropic` Python SDK (`AsyncAnthropic`), model `claude-haiku-4-5`. No new app dependencies.

---

## Conventions (unchanged)

- Backend at `backend/`: `.\.venv\Scripts\python.exe -m pytest` / `... -m uvicorn app.main:app --port 8000`.
- Gradle: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; cd android; .\gradlew.bat <task>`.
- Phone ↔ backend via `adb reverse tcp:8000 tcp:8000`; base URL `http://localhost:8000/`.
- **Model note:** `claude-haiku-4-5` is a deliberate latency/cost choice (you approved Haiku). To use a stronger model later, change the one `_MODEL` constant.

## File Structure

```
backend/app/
  models.py              # + ChatMessage, RouteSummary, CopilotContext, CopilotAsk*
  places/search.py       # NEW: search_with_fallback (shared by router + copilot tool)
  routers/places.py      # MODIFIED: use search_with_fallback (DRY)
  copilot/
    __init__.py          # NEW (empty)
    base.py              # NEW: CopilotProvider protocol + CopilotError
    stub.py              # NEW: StubCopilot
    tools.py             # NEW: search_places schema + executor
    anthropic_agent.py   # NEW: run_agent_loop + AnthropicCopilot
  routers/copilot.py     # NEW: POST /copilot/ask
  config.py              # + copilot_provider, anthropic_api_key, get_copilot()
  main.py                # register copilot router
  requirements.txt       # + anthropic
backend/tests/
  test_copilot_tools.py  # NEW
  test_agent_loop.py     # NEW (fake Anthropic client)
  test_copilot.py        # NEW (endpoint, stub)

android/app/src/main/java/com/virtueson/copilotmaps/
  network/CopilotDtos.kt # NEW
  network/CopilotApi.kt  # NEW
  network/NetworkModule.kt  # MODIFIED: + copilotApi
  data/ChatMessage.kt    # NEW: Role, ChatMessage, RouteSummary, TripContext, CopilotResult
  data/CopilotRepository.kt # NEW
  ui/copilot/CopilotState.kt    # NEW
  ui/copilot/CopilotViewModel.kt # NEW
  ui/copilot/CopilotChatSheet.kt # NEW (the chat UI)
  ui/map/MapScreen.kt    # MODIFIED: chat FAB + sheet wiring + TripContext builder
android/app/src/test/java/com/virtueson/copilotmaps/ui/copilot/
  CopilotViewModelTest.kt # NEW
```

---

# PHASE 1 — Backend (stub + agent loop, all tested offline)

### Task 1: Contract models + copilot skeleton + dependency

**Files:**
- Modify: `backend/app/models.py`, `backend/requirements.txt`
- Create: `backend/app/copilot/__init__.py`

- [ ] **Step 1: Append copilot models to `models.py`**

Append to `backend/app/models.py`:
```python
class ChatMessage(BaseModel):
    role: str  # "user" | "assistant"
    content: str


class RouteSummary(BaseModel):
    summary: str
    distance_meters: int
    duration_seconds: int
    traffic: str  # "light" | "moderate" | "heavy"
    selected: bool


class CopilotContext(BaseModel):
    origin: LatLng
    selected_route_polyline: str | None = None
    routes: list[RouteSummary] = []


class CopilotAskRequest(BaseModel):
    messages: list[ChatMessage]
    context: CopilotContext


class CopilotAskResponse(BaseModel):
    reply: str
```

- [ ] **Step 2: Add `anthropic` to `requirements.txt`**

Append a line to `backend/requirements.txt`:
```
anthropic>=0.40
```

- [ ] **Step 3: Install it + create the package marker**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```
Create `backend/app/copilot/__init__.py` (empty).

- [ ] **Step 4: Verify imports**

```powershell
.\.venv\Scripts\python.exe -c "import anthropic; from app.models import CopilotAskRequest; print('ok')"
```
Expected: `ok`.

- [ ] **Step 5: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add backend/app/models.py backend/requirements.txt backend/app/copilot/__init__.py
git commit -m "feat(backend): copilot contract models + anthropic dep"
```

---

### Task 2: Shared places fallback helper (DRY) + refactor places router

**Files:**
- Create: `backend/app/places/search.py`
- Modify: `backend/app/routers/places.py`

- [ ] **Step 1: Create `places/search.py`**

```python
from app.models import LatLng, Place
from app.places.base import PlacesProvider


async def search_with_fallback(
    provider: PlacesProvider,
    query: str,
    origin: LatLng,
    polyline: str | None,
) -> tuple[str, list[Place]]:
    """Along-route search, falling back to nearby. Returns (mode, places)."""
    places: list[Place] = []
    mode = "nearby"
    if polyline:
        places = await provider.along_route(query, polyline)
        mode = "along_route"
    if not places:
        places = await provider.nearby(query, origin)
        mode = "nearby"
    return mode, places
```

- [ ] **Step 2: Refactor `routers/places.py` to use it**

Replace the body of `search_places` in `backend/app/routers/places.py` so the file reads:
```python
import httpx
from fastapi import APIRouter, Depends, HTTPException

from app.config import get_places_provider
from app.models import PlacesSearchRequest, PlacesSearchResponse
from app.places.base import PlacesProvider
from app.places.search import search_with_fallback

router = APIRouter()


@router.post("/places/search", response_model=PlacesSearchResponse)
async def search_places(
    request: PlacesSearchRequest,
    provider: PlacesProvider = Depends(get_places_provider),
) -> PlacesSearchResponse:
    try:
        mode, places = await search_with_fallback(
            provider, request.query, request.origin, request.polyline
        )
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=f"Places provider error: {exc}") from exc
    return PlacesSearchResponse(mode=mode, places=places)
```

- [ ] **Step 3: Confirm existing places tests still pass**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"
.\.venv\Scripts\python.exe -m pytest tests/test_places.py -q
```
Expected: all pass (behavior unchanged).

- [ ] **Step 4: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add backend/app/places/search.py backend/app/routers/places.py
git commit -m "refactor(backend): extract search_with_fallback (shared by router + copilot)"
```

---

### Task 3: `CopilotProvider` protocol + `StubCopilot` (TDD)

**Files:**
- Create: `backend/app/copilot/base.py`, `backend/app/copilot/stub.py`
- Test: `backend/tests/test_copilot.py` (stub portion)

- [ ] **Step 1: Write the failing test**

`backend/tests/test_copilot.py`:
```python
import asyncio

from app.copilot.stub import StubCopilot
from app.models import ChatMessage, CopilotContext, LatLng, RouteSummary


def _context() -> CopilotContext:
    return CopilotContext(
        origin=LatLng(lat=1.0, lng=2.0),
        selected_route_polyline=None,
        routes=[
            RouteSummary(summary="Main St", distance_meters=12400, duration_seconds=960,
                         traffic="moderate", selected=True),
        ],
    )


def test_stub_reply_mentions_route_count():
    messages = [ChatMessage(role="user", content="which route is fastest?")]

    reply = asyncio.run(StubCopilot().ask(messages, _context()))

    assert isinstance(reply, str)
    assert reply
    assert "1" in reply  # references the one route in context
```

- [ ] **Step 2: Run it to verify it fails**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"
.\.venv\Scripts\python.exe -m pytest tests/test_copilot.py::test_stub_reply_mentions_route_count -v
```
Expected: FAIL — `ModuleNotFoundError: app.copilot.stub`.

- [ ] **Step 3: Write `base.py`**

```python
from typing import Protocol

from app.models import ChatMessage, CopilotContext


class CopilotError(Exception):
    """Raised when the copilot provider fails (mapped to HTTP 502)."""


class CopilotProvider(Protocol):
    async def ask(self, messages: list[ChatMessage], context: CopilotContext) -> str: ...
```

- [ ] **Step 4: Write `stub.py`**

```python
from app.models import ChatMessage, CopilotContext


class StubCopilot:
    """Deterministic offline copilot: summarizes context, makes no API call."""

    async def ask(self, messages: list[ChatMessage], context: CopilotContext) -> str:
        last_user = next((m.content for m in reversed(messages) if m.role == "user"), "")
        n = len(context.routes)
        if n == 0:
            return f"(stub) You asked: '{last_user}'. No route is planned yet."
        fastest = min(context.routes, key=lambda r: r.duration_seconds)
        mins = round(fastest.duration_seconds / 60)
        return (
            f"(stub) You asked: '{last_user}'. You have {n} route(s); "
            f"the fastest is {fastest.summary} at about {mins} min."
        )
```

- [ ] **Step 5: Run the test to verify it passes**

```powershell
.\.venv\Scripts\python.exe -m pytest tests/test_copilot.py::test_stub_reply_mentions_route_count -v
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add backend/app/copilot/base.py backend/app/copilot/stub.py backend/tests/test_copilot.py
git commit -m "feat(backend): CopilotProvider protocol + StubCopilot with test"
```

---

### Task 4: `search_places` tool (schema + executor) (TDD)

**Files:**
- Create: `backend/app/copilot/tools.py`
- Test: `backend/tests/test_copilot_tools.py`

- [ ] **Step 1: Write the failing test**

`backend/tests/test_copilot_tools.py`:
```python
import asyncio

from app.copilot.tools import SEARCH_PLACES_TOOL, execute_search_places
from app.models import CopilotContext, LatLng, Place


class _FakeProvider:
    def __init__(self, along, near):
        self._along = along
        self._near = near

    async def along_route(self, query, polyline):
        return self._along

    async def nearby(self, query, origin):
        return self._near


def _place(name):
    return Place(id=name, name=name, lat=1.0, lng=2.0, rating=4.5)


def test_tool_schema_shape():
    assert SEARCH_PLACES_TOOL["name"] == "search_places"
    assert "query" in SEARCH_PLACES_TOOL["input_schema"]["properties"]


def test_executor_uses_route_when_polyline_present():
    ctx = CopilotContext(origin=LatLng(lat=1.0, lng=2.0), selected_route_polyline="abc")
    provider = _FakeProvider(along=[_place("OnRoute")], near=[_place("Nearby")])

    result = asyncio.run(execute_search_places("gas station", ctx, provider))

    assert "on the route" in result
    assert "OnRoute" in result


def test_executor_falls_back_to_nearby():
    ctx = CopilotContext(origin=LatLng(lat=1.0, lng=2.0), selected_route_polyline="abc")
    provider = _FakeProvider(along=[], near=[_place("Nearby")])

    result = asyncio.run(execute_search_places("gas station", ctx, provider))

    assert "nearby" in result
    assert "Nearby" in result
```

- [ ] **Step 2: Run it to verify it fails**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"
.\.venv\Scripts\python.exe -m pytest tests/test_copilot_tools.py -v
```
Expected: FAIL — `ModuleNotFoundError: app.copilot.tools`.

- [ ] **Step 3: Write `tools.py`**

```python
from app.models import CopilotContext, Place
from app.places.base import PlacesProvider
from app.places.search import search_with_fallback

SEARCH_PLACES_TOOL = {
    "name": "search_places",
    "description": (
        "Find places such as gas stations, restaurants, ATMs, or any category on the "
        "user's current route (or near them if no route is planned). Pass a natural "
        "language query like 'gas station' or 'french restaurant'."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "query": {
                "type": "string",
                "description": "What to search for, e.g. 'gas station' or 'coffee'.",
            }
        },
        "required": ["query"],
    },
}


def _format_places(mode: str, places: list[Place]) -> str:
    if not places:
        return "No matching places found."
    where = "on the route" if mode == "along_route" else "nearby"
    lines = [f"Found {len(places)} places {where}:"]
    for p in places[:5]:
        rating = f" {p.rating} stars" if p.rating is not None else ""
        lines.append(f"- {p.name}{rating} at {p.lat:.4f},{p.lng:.4f}")
    return "\n".join(lines)


async def execute_search_places(
    query: str, context: CopilotContext, places_provider: PlacesProvider
) -> str:
    mode, places = await search_with_fallback(
        places_provider, query, context.origin, context.selected_route_polyline
    )
    return _format_places(mode, places)
```

- [ ] **Step 4: Run the test to verify it passes**

```powershell
.\.venv\Scripts\python.exe -m pytest tests/test_copilot_tools.py -v
```
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add backend/app/copilot/tools.py backend/tests/test_copilot_tools.py
git commit -m "feat(backend): search_places tool schema + executor"
```

---

### Task 5: Agent loop + `AnthropicCopilot` (TDD with a fake client)

**Files:**
- Create: `backend/app/copilot/anthropic_agent.py`
- Test: `backend/tests/test_agent_loop.py`

- [ ] **Step 1: Write the failing test (fake Anthropic client, scripted tool_use → text)**

`backend/tests/test_agent_loop.py`:
```python
import asyncio
from dataclasses import dataclass

from app.copilot.anthropic_agent import run_agent_loop


@dataclass
class _ToolUse:
    id: str
    name: str
    input: dict
    type: str = "tool_use"


@dataclass
class _Text:
    text: str
    type: str = "text"


@dataclass
class _Resp:
    stop_reason: str
    content: list


class _FakeMessages:
    def __init__(self, scripted):
        self._scripted = list(scripted)
        self.calls = 0

    async def create(self, **kwargs):
        resp = self._scripted[self.calls]
        self.calls += 1
        return resp


class _FakeClient:
    def __init__(self, scripted):
        self.messages = _FakeMessages(scripted)


def test_loop_runs_tool_then_returns_text():
    scripted = [
        _Resp(stop_reason="tool_use",
              content=[_ToolUse(id="t1", name="search_places", input={"query": "gas"})]),
        _Resp(stop_reason="end_turn", content=[_Text(text="There's a Shell ahead.")]),
    ]
    client = _FakeClient(scripted)
    executed = {}

    async def fake_execute(name, tool_input):
        executed["name"] = name
        executed["query"] = tool_input["query"]
        return "Found 1 place on the route: Shell"

    reply = asyncio.run(run_agent_loop(
        client=client, model="m", system="s", tools=[], messages=[],
        execute_tool=fake_execute,
    ))

    assert executed == {"name": "search_places", "query": "gas"}
    assert reply == "There's a Shell ahead."
    assert client.messages.calls == 2


def test_loop_returns_text_without_tool():
    scripted = [_Resp(stop_reason="end_turn", content=[_Text(text="Take the expressway.")])]
    client = _FakeClient(scripted)

    async def fake_execute(name, tool_input):
        raise AssertionError("should not be called")

    reply = asyncio.run(run_agent_loop(
        client=client, model="m", system="s", tools=[], messages=[],
        execute_tool=fake_execute,
    ))

    assert reply == "Take the expressway."
```

- [ ] **Step 2: Run it to verify it fails**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"
.\.venv\Scripts\python.exe -m pytest tests/test_agent_loop.py -v
```
Expected: FAIL — `ModuleNotFoundError: app.copilot.anthropic_agent`.

- [ ] **Step 3: Write `anthropic_agent.py`**

```python
import anthropic

from app.copilot.base import CopilotError
from app.copilot.tools import SEARCH_PLACES_TOOL, execute_search_places
from app.models import ChatMessage, CopilotContext

_MODEL = "claude-haiku-4-5"
_MAX_TOKENS = 1024
_MAX_ITERATIONS = 5

_PERSONA = (
    "You are Copilot, a concise in-car navigation assistant. Answer in one or two "
    "short, spoken-style sentences — the driver will hear this aloud later. Use the "
    "current trip context for route and traffic questions. Use the search_places tool "
    "to find gas, food, or other places on the route or nearby. If you don't have the "
    "data, say so briefly."
)


def _format_context(context: CopilotContext) -> str:
    lines = [f"Current location: {context.origin.lat:.5f},{context.origin.lng:.5f}"]
    if context.routes:
        lines.append("Planned routes:")
        for r in context.routes:
            sel = " (selected)" if r.selected else ""
            mins = round(r.duration_seconds / 60)
            km = r.distance_meters / 1000
            lines.append(f"- {r.summary}{sel}: {mins} min, {km:.1f} km, traffic {r.traffic}")
    else:
        lines.append("No route is currently planned.")
    return "\n".join(lines)


def _system_blocks(context: CopilotContext) -> list[dict]:
    # Stable persona is the cached prefix; volatile context comes after the breakpoint.
    return [
        {"type": "text", "text": _PERSONA, "cache_control": {"type": "ephemeral"}},
        {"type": "text", "text": _format_context(context)},
    ]


async def run_agent_loop(client, model, system, tools, messages, execute_tool, max_iterations=_MAX_ITERATIONS):
    convo = list(messages)
    for _ in range(max_iterations):
        response = await client.messages.create(
            model=model,
            max_tokens=_MAX_TOKENS,
            system=system,
            tools=tools,
            messages=convo,
        )
        if response.stop_reason == "tool_use":
            convo.append({"role": "assistant", "content": response.content})
            tool_results = []
            for block in response.content:
                if getattr(block, "type", None) == "tool_use":
                    result = await execute_tool(block.name, block.input)
                    tool_results.append({
                        "type": "tool_result",
                        "tool_use_id": block.id,
                        "content": result,
                    })
            convo.append({"role": "user", "content": tool_results})
            continue
        return "".join(
            b.text for b in response.content if getattr(b, "type", None) == "text"
        ).strip()
    return "Sorry, I couldn't work that out just now."


class AnthropicCopilot:
    def __init__(self, api_key: str, places_provider, client=None):
        self._places_provider = places_provider
        self._client = client or anthropic.AsyncAnthropic(api_key=api_key)

    async def ask(self, messages: list[ChatMessage], context: CopilotContext) -> str:
        async def execute(name: str, tool_input: dict) -> str:
            if name == "search_places":
                return await execute_search_places(
                    tool_input.get("query", ""), context, self._places_provider
                )
            return "Unknown tool."

        convo = [{"role": m.role, "content": m.content} for m in messages]
        try:
            return await run_agent_loop(
                client=self._client,
                model=_MODEL,
                system=_system_blocks(context),
                tools=[SEARCH_PLACES_TOOL],
                messages=convo,
                execute_tool=execute,
            )
        except anthropic.AnthropicError as exc:
            raise CopilotError(str(exc)) from exc
```

- [ ] **Step 4: Run the test to verify it passes**

```powershell
.\.venv\Scripts\python.exe -m pytest tests/test_agent_loop.py -v
```
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add backend/app/copilot/anthropic_agent.py backend/tests/test_agent_loop.py
git commit -m "feat(backend): Anthropic agent loop + AnthropicCopilot (fake-client tested)"
```

---

### Task 6: Config factory + `/copilot/ask` endpoint (TDD)

**Files:**
- Modify: `backend/app/config.py`, `backend/app/main.py`
- Create: `backend/app/routers/copilot.py`
- Test: `backend/tests/test_copilot.py` (endpoint portion)

- [ ] **Step 1: Add the failing endpoint test**

Append to `backend/tests/test_copilot.py`:
```python
from fastapi.testclient import TestClient

from app.config import get_copilot
from app.main import app


class _FixedCopilot:
    async def ask(self, messages, context):
        return "fixed reply about " + str(len(context.routes)) + " routes"


def _client() -> TestClient:
    app.dependency_overrides[get_copilot] = lambda: _FixedCopilot()
    return TestClient(app)


def teardown_function():
    app.dependency_overrides.pop(get_copilot, None)


def test_copilot_ask_returns_reply():
    client = _client()
    body = {
        "messages": [{"role": "user", "content": "hi"}],
        "context": {"origin": {"lat": 1.0, "lng": 2.0},
                    "routes": [{"summary": "A", "distance_meters": 100,
                                "duration_seconds": 60, "traffic": "light", "selected": True}]},
    }
    resp = client.post("/copilot/ask", json=body)
    assert resp.status_code == 200
    assert resp.json()["reply"] == "fixed reply about 1 routes"


def test_copilot_ask_malformed_422():
    client = _client()
    resp = client.post("/copilot/ask", json={"messages": []})
    assert resp.status_code == 422
```

- [ ] **Step 2: Run it to verify it fails**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"
.\.venv\Scripts\python.exe -m pytest tests/test_copilot.py -v
```
Expected: FAIL — `get_copilot` import error / 404.

- [ ] **Step 3: Add config factory**

In `backend/app/config.py`, add to `Settings`:
```python
    copilot_provider: str = "stub"
    anthropic_api_key: str = ""
```
And append this function at the end of the file:
```python
def get_copilot():
    settings = get_settings()
    if settings.copilot_provider == "anthropic":
        from app.copilot.anthropic_agent import AnthropicCopilot

        return AnthropicCopilot(
            api_key=settings.anthropic_api_key,
            places_provider=get_places_provider(),
        )
    from app.copilot.stub import StubCopilot

    return StubCopilot()
```

- [ ] **Step 4: Write `routers/copilot.py`**

```python
from fastapi import APIRouter, Depends, HTTPException

from app.config import get_copilot
from app.copilot.base import CopilotError
from app.models import CopilotAskRequest, CopilotAskResponse

router = APIRouter()


@router.post("/copilot/ask", response_model=CopilotAskResponse)
async def copilot_ask(
    request: CopilotAskRequest,
    copilot=Depends(get_copilot),
) -> CopilotAskResponse:
    try:
        reply = await copilot.ask(request.messages, request.context)
    except CopilotError as exc:
        raise HTTPException(status_code=502, detail=f"Copilot error: {exc}") from exc
    return CopilotAskResponse(reply=reply)
```

- [ ] **Step 5: Register the router in `main.py`**

In `backend/app/main.py`, update the import and registration:
```python
from app.routers import copilot, places, routes
```
and after the existing `include_router` lines add:
```python
app.include_router(copilot.router)
```

- [ ] **Step 6: Run the full backend suite**

```powershell
.\.venv\Scripts\python.exe -m pytest -q
```
Expected: all pass.

- [ ] **Step 7: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add backend/app/config.py backend/app/routers/copilot.py backend/app/main.py backend/tests/test_copilot.py
git commit -m "feat(backend): POST /copilot/ask + copilot config factory"
```

---

# PHASE 2 — App

### Task 7: Network layer — copilot DTOs + API

**Files:**
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/network/CopilotDtos.kt`, `CopilotApi.kt`
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/network/NetworkModule.kt`

- [ ] **Step 1: Write `CopilotDtos.kt`**

```kotlin
package com.virtueson.copilotmaps.network

import com.squareup.moshi.Json

data class ChatMessageDto(
    val role: String,
    val content: String,
)

data class RouteSummaryDto(
    val summary: String,
    @Json(name = "distance_meters") val distanceMeters: Int,
    @Json(name = "duration_seconds") val durationSeconds: Int,
    val traffic: String,
    val selected: Boolean,
)

data class CopilotContextDto(
    val origin: LatLngDto,
    @Json(name = "selected_route_polyline") val selectedRoutePolyline: String? = null,
    val routes: List<RouteSummaryDto> = emptyList(),
)

data class CopilotAskRequestDto(
    val messages: List<ChatMessageDto>,
    val context: CopilotContextDto,
)

data class CopilotAskResponseDto(
    val reply: String,
)
```

- [ ] **Step 2: Write `CopilotApi.kt`**

```kotlin
package com.virtueson.copilotmaps.network

import retrofit2.http.Body
import retrofit2.http.POST

interface CopilotApi {
    @POST("copilot/ask")
    suspend fun ask(@Body body: CopilotAskRequestDto): CopilotAskResponseDto
}
```

- [ ] **Step 3: Expose `copilotApi` in `NetworkModule.kt`**

Add to the bottom of the `NetworkModule` object (next to `routesApi`/`placesApi`):
```kotlin
    val copilotApi: CopilotApi by lazy { retrofit.create(CopilotApi::class.java) }
```

- [ ] **Step 4: Compile**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add android/app/src/main/java/com/virtueson/copilotmaps/network/CopilotDtos.kt android/app/src/main/java/com/virtueson/copilotmaps/network/CopilotApi.kt android/app/src/main/java/com/virtueson/copilotmaps/network/NetworkModule.kt
git commit -m "feat(app): copilot DTOs + CopilotApi"
```

---

### Task 8: Domain + repository

**Files:**
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/data/ChatMessage.kt`, `CopilotRepository.kt`

- [ ] **Step 1: Write `ChatMessage.kt`**

```kotlin
package com.virtueson.copilotmaps.data

enum class Role { USER, ASSISTANT }

data class ChatMessage(
    val role: Role,
    val content: String,
)

data class RouteSummary(
    val summary: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val traffic: String,
    val selected: Boolean,
)

data class TripContext(
    val origin: GeoPoint,
    val selectedRoutePolyline: String?,
    val routes: List<RouteSummary>,
)

sealed interface CopilotResult {
    data class Success(val reply: String) : CopilotResult
    data class Failure(val reason: String) : CopilotResult
}
```

- [ ] **Step 2: Write `CopilotRepository.kt`**

```kotlin
package com.virtueson.copilotmaps.data

import com.virtueson.copilotmaps.network.ChatMessageDto
import com.virtueson.copilotmaps.network.CopilotApi
import com.virtueson.copilotmaps.network.CopilotAskRequestDto
import com.virtueson.copilotmaps.network.CopilotContextDto
import com.virtueson.copilotmaps.network.LatLngDto
import com.virtueson.copilotmaps.network.RouteSummaryDto

interface CopilotRepository {
    suspend fun ask(messages: List<ChatMessage>, context: TripContext): CopilotResult
}

class DefaultCopilotRepository(
    private val api: CopilotApi,
) : CopilotRepository {
    override suspend fun ask(messages: List<ChatMessage>, context: TripContext): CopilotResult {
        return try {
            val response = api.ask(
                CopilotAskRequestDto(
                    messages = messages.map { ChatMessageDto(it.role.name.lowercase(), it.content) },
                    context = CopilotContextDto(
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
                )
            )
            CopilotResult.Success(response.reply)
        } catch (e: Exception) {
            CopilotResult.Failure("Can't reach the server — is the backend running and adb reverse set?")
        }
    }
}
```

- [ ] **Step 3: Compile**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add android/app/src/main/java/com/virtueson/copilotmaps/data/ChatMessage.kt android/app/src/main/java/com/virtueson/copilotmaps/data/CopilotRepository.kt
git commit -m "feat(app): copilot domain types + CopilotRepository"
```

---

### Task 9: `CopilotState` + `CopilotViewModel` (TDD)

**Files:**
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/ui/copilot/CopilotState.kt`, `CopilotViewModel.kt`
- Test: `android/app/src/test/java/com/virtueson/copilotmaps/ui/copilot/CopilotViewModelTest.kt`

- [ ] **Step 1: Write `CopilotState.kt`**

```kotlin
package com.virtueson.copilotmaps.ui.copilot

import com.virtueson.copilotmaps.data.ChatMessage

data class CopilotUiState(
    val messages: List<ChatMessage> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
)
```

- [ ] **Step 2: Write the failing test**

`android/app/src/test/java/com/virtueson/copilotmaps/ui/copilot/CopilotViewModelTest.kt`:
```kotlin
package com.virtueson.copilotmaps.ui.copilot

import com.virtueson.copilotmaps.MainDispatcherRule
import com.virtueson.copilotmaps.data.ChatMessage
import com.virtueson.copilotmaps.data.CopilotRepository
import com.virtueson.copilotmaps.data.CopilotResult
import com.virtueson.copilotmaps.data.GeoPoint
import com.virtueson.copilotmaps.data.Role
import com.virtueson.copilotmaps.data.TripContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

private class FakeCopilotRepository(private val result: CopilotResult) : CopilotRepository {
    override suspend fun ask(messages: List<ChatMessage>, context: TripContext): CopilotResult = result
}

private val ctx = TripContext(GeoPoint(0.0, 0.0), null, emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
class CopilotViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `success appends user then assistant message`() = runTest {
        val vm = CopilotViewModel(FakeCopilotRepository(CopilotResult.Success("Hi there")))

        vm.sendMessage("hello", ctx)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(2, state.messages.size)
        assertEquals(Role.USER, state.messages[0].role)
        assertEquals("hello", state.messages[0].content)
        assertEquals(Role.ASSISTANT, state.messages[1].role)
        assertEquals("Hi there", state.messages[1].content)
        assertFalse(state.sending)
        assertNull(state.error)
    }

    @Test
    fun `failure sets error and keeps only the user message`() = runTest {
        val vm = CopilotViewModel(FakeCopilotRepository(CopilotResult.Failure("boom")))

        vm.sendMessage("hello", ctx)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(1, state.messages.size)
        assertEquals(Role.USER, state.messages[0].role)
        assertEquals("boom", state.error)
        assertFalse(state.sending)
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat :app:testDebugUnitTest --tests "com.virtueson.copilotmaps.ui.copilot.CopilotViewModelTest"
```
Expected: FAIL — `CopilotViewModel` unresolved.

- [ ] **Step 4: Write `CopilotViewModel.kt`**

```kotlin
package com.virtueson.copilotmaps.ui.copilot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.virtueson.copilotmaps.data.ChatMessage
import com.virtueson.copilotmaps.data.CopilotRepository
import com.virtueson.copilotmaps.data.CopilotResult
import com.virtueson.copilotmaps.data.Role
import com.virtueson.copilotmaps.data.TripContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val MAX_TURNS = 12

class CopilotViewModel(
    private val repository: CopilotRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CopilotUiState())
    val state: StateFlow<CopilotUiState> = _state.asStateFlow()

    fun sendMessage(text: String, context: TripContext) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.sending) return

        val withUser = _state.value.messages + ChatMessage(Role.USER, trimmed)
        _state.value = _state.value.copy(messages = withUser, sending = true, error = null)

        viewModelScope.launch {
            val history = withUser.takeLast(MAX_TURNS)
            when (val result = repository.ask(history, context)) {
                is CopilotResult.Success ->
                    _state.value = _state.value.copy(
                        messages = _state.value.messages + ChatMessage(Role.ASSISTANT, result.reply),
                        sending = false,
                    )
                is CopilotResult.Failure ->
                    _state.value = _state.value.copy(sending = false, error = result.reason)
            }
        }
    }
}

class CopilotViewModelFactory(
    private val repository: CopilotRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CopilotViewModel(repository) as T
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.virtueson.copilotmaps.ui.copilot.CopilotViewModelTest"
```
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add android/app/src/main/java/com/virtueson/copilotmaps/ui/copilot/CopilotState.kt android/app/src/main/java/com/virtueson/copilotmaps/ui/copilot/CopilotViewModel.kt android/app/src/test/java/com/virtueson/copilotmaps/ui/copilot/CopilotViewModelTest.kt
git commit -m "feat(app): CopilotState + CopilotViewModel with unit tests"
```

---

### Task 10: Chat sheet UI + MapScreen wiring + on-device verify

**Files:**
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/ui/copilot/CopilotChatSheet.kt`
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt`

- [ ] **Step 1: Write `CopilotChatSheet.kt`**

```kotlin
package com.virtueson.copilotmaps.ui.copilot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.virtueson.copilotmaps.data.ChatMessage
import com.virtueson.copilotmaps.data.Role

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopilotChatSheet(
    state: CopilotUiState,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Copilot", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 360.dp)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.messages.isEmpty()) {
                    item {
                        Text(
                            "Ask me about your trip — \"which route is faster?\", " +
                                "\"any gas on my route?\"",
                            color = Color(0xFF5F6368),
                        )
                    }
                }
                items(state.messages) { message -> MessageBubble(message) }
            }

            state.error?.let {
                Text(it, color = Color(0xFFEA4335), modifier = Modifier.padding(bottom = 8.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask the copilot…") },
                    singleLine = true,
                )
                if (state.sending) {
                    CircularProgressIndicator(modifier = Modifier.padding(start = 12.dp))
                } else {
                    Button(
                        onClick = {
                            val text = draft.trim()
                            if (text.isNotEmpty()) {
                                onSend(text)
                                draft = ""
                            }
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("Send") }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val fromUser = message.role == Role.USER
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = if (fromUser) Color(0xFFD2E3FC) else Color(0xFFF1F3F4),
            modifier = Modifier
                .align(if (fromUser) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(start = if (fromUser) 48.dp else 0.dp, end = if (fromUser) 0.dp else 48.dp),
        ) {
            Text(message.content, modifier = Modifier.padding(10.dp))
        }
    }
}
```

- [ ] **Step 2: Wire the chat into `MapScreen.kt` — add imports**

Add to the imports in `MapScreen.kt`:
```kotlin
import androidx.compose.material3.ExtendedFloatingActionButton
import com.virtueson.copilotmaps.data.ChatMessage
import com.virtueson.copilotmaps.data.CopilotRepository
import com.virtueson.copilotmaps.data.DefaultCopilotRepository
import com.virtueson.copilotmaps.data.Route
import com.virtueson.copilotmaps.data.RouteSummary
import com.virtueson.copilotmaps.data.TrafficInterval
import com.virtueson.copilotmaps.data.TripContext
import com.virtueson.copilotmaps.ui.copilot.CopilotChatSheet
import com.virtueson.copilotmaps.ui.copilot.CopilotViewModel
import com.virtueson.copilotmaps.ui.copilot.CopilotViewModelFactory
```

- [ ] **Step 3: Add the copilot ViewModel + state in `MapScreen` (the `@Composable fun MapScreen`)**

After the `placesViewModel` / `placesState` lines in `MapScreen`, add:
```kotlin
    val copilotViewModel: CopilotViewModel = viewModel(
        factory = CopilotViewModelFactory(DefaultCopilotRepository(NetworkModule.copilotApi))
    )
    val copilotState by copilotViewModel.state.collectAsStateWithLifecycle()
```

- [ ] **Step 4: Pass copilot wiring into `RoutingMap` (the `MapUiState.Located` branch)**

Replace the `RoutingMap(...)` call in the `is MapUiState.Located` branch with:
```kotlin
        is MapUiState.Located -> {
            val origin = GeoPoint(loc.latitude, loc.longitude)
            RoutingMap(
                modifier = modifier,
                origin = origin,
                routesState = routesState,
                placesState = placesState,
                copilotState = copilotState,
                onPlan = { dest -> routeViewModel.planRoutes(origin, dest) },
                onSelect = routeViewModel::selectRoute,
                onSearchPlaces = { category ->
                    val polyline = (routesState as? RoutesState.Loaded)?.let { loaded ->
                        loaded.routes.firstOrNull { it.id == loaded.selectedId }?.polyline
                    }
                    placesViewModel.search(category, origin, polyline)
                },
                onClearPlaces = placesViewModel::clear,
                onSendCopilot = { text ->
                    copilotViewModel.sendMessage(text, buildTripContext(origin, routesState))
                },
            )
        }
```

- [ ] **Step 5: Update the `RoutingMap` signature + add the chat FAB & sheet**

In `RoutingMap`, add the two new params to the signature:
```kotlin
@Composable
private fun RoutingMap(
    modifier: Modifier,
    origin: GeoPoint,
    routesState: RoutesState,
    placesState: PlacesState,
    copilotState: com.virtueson.copilotmaps.ui.copilot.CopilotUiState,
    onPlan: (GeoPoint) -> Unit,
    onSelect: (String) -> Unit,
    onSearchPlaces: (PlaceCategory) -> Unit,
    onClearPlaces: () -> Unit,
    onSendCopilot: (String) -> Unit,
) {
```
Then, inside `RoutingMap`'s root `Box { ... }`, just before its closing brace (after the bottom `when (routesState)` block), add the FAB + sheet:
```kotlin
        var showChat by remember { mutableStateOf(false) }
        ExtendedFloatingActionButton(
            onClick = { showChat = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) { Text("Copilot") }

        if (showChat) {
            CopilotChatSheet(
                state = copilotState,
                onSend = onSendCopilot,
                onDismiss = { showChat = false },
            )
        }
```

- [ ] **Step 6: Add the `buildTripContext` + `trafficLabel` helpers at the bottom of `MapScreen.kt`**

```kotlin
private fun buildTripContext(origin: GeoPoint, routesState: RoutesState): TripContext {
    val loaded = routesState as? RoutesState.Loaded
    val routes: List<Route> = loaded?.routes ?: emptyList()
    val selectedId = loaded?.selectedId
    val selectedPolyline = routes.firstOrNull { it.id == selectedId }?.polyline
    val summaries = routes.map { r ->
        RouteSummary(
            summary = r.summary,
            distanceMeters = r.distanceMeters,
            durationSeconds = r.durationSeconds,
            traffic = trafficLabel(r.trafficIntervals),
            selected = r.id == selectedId,
        )
    }
    return TripContext(origin, selectedPolyline, summaries)
}

private fun trafficLabel(intervals: List<TrafficInterval>): String = when {
    intervals.any { it.speed == TrafficSpeed.JAM } -> "heavy"
    intervals.any { it.speed == TrafficSpeed.SLOW } -> "moderate"
    else -> "light"
}
```

- [ ] **Step 7: Compile**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL. (If `ModalBottomSheet`/`ExtendedFloatingActionButton` need an `@OptIn(ExperimentalMaterial3Api::class)`, add it — the sheet file already opts in.)

- [ ] **Step 8: On-device verification (stub copilot)**

Start the backend with the **stub** copilot + the tunnel:
```powershell
# terminal A
cd "D:\Russell\Data Science Job\AI Maps\backend"; $env:COPILOT_PROVIDER="stub"; .\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8000
# terminal B
& "C:\Users\Russell\AppData\Local\Android\Sdk\platform-tools\adb.exe" reverse tcp:8000 tcp:8000
```
Run the app (▶). Plan a route, tap the **Copilot** FAB, send "which route is faster?" → a deterministic `(stub)` reply that references your routes appears. Confirms the full chat round-trip + context.

- [ ] **Step 9: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add android/app/src/main/java/com/virtueson/copilotmaps/ui/copilot/CopilotChatSheet.kt android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt
git commit -m "feat(app): copilot chat sheet + map wiring (stub-verified)"
```

---

# PHASE 3 — Real Claude

### Task 11: Anthropic key + `.env` + on-device real-copilot verify

**Files:**
- Modify: `backend/.env` (git-ignored)

- [ ] **Step 1: Create an Anthropic API key**

Go to https://console.anthropic.com → **API Keys** → create a key → copy it. (Add a few dollars of credit if the account is new.)

- [ ] **Step 2: Add it to `backend/.env`**

Append to `backend/.env`:
```
COPILOT_PROVIDER=anthropic
ANTHROPIC_API_KEY=sk-ant-...your key...
```

- [ ] **Step 3: Restart the backend with the real copilot**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"
.\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8000
```
(Ensure `adb reverse tcp:8000 tcp:8000` is still set.)

- [ ] **Step 4: On-device verification (real Claude)**

In the app, plan a route, open **Copilot**, and try:
- "which route is faster and why?" → reasons over the route ETAs/traffic from context.
- "any gas station on my route?" → the agent calls `search_places` and answers with real places.
- a follow-up like "what about food?" → multi-turn memory works within the session.

- [ ] **Step 5: Commit (no secret — .env is git-ignored)**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git status   # confirm backend/.env is NOT listed
git commit --allow-empty -m "chore: Step 4a verified end-to-end with real Claude copilot"
```

---

## Definition of Done (verify all)

- [ ] `POST /copilot/ask` returns replies (stub, then real Claude), running `search_places` when needed.
- [ ] Chat sheet: route question answered from context; "gas on my route?" triggers the tool; multi-turn within session.
- [ ] Conversation persists while app is open; resets on close.
- [ ] Backend `pytest` (incl. fake-client agent-loop test) + app `CopilotViewModelTest` pass.
- [ ] `git status` clean; `backend/.env` / `local.properties` never committed.

---

## Notes for later

- **Step 4b (voice):** wrap this with Android `SpeechRecognizer` (mic → text → `sendMessage`) and `TextToSpeech` (reply → speech).
- **Profile memory:** a small stored preferences profile injected as extra context.
- **Landmark-at-turn:** turn-by-turn steps (Step 5) + a Google Address Descriptors tool.
- **Prompt caching** only engages once the cached prefix exceeds ~1024 tokens; the breakpoint is already placed for when prompts grow.
```
