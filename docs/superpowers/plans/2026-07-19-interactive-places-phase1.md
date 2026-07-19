# Interactive Places — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the copilot's `search_places` results appear on the map as pins (like the Gas/Food buttons), make place markers tappable with a Directions button, and clean up the top UI (remove Gas/Food/Clear; move Clear to the right above recenter).

**Architecture:** The backend threads a per-turn `TurnOutputs` collector through the tool registry so `search_places` can surface its structured `Place` list; that list rides back on the `/copilot/ask` response. The Android app feeds those places into the existing `PlacesViewModel` (single source of truth for pins), so copilot pins and button pins are the same state and the same markers. Markers gain a tap → detail card → Directions flow that reuses the existing "plan route then Start" path.

**Tech Stack:** Python 3 / FastAPI / Pydantic v2 (backend); Kotlin / Jetpack Compose / Retrofit + Moshi / Google Maps Compose (Android). Backend tests: pytest against stubs. Android unit tests: plain-JVM JUnit.

**Spec:** `docs/superpowers/specs/2026-07-19-interactive-places-copilot-design.md` (this is Phase 1 of that spec; Phase 2 — voice-nav + constraint — is a separate later plan).

## Global Constraints

- Backend tests force stubs via `dependency_overrides` and must never hit live Google/LLM: `cd backend && ./.venv/Scripts/python.exe -m pytest -q`.
- Android unit tests are plain-JVM JUnit — no `Context`/framework types in unit-tested paths. Compose UI is verified by `compileDebugKotlin` + on-device, not unit tests. Set `JAVA_HOME` first (see below).
- The `/copilot/ask` response keeps its existing `{reply, language}` fields; new fields are additive with safe defaults.
- `AskResult` and `CopilotAskResponse` gain `places: list[Place] = []` and `navigation: Place | None = None`. Phase 1 only ever populates `places`; `navigation` stays defaulted (Phase 2 fills it).
- Reuse the existing `PlaceDto` and the `Place` domain model; do NOT define parallel place types.
- Never stage `.env`, `local.properties`, or scratch files; stage only the exact files each commit lists. Never `git add -A`/`.`.

**Android build/test commands (PowerShell — `java` is not on PATH):**
```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat testDebugUnitTest --console=plain      # unit tests
.\gradlew.bat compileDebugKotlin --console=plain     # compile check
```

---

### Task 1: Backend — thread `TurnOutputs` so `search_places` surfaces its places

**Files:**
- Modify: `backend/app/copilot/base.py` (AskResult fields)
- Modify: `backend/app/copilot/registry.py` (TurnOutputs, executor signature, dispatch)
- Modify: `backend/app/copilot/tools.py` (executors accept `outputs`; search writes places)
- Modify: `backend/app/copilot/openai_compat.py` (create outputs, merge into result)
- Modify: `backend/app/copilot/anthropic_agent.py` (same)
- Test: `backend/tests/test_turn_outputs.py` (new)

**Interfaces:**
- Consumes: `app.models.Place`, `search_with_fallback`.
- Produces:
  - `registry.TurnOutputs` dataclass: `places: list[Place]` (default `[]`), `navigation: Place | None` (default `None`).
  - `registry.ToolExecutor = Callable[[dict, CopilotContext, TurnOutputs], Awaitable[str]]`.
  - `dispatch(tools, name, args, context, outputs) -> str`.
  - `base.AskResult(reply, tools_used=[], loop_count=0, places=[], navigation=None)`.
  - `tools.execute_search_places(query, context, places_provider, outputs=None) -> str` (now writes `outputs.places` when `outputs` given).

- [ ] **Step 1: Write the failing test**

Create `backend/tests/test_turn_outputs.py`:

