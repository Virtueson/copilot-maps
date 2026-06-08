# Copilot Maps — Step 4a: Text Copilot — Design Spec

**Date:** 2026-06-01
**Status:** Approved (design), pending implementation plan
**Author:** Russell (with Claude)

---

## Context

Steps 1–3 are complete: live-GPS map, routing (3 alternates + ETAs + traffic
colors), and places (gas/food along route or nearby). Backend has two POST
endpoints (`/routes/plan`, `/places/search`) plus `/health`, all following a
provider-protocol (stub + Google) pattern.

**This spec covers Step 4a: the text copilot** — a `POST /copilot/ask` LLM agent
loop (Claude + tools) and a **text chat** UI. Step 4b (voice: SpeechRecognizer +
TextToSpeech around this same endpoint) is a separate later cycle.

The `/copilot/ask` agent reuses the Step 3 places capability **as a tool**.

---

## Goal & Scope

**Goal:** A text chat where you ask plain-language questions about your current
trip and a Claude-powered copilot answers — reasoning over your routes/traffic and
calling a places tool for "what's on my way."

**Scope decisions (agreed):**

- **Answer-only (scope A):** the copilot reasons over current trip context and can
  call **one tool, `search_places`**; it replies in **text**. It does NOT change the
  map, plan new routes, or take actions (deferred).
- **Grounding:** the app sends **current trip context** (location + the planned
  routes with ETA/traffic) **fresh each turn**; route/traffic questions are answered
  from context, places questions via the tool.
- **Memory:** **in-RAM, session-only** conversation held in the app's
  `CopilotViewModel`. Changing destination keeps the chat (only context updates);
  closing the app starts fresh. Stateless backend. (Local persistence and a
  long-term user *profile* are noted for later.)
- **Agent:** a hand-rolled loop on the Anthropic SDK, **stub-first**
  (`CopilotProvider` protocol: `StubCopilot` + `AnthropicCopilot`).
- **Model:** `claude-haiku-4-5-20251001` (fast/cheap for driving Q&A).

**Definition of Done:**

1. `POST /copilot/ask` returns a reply (stub, then real Anthropic), executing the
   `search_places` tool when needed.
2. In the app: a chat sheet where asking "which route is faster?" answers from
   context and "any gas on my route?" triggers the tool and answers.
3. Conversation persists while the app is open; resets on close.
4. Server/Anthropic errors handled gracefully — no crash.
5. Backend pytest (incl. the fake-LLM agent-loop test) and app `CopilotViewModel`
   unit tests pass.

---

## The Contract (`POST /copilot/ask`)

**Request:**
```json
{
  "messages": [ { "role": "user", "content": "any gas station on my route?" } ],
  "context": {
    "origin": { "lat": 1.2966, "lng": 103.7764 },
    "selected_route_polyline": "yvw~Fhi}uM...",
    "routes": [
      { "summary": "Pan Island Expwy", "distance_meters": 12400,
        "duration_seconds": 980, "traffic": "moderate", "selected": true },
      { "summary": "Central Expwy", "distance_meters": 13100,
        "duration_seconds": 1040, "traffic": "light", "selected": false }
    ]
  }
}
```
- `messages` — app's in-RAM history + new user turn, each `{role: "user"|"assistant", content}`.
- `context` — current trip snapshot, sent every turn:
  - `origin` — location (places tool nearby/fallback center).
  - `selected_route_polyline` — selected route's encoded polyline (places tool
    along-route), or `null`.
  - `routes` — alternates the copilot reasons over; `traffic` is a short label
    (`"light"|"moderate"|"heavy"`) the **app computes** from traffic intervals;
    `selected` marks the active route.

**Response:** `{ "reply": "Yes — a Shell station ~3 km ahead, on your route (4.3★)." }`

**Pydantic:**
```python
class ChatMessage(BaseModel):
    role: str          # "user" | "assistant"
    content: str

class RouteSummary(BaseModel):
    summary: str
    distance_meters: int
    duration_seconds: int
    traffic: str       # "light" | "moderate" | "heavy"
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

---

## Backend Design

```
backend/app/
  models.py              # + ChatMessage, RouteSummary, CopilotContext, CopilotAsk*
  copilot/
    __init__.py
    base.py              # CopilotProvider protocol: ask(messages, context) -> str
    stub.py              # StubCopilot — canned deterministic reply (offline)
    anthropic_agent.py   # AnthropicCopilot — the agent loop
    tools.py             # search_places tool schema + executor
  routers/copilot.py     # POST /copilot/ask
  config.py              # + copilot_provider, anthropic_api_key, get_copilot()
  main.py                # register copilot router
  requirements.txt       # + anthropic
