# Design: Interactive places — copilot pins, tappable markers, voice navigation

**Date:** 2026-07-19
**Status:** Approved (pending spec review)

## Problem

Today the map's place pins come only from static **Gas / Food** buttons, and the
copilot's `search_places` results are spoken but never shown on the map (the
`/copilot/ask` response returns only `{reply, language}` and discards the
places). The two best capabilities — smart voice search and the visual map —
don't talk to each other. We want:

1. Remove the top Gas / Food / Clear buttons; keep Clear as an icon on the right
   above the recenter control.
2. When the copilot searches (e.g. "nearest gas"), drop the same pins the button
   would.
3. Tappable markers: tap a pin → see its description → a **Directions** button
   that routes there.
4. Voice navigation: tell the copilot a place ("go to Shell Tendean") → it routes
   there.
5. Safety constraint: the copilot may navigate **only** to a place from the last
   `search_places` result.

## Decisions (from brainstorming)

- **Voice-nav behavior:** plot the route and show **Start** (reuse the existing
  search-result-tap flow). Do NOT auto-start turn-by-turn.
- **Not-in-list behavior:** refuse and say why ("X isn't in your current
  results"). Never route to an unlisted place.
- **Matching split:** the LLM interprets the spoken words into a candidate name;
  the **backend** authoritatively matches that name against the real last-search
  list and owns the coordinates. The LLM never supplies coordinates.
- **Approach A (server-authoritative):** structured response fields + a
  `navigate_to_place` tool + the app sends the last-search list in context.
- **Two phases**, one spec (the plan sequences them).

## Architecture: one list, three uses

`PlacesState` becomes the single source of truth for "pins on the map = the last
search":

```
Gas button ─┐
            ├─→ PlacesState.Loaded(places) ──→ map pins
Copilot ────┘         │
"nearest gas"         └──→ sent back as context.places next turn ──→ authorizes voice-nav
```

Whether pins come from a button or the copilot, they land in the same state,
render with the same marker code, and become the same authorization list for
voice-navigation. Bonus: voice-nav then works for button searches too.

## Approaches considered

- **A — Server-authoritative (chosen).** Response gains `places` (pins) +
  `navigation` (resolved place); a `navigate_to_place` tool matches against the
  app-supplied last-search list; coordinates come from the matched place. The
  refusal is coherent because the constraint is enforced where the model can see
  the result, so the spoken reply reflects it.
- **B — App-side enforcement.** Copilot names a place; the app validates and
  routes-or-ignores. Rejected: the spoken reply can't reflect a silent app-side
  refusal (model might say "routing to X" while nothing happens).
- **C — Re-search on any named place.** Ignores the "last search only" rule.
  Rejected.

## Backend design

### Models (`app/models.py`)

- `CopilotContext` gains `places: list[Place] = []` — the current pins the app
  sends, so the backend can validate a nav request (Phase 2).
- `CopilotAskResponse` gains `places: list[Place] = []` (search hits → pins) and
  `navigation: Place | None = None` (resolved place to route to).
- `AskResult` gains the same `places` and `navigation` fields.

### Per-turn outputs collector

A tool executor currently returns only a string (what the LLM reads). We also
need structured data out: the `Place` objects for pins and the resolved nav
target. Introduce a small mutable `TurnOutputs` dataclass
(`places: list[Place]`, `navigation: Place | None`) threaded through
`registry.dispatch()` into the executors. `search_places` fills
`outputs.places`; `navigate_to_place` fills `outputs.navigation`. After the
agent loop, each provider copies `outputs` into its `AskResult`.

- `Tool.executor` signature becomes `async (args, context, outputs) -> str`.
- `dispatch(tools, name, args, context, outputs)` passes `outputs` through.
- Both providers (`openai_compat`, `anthropic_agent`) create a `TurnOutputs()`
  per `ask`, pass it into the `execute` closure, and read it after the loop.

### Tools (`app/copilot/tools.py`)

- `search_places` — same LLM-facing description; executor additionally appends
  the structured `Place` list to `outputs.places` (the full list, so the app can
  pin all of them; the text summary to the LLM keeps its Top-5 cap).
- **New** `navigate_to_place(name: str)` (Phase 2): matches `name` against
  `context.places`, case-insensitive.
  - exactly one match → set `outputs.navigation = place`; return "Routing to
    <name>."
  - zero matches → return "<name> isn't in your current results." (model
    refuses)
  - multiple matches → return "Which one — <A> or <B>?" (model disambiguates);
    `outputs.navigation` stays unset.

### Prompt (`app/copilot/prompt.py`) — Phase 2

- `format_context` appends the current place names when `context.places` is
  non-empty (e.g. "Places currently shown: Shell Tendean, Pertamina Kuningan
  Timur, …"); silent when empty.
- `PERSONA` gains one line: it may navigate only to a place currently shown, via
  `navigate_to_place`, and must refuse otherwise.

### Router (`app/routers/copilot.py`)

Copy `places` and `navigation` from `AskResult` into `CopilotAskResponse`. The
existing `{reply, language}` contract is preserved and extended, not broken.

## Android design

### DTOs (`network/`)

- `CopilotAskResponseDto` gains `places: List<PlaceDto>` and
  `navigation: PlaceDto?` (reuse the existing `PlaceDto`).
- `TripContextDto` (inside the copilot request) gains `places: List<PlaceDto>`
  (Phase 2).

### Data models

- `CopilotResult.Success` gains `places: List<Place>` and `navigation: Place?`.

### Wiring

- **Pins:** `PlacesViewModel` gains `showResults(places: List<Place>)` → sets
  `PlacesState.Loaded`. On a copilot `Success` carrying `places`, `MapScreen`
  pushes them into `placesViewModel` (a `LaunchedEffect` on the copilot result).
  Copilot pins and button pins are the same state, same markers.
- **Navigation (Phase 2):** `CopilotViewModel` exposes a one-shot navigation
  event (a `Channel`/`SharedFlow` of `Place`, fire-once, not state). `MapScreen`
  collects it and runs the existing route-to-a-place flow (set destination,
  `onPlan(place.location)`, fit camera, show `RouteCards` with **Start**).
- **Last-search context (Phase 2):** `buildTripContext` includes the current
  `PlacesState.Loaded.places` as `context.places`.

## UI design (Phase 1)

- **Remove** the top `Surface { Row { Gas / Food / Clear } }` block
  (`MapScreen.kt:535-544`). The destination search field above it stays.
- **Relocate Clear:** a `Column` at `CenterEnd` holding a Clear
  `FloatingActionButton` (🗑/✕ icon, shown when `PlacesState.Loaded` has places)
  stacked above the existing recenter ◎ FAB (shown when `!following`). Both are
  independently visible. Clear → `onClearPlaces()`.
- **Tappable markers:** each place `Marker` (`MapScreen.kt:423-435`) gets an
  `onClick` that sets `selectedPlace: Place?` and consumes the click (no native
  bubble — Android won't allow a real button inside a native info window). When
  `selectedPlace != null`, render a Compose **bottom card** with the place's
  name, ★rating, address, and distance, plus **Directions** and a dismiss (✕).
  **Directions** runs the same flow as a search-result tap
  (`MapScreen.kt:506-526`): set destination, `onPlan`, fit camera → the existing
  `RouteCards` with **Start** appear.
- The existing `PlacesState` status banner keeps working for copilot-driven
  searches; it just won't flash "Searching…" (copilot results arrive in one
  shot).

## Error handling

- Copilot returns no `places` (non-search question) → no change to pins.
- `navigate_to_place` no/ambiguous match → model refuses/disambiguates; no
  navigation event emitted; pins unchanged.
- Navigation event with an active route already loaded → same as tapping a new
  destination today (re-plans).
- Empty `context.places` → `format_context` says nothing about places; the model
  cannot navigate (nothing to match).

## Testing

**Backend (pytest, stubs — never live):**
- `navigate_to_place`: exact / case-insensitive / no-match / ambiguous → correct
  text and `outputs.navigation` set only on a unique match.
- `TurnOutputs` threading: `search_places` populates `outputs.places`; the agent
  loop copies `places`/`navigation` into `AskResult`; the router response carries
  them.
- `format_context`: lists names when `context.places` present, silent when empty.

**Android (plain-JVM JUnit, per the pure-decider convention):**
- `CopilotResult` / DTO mapping includes `places` + `navigation`.
- `PlacesViewModel.showResults(places)` → `Loaded`.
- `CopilotViewModel` emits the navigation event once per nav result and exposes
  `places`.
- Marker card, Clear relocation, and the routing UI are Android-only glue —
  verified by `compileDebugKotlin` + on-device testing, not unit tests.

## Phasing

- **Phase 1** — backend `places` (no nav tool), pins wiring, UI (cleanup + Clear
  relocation + tappable markers/Directions). Ships: copilot drops pins, tappable
  markers, cleaned-up UI. Verify on device.
- **Phase 2** — `navigate_to_place` tool, `context.places`, `navigation` event,
  persona/`format_context` updates. Ships: voice-navigate with the last-search
  constraint. Verify on device.

## Out of scope / non-goals

- No auto-start of turn-by-turn from voice (plot + Start only).
- No searching for places the copilot names but that aren't in the last search
  (strict refusal).
- No changes to the route-planning or navigation engines — voice-nav and the
  Directions button reuse the existing plan → Start flow.
- No new place-search categories or a replacement for the removed buttons; place
  search is now voice/copilot-driven (the destination search field is unchanged).
- No persistence of pins across app restarts (unchanged from today).