```python
import asyncio

import polyline

from app.copilot.registry import Tool, TurnOutputs, dispatch
from app.copilot.tools import build_tools
from app.models import CopilotContext, LatLng, Place

# A real, decodable west->east line; origin at its start makes the on-route hit
# survive the ahead-on-route filter.
_ROUTE = polyline.encode([(0.0, 0.0), (0.0, 0.1)])


class _FakeProvider:
    def __init__(self, along, near):
        self._along, self._near = along, near

    async def along_route(self, query, polyline):
        return self._along

    async def nearby(self, query, origin):
        return self._near


def _place(name):
    return Place(id=name, name=name, lat=0.0, lng=0.05, rating=4.5)


def test_dispatch_passes_outputs_to_executor():
    hits = []

    async def _exec(args, context, outputs):
        outputs.places = [_place("X")]
        return "ok"

    tool = Tool(name="t", description="", parameters={}, executor=_exec)
    ctx = CopilotContext(origin=LatLng(lat=0.0, lng=0.0))
    outputs = TurnOutputs()

    result = asyncio.run(dispatch([tool], "t", {}, ctx, outputs))

    assert result == "ok"
    assert [p.name for p in outputs.places] == ["X"]


def test_search_places_tool_writes_places_to_outputs():
    provider = _FakeProvider(along=[_place("OnRoute")], near=[])
    tools = build_tools(provider)
    search = next(t for t in tools if t.name == "search_places")
    # origin at the route start so the on-route hit survives the ahead-filter
    ctx = CopilotContext(
        origin=LatLng(lat=0.0, lng=0.0),
        selected_route_polyline=_ROUTE,
    )
    outputs = TurnOutputs()

    text = asyncio.run(search.executor({"query": "gas station"}, ctx, outputs))

    assert "OnRoute" in text
    assert [p.name for p in outputs.places] == ["OnRoute"]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./.venv/Scripts/python.exe -m pytest tests/test_turn_outputs.py -q`
Expected: FAIL — `ImportError: cannot import name 'TurnOutputs'` (and the executor signature is wrong).

- [ ] **Step 3: Add `TurnOutputs`, update the executor type + `dispatch`**

Edit `backend/app/copilot/registry.py`. Replace the imports/type-alias/dispatch region so the file reads:

```python
"""Provider-agnostic tool registry.

A `Tool` is declared once (name + JSON-schema parameters + an async executor with
its dependencies already bound in). Each LLM provider renders the same registry into
its own wire format and dispatches calls by name. To add a capability, append one
`Tool` in `build_tools()` — no provider or agent-loop code needs to change.
"""

from dataclasses import dataclass, field
from typing import Awaitable, Callable

from app.models import CopilotContext, Place


@dataclass
class TurnOutputs:
    """Structured side-outputs a tool can emit during one /copilot/ask turn.

    Executors return text for the LLM to read; anything the *app* needs (map
    pins, a resolved navigation target) is written here and copied into the
    AskResult after the agent loop.
    """

    places: list[Place] = field(default_factory=list)
    navigation: Place | None = None


# An executor receives parsed args + the trip context + the turn's outputs sink.
ToolExecutor = Callable[[dict, CopilotContext, TurnOutputs], Awaitable[str]]


@dataclass(frozen=True)
class Tool:
    name: str
    description: str
    parameters: dict  # JSON Schema for the arguments object
    executor: ToolExecutor
```

Leave `to_openai_tools` and `to_anthropic_tools` exactly as they are. Then replace `dispatch`:

```python
async def dispatch(
    tools: list[Tool],
    name: str,
    args: dict,
    context: CopilotContext,
    outputs: TurnOutputs,
) -> str:
    """Run the executor for `name`, or report an unknown tool."""
    for tool in tools:
        if tool.name == name:
            return await tool.executor(args, context, outputs)
    return f"Unknown tool: {name}"
```

- [ ] **Step 4: Update the tool executors to accept `outputs`**

Edit `backend/app/copilot/tools.py`. Change `execute_search_places` and the `build_tools` closure:

```python
async def execute_search_places(
    query: str,
    context: CopilotContext,
    places_provider: PlacesProvider,
    outputs=None,
) -> str:
    mode, places = await search_with_fallback(
        places_provider, query, context.origin, context.selected_route_polyline
    )
    if outputs is not None:
        outputs.places = list(places)
    return _format_places(mode, places)


def build_tools(places_provider: PlacesProvider) -> list[Tool]:
    """Single registration point for every copilot tool.

    To add a capability, write its executor and append one `Tool(...)` here; all
    providers (OpenAI-compatible and Anthropic-native) pick it up automatically.
    """

    async def _search_places(args: dict, context: CopilotContext, outputs) -> str:
        return await execute_search_places(
            args.get("query", ""), context, places_provider, outputs
        )

    return [
        Tool(
            name="search_places",
            description=_SEARCH_PLACES_DESCRIPTION,
            parameters=_SEARCH_PLACES_PARAMETERS,
            executor=_search_places,
        ),
    ]
```