```

- **`CopilotProvider`** (`base.py`): `async def ask(self, messages: list[ChatMessage],
  context: CopilotContext) -> str`.
- **`tools.py`:**
  - `SEARCH_PLACES_TOOL` — Claude tool schema, `input_schema { query: string }`,
    description for finding gas/food on route or nearby.
  - `execute_search_places(query, context, places_provider) -> str` — runs the **Step
    3 along-route→nearby fallback** using `context.selected_route_polyline` + `origin`;
    returns a compact text list (incl. `mode`) for the model to read.
- **`AnthropicCopilot(api_key, places_provider)`:**
  - **System prompt** = stable persona (concise, spoken-style in-car copilot) +
    serialized trip context (the routes). Stable persona + tool defs are
    **prompt-cached**; per-turn context is not.
  - **Loop** (max ~5 iterations): `messages.create(model, system, tools, messages)` →
    on `stop_reason == "tool_use"` run the executor, append `tool_result`, loop; else
    return text.
  - **Model:** `claude-haiku-4-5-20251001`.
- **`StubCopilot`:** deterministic canned reply referencing context (e.g. route count
  + fastest), no Anthropic call.
- **`config.py`:** `copilot_provider` (`"stub"|"anthropic"`, default `"stub"`),
  `anthropic_api_key`; `get_copilot()` wires `AnthropicCopilot` with
  `get_places_provider()`.
- **`routers/copilot.py`:** `POST /copilot/ask` → `provider.ask(...)` → `{reply}`;
  Anthropic/`httpx` errors → 502.

The exact Anthropic SDK usage (async client, tool-use handling, prompt caching) is
done with the **claude-api skill** at build time.

**Setup (build task):** create an Anthropic API key at console.anthropic.com; add
`ANTHROPIC_API_KEY` and `COPILOT_PROVIDER=anthropic` to git-ignored `backend/.env`.

---

## App Design

```
android/app/src/main/java/com/virtueson/copilotmaps/
  network/   # + CopilotApi + Copilot DTOs (shared NetworkModule Retrofit)
  data/
    ChatMessage.kt       # domain ChatMessage(role, content) + Role enum + TripContext + RouteSummary
    CopilotRepository.kt # ask(messages, context) -> CopilotResult
  ui/copilot/
    CopilotState.kt      # CopilotUiState(messages, sending, error)
    CopilotViewModel.kt  # holds in-RAM history; sendMessage(text, context)
  ui/map/MapScreen.kt    # + chat FAB -> ModalBottomSheet chat panel
```

- **Domain:** `ChatMessage(role: Role, content)`, `Role { USER, ASSISTANT }`;
  `TripContext(origin: GeoPoint, selectedRoutePolyline: String?, routes:
  List<RouteSummary>)`; `RouteSummary(summary, distanceMeters, durationSeconds,
  traffic, selected)`.
- **`CopilotRepository`:** maps domain → DTOs, calls `CopilotApi`, returns reply or a
  failure message.
- **`CopilotViewModel`:** owns `CopilotUiState` (the session conversation).
  `sendMessage(text, context)` appends the user turn, sets `sending`, calls the repo,
  appends the assistant reply or sets `error`. Caps history to the **last ~12 turns**
  when sending.
- **`MapScreen`:** a **chat FAB** (bottom-right) opens a **`ModalBottomSheet`** with a
  `LazyColumn` of bubbles (user right, assistant left), a text field + **Send**, and a
  spinner while `sending`. On send, `MapScreen` **builds `TripContext` from live
  state** — `origin` from `MapViewModel`; `selectedRoutePolyline` + `routes` from
  `RouteViewModel`, computing each route's `traffic` label from its intervals (any
  `JAM`→`"heavy"`, any `SLOW`→`"moderate"`, else `"light"`) — and calls
  `copilotViewModel.sendMessage(text, context)`.

---

## Error Handling

- **Backend:** 422 (bad body); 502 (Anthropic error/timeout); agent loop max-iteration
  guard → graceful fallback reply; places-tool failure → error *string* the model
  relays (no 502).
- **App:** unreachable/timeout → `CopilotUiState.error` with the standard message;
  `sending` clears; resend to retry.

---

## Testing

- **Backend pytest:**
  - `/copilot/ask` with **stub copilot** → deterministic reply referencing context.
  - `execute_search_places` with a stub places provider: polyline → along-route;
    none → nearby.
  - **Agent loop with a fake Anthropic client** (scripted `tool_use` then text) →
    verifies the tool runs and the final answer returns, no real API call.
  - malformed body → 422.
- **App unit tests** (`CopilotViewModel`, fake repository): `sendMessage` appends user
  then assistant turn; failure sets `error`; `sending` toggles.
- **On-device:** chat sheet → "which route is faster?" (from context) and "any gas on
  my route?" (tool) → spoken-style replies. Stub copilot first (free), then real
  Anthropic.

---

## Out of Scope

- Voice (STT/TTS) — Step 4b.
- Actions: planning new routes, changing selection, dropping markers from chat
  (scope B/C — later).
- Landmark-at-turn answers ("what building before the left turn") — needs turn-by-turn
  steps (Step 5) + a Google **Address Descriptors** landmark tool; great later feature,
  esp. for Jakarta.
- Long-term **profile** memory (preferences) and local chat persistence — noted, later.
- Live-nav questions ("why did I miss that turn") — Step 5.

---

## Next Step

Proceed to the **writing-plans** skill for the implementation plan.