- [ ] **Step 5: Add `places`/`navigation` to `AskResult`**

Edit `backend/app/copilot/base.py`:

```python
from dataclasses import dataclass, field
from typing import Protocol

from app.models import ChatMessage, CopilotContext, Place


class CopilotError(Exception):
    """Raised when the copilot provider fails (mapped to HTTP 502)."""


@dataclass(frozen=True)
class AskResult:
    reply: str
    tools_used: list[str] = field(default_factory=list)
    loop_count: int = 0
    places: list[Place] = field(default_factory=list)
    navigation: Place | None = None


class CopilotProvider(Protocol):
    async def ask(self, messages: list[ChatMessage], context: CopilotContext) -> AskResult: ...
```

- [ ] **Step 6: Thread `outputs` through both providers**

Edit `backend/app/copilot/openai_compat.py`. Add `from dataclasses import replace` to the imports, and change the `ask` method to create outputs, pass them into `dispatch`, and merge them into the returned result:

```python
    async def ask(self, messages: list[ChatMessage], context: CopilotContext) -> AskResult:
        outputs = TurnOutputs()

        async def execute(name: str, args: dict) -> str:
            return await dispatch(self._tools, name, args, context, outputs)

        convo = [_system_message(context)] + [
            {"role": m.role, "content": m.content} for m in messages
        ]
        try:
            result = await run_openai_agent_loop(
                client=self._client,
                model=self._model,
                messages=convo,
                tools=to_openai_tools(self._tools),
                execute_tool=execute,
            )
        except openai.OpenAIError as exc:
            raise CopilotError(str(exc)) from exc
        return replace(result, places=outputs.places, navigation=outputs.navigation)
```

Update the import line `from app.copilot.registry import dispatch, to_openai_tools` to `from app.copilot.registry import TurnOutputs, dispatch, to_openai_tools`.

Edit `backend/app/copilot/anthropic_agent.py` the same way: add `from dataclasses import replace`, import `TurnOutputs`, and change `ask`:

```python
    async def ask(self, messages: list[ChatMessage], context: CopilotContext) -> AskResult:
        outputs = TurnOutputs()

        async def execute(name: str, tool_input: dict) -> str:
            return await dispatch(self._tools, name, tool_input, context, outputs)

        convo = [{"role": m.role, "content": m.content} for m in messages]
        try:
            result = await run_agent_loop(
                client=self._client,
                model=_MODEL,
                system=_system_blocks(context),
                tools=to_anthropic_tools(self._tools),
                messages=convo,
                execute_tool=execute,
            )
        except anthropic.AnthropicError as exc:
            raise CopilotError(str(exc)) from exc
        return replace(result, places=outputs.places, navigation=outputs.navigation)
```

Change its import `from app.copilot.registry import dispatch, to_anthropic_tools` to `from app.copilot.registry import TurnOutputs, dispatch, to_anthropic_tools`.

- [ ] **Step 7: Run the new test + the full backend suite**

Run: `cd backend && ./.venv/Scripts/python.exe -m pytest tests/test_turn_outputs.py -q`
Expected: PASS — 2 passed.

Run: `cd backend && ./.venv/Scripts/python.exe -m pytest -q`
Expected: PASS — all green. (Existing `test_copilot_tools.py` calls `execute_search_places` without `outputs`, which still works via the default; the openai/anthropic loop tests still pass because the loop signature is unchanged.)

- [ ] **Step 8: Commit**

```bash
git add backend/app/copilot/base.py backend/app/copilot/registry.py backend/app/copilot/tools.py backend/app/copilot/openai_compat.py backend/app/copilot/anthropic_agent.py backend/tests/test_turn_outputs.py
git commit -m "feat(copilot): thread TurnOutputs so search_places surfaces its places"
```

---

### Task 2: Backend — carry `places` on the `/copilot/ask` response

**Files:**
- Modify: `backend/app/models.py` (CopilotAskResponse fields)
- Modify: `backend/app/routers/copilot.py` (copy places/navigation from result)
- Test: `backend/tests/test_copilot.py` (add one test)

**Interfaces:**
- Consumes: `AskResult.places`, `AskResult.navigation` (Task 1).
- Produces: `CopilotAskResponse(reply, language="en", places=[], navigation=None)` serialized on `/copilot/ask`.

- [ ] **Step 1: Write the failing test**

Add to `backend/tests/test_copilot.py` (append; it already overrides `get_copilot` with a fake — match the file's existing fake/override style). First read the top of the file to reuse its fake, then add:

```python
def test_response_includes_search_places():
    """Places from the copilot result must reach the HTTP response for map pins."""
    from app.copilot.base import AskResult
    from app.models import Place

    place = Place(id="p1", name="Shell Tendean", lat=-6.24, lng=106.82, rating=4.3)

    class _PlacesCopilot:
        async def ask(self, messages, context):
            return AskResult(reply="There are gas stations ahead.", places=[place])

    app.dependency_overrides[get_copilot] = lambda: _PlacesCopilot()
    try:
        client = TestClient(app)
        body = {
            "messages": [{"role": "user", "content": "any gas"}],
            "context": {"origin": {"lat": -6.24, "lng": 106.82}},
        }
        data = client.post("/copilot/ask", json=body).json()
    finally:
        app.dependency_overrides.pop(get_copilot, None)

    assert data["reply"] == "There are gas stations ahead."
    assert [p["name"] for p in data["places"]] == ["Shell Tendean"]
    assert data["navigation"] is None
```

If `app`, `get_copilot`, and `TestClient` are not already imported at the top of `test_copilot.py`, add the imports the existing tests use (they will already be present — reuse them; do not add duplicates).

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./.venv/Scripts/python.exe -m pytest tests/test_copilot.py::test_response_includes_search_places -q`
Expected: FAIL — `KeyError: 'places'` (response has no `places` field yet).

- [ ] **Step 3: Add fields to the response model**

Edit `backend/app/models.py`. Replace `CopilotAskResponse`:

```python
class CopilotAskResponse(BaseModel):
    reply: str
    language: str = "en"
    places: list[Place] = []
    navigation: Place | None = None
```

(`Place` is already defined above in this file.)

- [ ] **Step 4: Copy places/navigation in the router**

Edit `backend/app/routers/copilot.py`. Change the final return:

```python
    return CopilotAskResponse(
        reply=result.reply,
        language=language,
        places=result.places,
        navigation=result.navigation,
    )
```

- [ ] **Step 5: Run the test + full suite**

Run: `cd backend && ./.venv/Scripts/python.exe -m pytest tests/test_copilot.py -q`
Expected: PASS.

Run: `cd backend && ./.venv/Scripts/python.exe -m pytest -q`
Expected: PASS — all green.

- [ ] **Step 6: Commit**

```bash
git add backend/app/models.py backend/app/routers/copilot.py backend/tests/test_copilot.py
git commit -m "feat(copilot): return search places on the /copilot/ask response"
```

---

### Task 3: Android — map copilot response places into the domain result

**Files:**
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/network/CopilotDtos.kt` (response DTO)
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/data/ChatMessage.kt` (CopilotResult.Success)
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/data/PlacesRepository.kt` (extract `PlaceDto.toPlace()`)
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/data/CopilotRepository.kt` (map places)
- Test: `android/app/src/test/java/com/virtueson/copilotmaps/data/CopilotRepositoryTest.kt` (new)

**Interfaces:**
- Consumes: response `places` field (Task 2), existing `PlaceDto`, `Place`, `GeoPoint`.
- Produces:
  - `PlaceDto.toPlace(): Place` extension (in `PlacesRepository.kt`), reused by both repositories.
  - `CopilotResult.Success(reply, language=null, places=emptyList(), navigation=null)`.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/virtueson/copilotmaps/data/CopilotRepositoryTest.kt`:

```kotlin
package com.virtueson.copilotmaps.data

import com.virtueson.copilotmaps.network.CopilotApi
import com.virtueson.copilotmaps.network.CopilotAskRequestDto
import com.virtueson.copilotmaps.network.CopilotAskResponseDto
import com.virtueson.copilotmaps.network.PlaceDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeCopilotApi(private val response: CopilotAskResponseDto) : CopilotApi {
    override suspend fun ask(body: CopilotAskRequestDto): CopilotAskResponseDto = response
}

class CopilotRepositoryTest {
    @Test
    fun `maps response places into the success result`() = runBlocking {
        val api = FakeCopilotApi(
            CopilotAskResponseDto(
                reply = "gas ahead",
                language = "en",
                places = listOf(
                    PlaceDto(id = "p1", name = "Shell Tendean", lat = -6.24, lng = 106.82, rating = 4.3),
                ),
                navigation = null,
            )
        )
        val repo = DefaultCopilotRepository(api)

        val result = repo.ask(
            messages = emptyList(),
            context = TripContext(GeoPoint(-6.24, 106.82), null, emptyList()),
            sessionId = "s",
            turnIndex = 0,
        )

        result as CopilotResult.Success
        assertEquals("gas ahead", result.reply)
        assertEquals(listOf("Shell Tendean"), result.places.map { it.name })
        assertEquals(GeoPoint(-6.24, 106.82), result.places.first().location)
        assertNull(result.navigation)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run (PowerShell):
```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat testDebugUnitTest --tests "com.virtueson.copilotmaps.data.CopilotRepositoryTest" --console=plain
```
Expected: FAIL to compile — `CopilotAskResponseDto` has no `places`/`navigation`, `CopilotResult.Success` has no `places`.

- [ ] **Step 3: Add fields to the response DTO**

Edit `network/CopilotDtos.kt`. Replace `CopilotAskResponseDto`:

```kotlin
data class CopilotAskResponseDto(
    val reply: String,
    val language: String? = null,
    val places: List<PlaceDto> = emptyList(),
    val navigation: PlaceDto? = null,
)
```

- [ ] **Step 4: Extend `CopilotResult.Success`**

Edit `data/ChatMessage.kt`. Replace the `CopilotResult` block:

```kotlin
sealed interface CopilotResult {
    data class Success(
        val reply: String,
        val language: String? = null,
        val places: List<Place> = emptyList(),
        val navigation: Place? = null,
    ) : CopilotResult
    data class Failure(val reason: String) : CopilotResult
}
```

- [ ] **Step 5: Extract a shared `PlaceDto.toPlace()` and use it in both repos**

Edit `data/PlacesRepository.kt`. Add the import `import com.virtueson.copilotmaps.network.PlaceDto` and a top-level extension, then use it in the mapping:

```kotlin
fun PlaceDto.toPlace(): Place = Place(
    id = id,
    name = name,
    location = GeoPoint(lat, lng),
    address = address,
    rating = rating,
    priceLevel = priceLevel,
    openNow = openNow,
)
```

Replace the inline `response.places.map { dto -> Place(...) }` block with:

```kotlin
            val places = response.places.map { it.toPlace() }
```

- [ ] **Step 6: Map places in the copilot repository**

Edit `data/CopilotRepository.kt`. Add `import com.virtueson.copilotmaps.data.toPlace` is NOT needed (same package `data`). Change the success mapping line:

```kotlin
            CopilotResult.Success(
                reply = response.reply,
                language = response.language,
                places = response.places.map { it.toPlace() },
                navigation = response.navigation?.toPlace(),
            )
```

- [ ] **Step 7: Run the test + full unit suite**

Run:
```
.\gradlew.bat testDebugUnitTest --tests "com.virtueson.copilotmaps.data.CopilotRepositoryTest" --console=plain
.\gradlew.bat testDebugUnitTest --console=plain
```
Expected: PASS — the new test green, no regressions.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/virtueson/copilotmaps/network/CopilotDtos.kt android/app/src/main/java/com/virtueson/copilotmaps/data/ChatMessage.kt android/app/src/main/java/com/virtueson/copilotmaps/data/PlacesRepository.kt android/app/src/main/java/com/virtueson/copilotmaps/data/CopilotRepository.kt android/app/src/test/java/com/virtueson/copilotmaps/data/CopilotRepositoryTest.kt
git commit -m "feat(android): map copilot response places into CopilotResult"
```

---

### Task 4: Android — copilot places become map pins via `PlacesViewModel`

**Files:**
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/PlacesViewModel.kt` (add `showResults`)
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/copilot/CopilotViewModel.kt` (expose latest places)
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt` (push copilot places into PlacesViewModel)
- Test: `android/app/src/test/java/com/virtueson/copilotmaps/ui/map/PlacesViewModelTest.kt` (add one test)

**Interfaces:**
- Consumes: `CopilotResult.Success.places` (Task 3), `PlacesState.Loaded`.
- Produces: `PlacesViewModel.showResults(places: List<Place>)` → sets `PlacesState.Loaded(places, fellBackToNearby = false)`.

- [ ] **Step 1: Write the failing test**

Add to `android/app/src/test/java/com/virtueson/copilotmaps/ui/map/PlacesViewModelTest.kt` (reuse the file's existing `FakePlacesRepository`, `place`, `origin` helpers):

```kotlin
    @Test
    fun `showResults sets Loaded with the given places`() = runTest {
        val vm = PlacesViewModel(FakePlacesRepository(PlacesResult.Success(emptyList(), "nearby")))

        vm.showResults(listOf(place("Shell"), place("Pertamina")))

        val state = vm.state.value
        state as PlacesState.Loaded
        assertEquals(listOf("Shell", "Pertamina"), state.places.map { it.name })
        assertFalse(state.fellBackToNearby)
    }
```

Ensure `assertFalse` is imported (`import org.junit.Assert.assertFalse`) — add it only if absent.

- [ ] **Step 2: Run test to verify it fails**

Run:
```
.\gradlew.bat testDebugUnitTest --tests "com.virtueson.copilotmaps.ui.map.PlacesViewModelTest" --console=plain
```
Expected: FAIL to compile — `showResults` is unresolved.

- [ ] **Step 3: Add `showResults` to `PlacesViewModel`**

Edit `ui/map/PlacesViewModel.kt`. Add the import `import com.virtueson.copilotmaps.data.Place` and a method after `search(...)`:

```kotlin
    /** Show an externally-produced result set (e.g. from the copilot) as pins. */
    fun showResults(places: List<Place>) {
        _state.value = PlacesState.Loaded(places = places, fellBackToNearby = false)
    }
```

- [ ] **Step 4: Emit the copilot's search places as an event**

Edit `ui/copilot/CopilotViewModel.kt`. Use a `SharedFlow` **event** (not `StateFlow`) so an identical result set after a Clear still re-shows pins — a `StateFlow` would dedup the equal value and silently drop it. Add these imports if absent:
`import com.virtueson.copilotmaps.data.Place`,
`import kotlinx.coroutines.flow.MutableSharedFlow`,
`import kotlinx.coroutines.flow.SharedFlow`,
`import kotlinx.coroutines.flow.asSharedFlow`.

Add a field next to `_state`:

```kotlin
    private val _placeResults = MutableSharedFlow<List<Place>>(extraBufferCapacity = 1)
    val placeResults: SharedFlow<List<Place>> = _placeResults.asSharedFlow()
```

In `sendMessage`, inside the `is CopilotResult.Success ->` branch, right after updating `messages`, emit the places:

```kotlin
                    if (result.places.isNotEmpty()) {
                        _placeResults.tryEmit(result.places)
                    }
```

- [ ] **Step 5: Push copilot places into `PlacesViewModel` from the screen**

Edit `ui/map/MapScreen.kt`. Just after the existing `val copilotState by copilotViewModel.state.collectAsStateWithLifecycle()` line (around line 147), add a one-time collector:

```kotlin
    LaunchedEffect(Unit) {
        copilotViewModel.placeResults.collect { places ->
            placesViewModel.showResults(places)
        }
    }
```

- [ ] **Step 6: Run the test, full unit suite, and a compile check**

Run:
```
.\gradlew.bat testDebugUnitTest --tests "com.virtueson.copilotmaps.ui.map.PlacesViewModelTest" --console=plain
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat compileDebugKotlin --console=plain
```
Expected: PASS — new test green, no regressions, compiles.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/virtueson/copilotmaps/ui/map/PlacesViewModel.kt android/app/src/main/java/com/virtueson/copilotmaps/ui/copilot/CopilotViewModel.kt android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt android/app/src/test/java/com/virtueson/copilotmaps/ui/map/PlacesViewModelTest.kt
git commit -m "feat(android): copilot search results drop map pins"
```

---

### Task 5: Android UI — remove top Gas/Food/Clear; move Clear to the right above recenter

**Files:**
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt`

**Interfaces:**
- Consumes: `onClearPlaces`, `placesState`, existing `following` state and the ◎ recenter FAB.
- Produces: no new public interface; a Compose-only layout change.

This task has no unit test (Compose UI). It is verified by `compileDebugKotlin` and on-device.

- [ ] **Step 1: Remove the top category button strip**

Edit `ui/map/MapScreen.kt`. Delete this block (currently ~lines 535-544):

```kotlin
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier.padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Button(onClick = { onSearchPlaces(PlaceCategory.GAS) }) { Text("Gas") }
                        Button(onClick = { onSearchPlaces(PlaceCategory.FOOD) }) { Text("Food") }
                        OutlinedButton(onClick = onClearPlaces) { Text("Clear") }
                    }
                }
```

Also delete the now-unused `Spacer(Modifier.height(8.dp))` immediately **above** that `Surface` (the one at ~line 533) so there isn't a double gap before the banner.

- [ ] **Step 2: Add a Clear FAB stacked above the recenter ◎**

Still in `ui/map/MapScreen.kt`, replace the existing recenter FAB block (currently ~lines 603-610):

```kotlin
        if (!following) {
            FloatingActionButton(
                onClick = { following = true },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp),
            ) { Text("◎") }
        }
```

with a Column that holds an optional Clear FAB above the optional recenter FAB:

```kotlin
        val hasPins = placesState is PlacesState.Loaded &&
            (placesState as PlacesState.Loaded).places.isNotEmpty()
        if (hasPins || !following) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (hasPins) {
                    FloatingActionButton(onClick = onClearPlaces) { Text("🗑") }
                }
                if (!following) {
                    FloatingActionButton(onClick = { following = true }) { Text("◎") }
                }
            }
        }
```

- [ ] **Step 3: Remove now-dead code if unused**

`onSearchPlaces` and `PlaceCategory` are no longer referenced from the UI after Step 1. Leave the `onSearchPlaces` parameter in `RoutingMap`'s signature and its call site in `MapScreen` **as-is for now** (removing them touches more surface than this task needs and `PlaceCategory` is still used by `SearchViewModel`/tests). Do NOT delete `PlaceCategory`. If the compiler warns that `onSearchPlaces` is unused, that warning is acceptable for this task.

- [ ] **Step 4: Compile check**

Run:
```
.\gradlew.bat compileDebugKotlin --console=plain
```
Expected: BUILD SUCCESSFUL (an "unused parameter onSearchPlaces" warning is OK).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt
git commit -m "feat(android): drop top category buttons; move Clear beside recenter"
```

---

### Task 6: Android UI — tappable place markers with a Directions card

**Files:**
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt`

**Interfaces:**
- Consumes: `placesState`, existing `destination`, `onPlan`, `cameraPositionState`, `following`, `searchScope`, `haversineMeters`, `priceSymbol`.
- Produces: no new public interface; a Compose-only marker interaction + detail card.

No unit test (Compose UI). Verified by `compileDebugKotlin` + on-device.

- [ ] **Step 1: Add selected-place state**

Edit `ui/map/MapScreen.kt`. Near the other `remember` state at the top of `RoutingMap` (around line 341, by `var showChat by remember { mutableStateOf(false) }`), add:

```kotlin
    var selectedPlace by remember { mutableStateOf<Place?>(null) }
```

- [ ] **Step 2: Make place markers tap to select**

Replace the existing place-markers block (currently ~lines 423-435):

```kotlin
            if (placesState is PlacesState.Loaded) {
                placesState.places.forEach { place ->
                    Marker(
                        state = rememberMarkerState(
                            key = place.id,
                            position = LatLng(place.location.lat, place.location.lng),
                        ),
                        title = place.name,
                        snippet = placeSnippet(place),
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE),
                        onClick = {
                            selectedPlace = place
                            true  // consume: show our own card, not the native bubble
                        },
                    )
                }
            }
```

- [ ] **Step 3: Render the Directions detail card**

Still in `RoutingMap`, add this just before the closing brace of the outer `Box` (right after the `if (showChat) { CopilotChatSheet(...) }` block, around line 620):

```kotlin
        selectedPlace?.let { place ->
            PlaceDetailCard(
                place = place,
                distanceMeters = haversineMeters(origin, place.location).toInt(),
                onDismiss = { selectedPlace = null },
                onDirections = {
                    val dest = LatLng(place.location.lat, place.location.lng)
                    following = false
                    if (navState is NavUiState.Active) onEndNav()
                    destination = dest
                    onPlan(place.location)
                    selectedPlace = null
                    searchScope.launch {
                        val bounds = LatLngBounds.builder()
                            .include(LatLng(origin.lat, origin.lng))
                            .include(dest)
                            .build()
                        try {
                            cameraPositionState.animate(
                                CameraUpdateFactory.newLatLngBounds(bounds, 120), 1000,
                            )
                        } catch (_: Exception) {
                            // map not ready; ignore
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
```

- [ ] **Step 4: Add the `PlaceDetailCard` composable**

Add this private composable near `SearchResultRow` (bottom of the file, before `maneuverArrow`):

```kotlin
@Composable
private fun PlaceDetailCard(
    place: Place,
    distanceMeters: Int,
    onDismiss: () -> Unit,
    onDirections: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .fillMaxWidth()
            .padding(12.dp),
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(place.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("✕") }
            }
            val bits = buildList {
                place.rating?.let { add("★$it") }
                priceSymbol(place.priceLevel).takeIf { it.isNotEmpty() }?.let { add(it) }
                place.openNow?.let { add(if (it) "Open now" else "Closed") }
                add(formatDistance(distanceMeters))
            }
            Text(bits.joinToString(" · "))
            place.address?.let { Text(it, fontSize = 12.sp) }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDirections) { Text("Directions") }
        }
    }
}
```

- [ ] **Step 5: Compile check**

Run:
```
.\gradlew.bat compileDebugKotlin --console=plain
```
Expected: BUILD SUCCESSFUL. (If `priceSymbol` is not resolvable in this file, it is defined in the same `ui.map` package — confirm the import; `SearchResultRow` already uses it, so no new import is needed.)

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt
git commit -m "feat(android): tappable place markers with a Directions card"
```

---

## On-device verification (after all tasks)

With the backend running (updated code) and `adb reverse tcp:8000 tcp:8000` set, install and drive the app:

1. Set a route.
2. Ask the copilot "any gas on my route" → **orange pins appear** on the map for the returned places (in addition to the spoken reply).
3. Tap a pin → the **detail card** slides up (name, ★, distance, address) with a **Directions** button.
4. Tap **Directions** → the route to that place plots and the **Start** button appears (existing flow).
5. Confirm the top strip no longer has Gas/Food/Clear, and a **🗑 Clear** button sits on the right above ◎ when pins are showing; tapping it clears the pins.

Install: `.\gradlew.bat installDebug --console=plain` (with `JAVA_HOME` set).

## Notes for the reviewer

- Phase 1 leaves `AskResult.navigation` / `CopilotAskResponse.navigation` always `None`; that is intentional (Phase 2 fills it). The fields exist now so the wire format is stable across phases.
- `onSearchPlaces`/`PlaceCategory` remain wired but unused by the UI after Task 5 — a deliberate minimal-surface choice; a later cleanup can remove the plumbing if the buttons never return.
