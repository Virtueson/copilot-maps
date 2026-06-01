# Copilot Maps — Step 2: Routing — Design Spec

**Date:** 2026-06-01
**Status:** Approved (design), pending implementation plan
**Author:** Russell (with Claude)

---

## Context

Copilot Maps is an AI navigation app (see `copilot-maps-brief.md`), decomposed
into 5 sequential sub-projects. **Step 1 (Map + GPS) is complete**: a single-module
Jetpack Compose app shows a Google map centered on live GPS.

**This spec covers Step 2: Routing.** It adds the project's first backend and the
app's first network calls: tap a destination, ask the backend to plan routes, and
draw them on the map with ETAs.

The developer is an AI/backend engineer (backend is their comfort zone) on their
first Android app. End goal of the overall project: a Google Play Store launch.

---

## Goal & Scope

**Goal:** From the user's live location, let them set a destination and see up to
**3 alternate driving routes** drawn on the map, each with its ETA and distance,
and pick among them.

**Scope decisions (agreed):**

- **Destination input:** long-press the map to drop a destination pin. (No place
  search — that's Step 3.)
- **Routes:** single route **plus up to 3 alternates** with ETAs and tap-to-select.
- **Traffic coloring is OUT of scope** — deferred to a later "Step 2b" (per-segment
  speed rendering is fiddlier and not needed to prove routing).
- **Dev connectivity:** `adb reverse tcp:8000 tcp:8000` — the phone's
  `localhost:8000` tunnels over USB to the PC's backend. (Cloud deploy is a later,
  pre-launch concern.)
- **Build strategy:** **stub-first** — define the contract, serve it from a
  `StubRoutePlanner` with fake-but-real-geometry routes, build the whole app against
  the stub, then swap in the real Google Routes API with no app changes.

**Definition of Done:**

1. Backend `POST /routes/plan` returns 3 routes matching the contract (stub, then
   real Google Routes API).
2. On the phone: long-press the map → 3 route polylines draw between the user's
   location and the destination, with a bottom panel of ETA/distance cards.
3. Tapping a route card highlights that route.
4. Server-unreachable and no-route cases show graceful errors with retry — no crash.
5. Backend pytest and app unit tests pass.

---

## Architecture & Repo Structure

Monorepo; the backend becomes a sibling of `android/`.

```
AI Maps/
  android/                         # existing app (Step 1)
  backend/                         # NEW — FastAPI service
    app/
      main.py                      # FastAPI app + CORS + router + GET /health
      models.py                    # Pydantic request/response = THE CONTRACT
      config.py                    # Settings: ROUTE_PLANNER, GOOGLE_ROUTES_API_KEY
      routers/routes.py            # POST /routes/plan
      planners/
        base.py                    # RoutePlanner protocol
        stub.py                    # StubRoutePlanner (default; offline fake routes)
        google.py                  # GoogleRoutePlanner (real Routes API; added late)
    tests/test_routes.py           # pytest: endpoint + stub + validation + health
    requirements.txt
    .env.example                   # ROUTE_PLANNER=stub / GOOGLE_ROUTES_API_KEY=

  android/app/src/main/java/com/virtueson/copilotmaps/
    network/                       # Retrofit API + DTOs + Retrofit/OkHttp setup
    data/                          # Route (domain) + RoutesRepository (decodes polyline)
    ui/map/                        # RouteViewModel + RoutesState (NEW); MapScreen draws routes
```

**App ViewModel decision:** keep Step 1's `MapViewModel` (location) untouched and
add a **separate `RouteViewModel`** (routing). Two small, single-purpose ViewModels
on one screen, rather than one bloated one. `MapScreen` uses both: on a long-press
it reads the current location from `MapViewModel` and calls
`RouteViewModel.planRoutes(origin, destination)`.

---

## The Data Contract (`/routes/plan`)

The central artifact of stub-first: both sides agree on this shape.

**Request** (app → backend):

```json
POST /routes/plan
{
  "origin":      { "lat": 1.2966, "lng": 103.7764 },
  "destination": { "lat": 1.3521, "lng": 103.8198 }
}
```

**Response** (backend → app; up to 3 routes, **best first** so `routes[0]` is
recommended):

```json
{
  "routes": [
    {
      "id": "route-0",
      "summary": "Pan Island Expwy",
      "distance_meters": 12400,
      "duration_seconds": 980,
      "polyline": "yvw~Fhi}uM...encoded..."
    }
  ]
}
```

**Fields:** `id` (stable selection key), `summary` (route-card label),
`distance_meters` (int; app formats to km), `duration_seconds` (int, traffic-aware;
app formats to min), `polyline` (Google encoded polyline; app decodes to points).

**Pydantic (`app/models.py`):**

```python
class LatLng(BaseModel):
    lat: float
    lng: float

class Route(BaseModel):
    id: str
    summary: str
    distance_meters: int
    duration_seconds: int
    polyline: str

class RoutePlanRequest(BaseModel):
    origin: LatLng
    destination: LatLng

class RoutePlanResponse(BaseModel):
    routes: list[Route]
```

**Deliberately excluded (YAGNI):** arrival clock-time (app computes `now +
duration`), per-segment traffic (Step 2b), turn-by-turn steps (Step 5).

**Extensibility requirement:** the contract MUST evolve additively. Live navigation
(Step 5) will add **optional** fields (e.g., a `steps` array per route for
turn-by-turn and missed-turn detection) without changing existing field meanings;
Step 2 code must keep working unchanged. An optional elevation profile (via Google's
separate Elevation API) could be added the same way if needed. (Note: which physical
road deck you're on — flyover vs. road beneath — is NOT available from Google's APIs
and is out of scope for the whole project.)

---

## Backend Design

- **`models.py`** — the contract above; single source of truth.
- **`planners/base.py`** — the seam:
  ```python
  class RoutePlanner(Protocol):
      async def plan(self, origin: LatLng, destination: LatLng) -> list[Route]: ...
  ```
  The endpoint depends only on this protocol.
- **`planners/stub.py`** — `StubRoutePlanner`. Returns 3 routes that actually
  connect origin → destination (each built as `origin → an offset midpoint →
  destination`, encoded to a polyline), with `distance_meters` from haversine and
  `duration_seconds` from distance ÷ an assumed speed. Three midpoint offsets →
  three distinct alternates with differing ETAs. No Google call; works offline.
- **`planners/google.py`** — `GoogleRoutePlanner` (added in a late task). Calls
  `directions/v2:computeRoutes` via `httpx` with `computeAlternativeRoutes: true`,
  `routingPreference: TRAFFIC_AWARE`, and a field mask; maps Google's `routes[]`
  (`polyline.encodedPolyline`, `duration`, `distanceMeters`, `description`) → our
  `list[Route]`. Same return type as the stub.
- **`config.py`** — `Settings` (pydantic-settings) from `.env`: `ROUTE_PLANNER`
  (`"stub"` | `"google"`, default `"stub"`) and `GOOGLE_ROUTES_API_KEY`. A factory
  selects the planner.
- **`routers/routes.py`** — `POST /routes/plan`: validate body, call configured
  planner, return `RoutePlanResponse`.
- **`main.py`** — register router, permissive **CORS** in dev, `GET /health` → 200.

**Backend data flow:**
```
POST /routes/plan → Pydantic validate → planner.plan(origin, dest)
   → [stub builds fake routes | google calls Routes API + maps] → RoutePlanResponse
```

**The swap moment:** changing `.env` `ROUTE_PLANNER=stub` → `google` (and adding the
key) is the entire difference between fake and real routes. App, contract, and tests
are unchanged.

**Dependencies:** `fastapi`, `uvicorn`, `pydantic`, `pydantic-settings`, `httpx`,
`polyline`.

---

## App Design

- **`network/`**
  - `RoutesApi` — `@POST("routes/plan") suspend fun planRoutes(@Body body:
    RoutePlanRequestDto): RoutePlanResponseDto`.
  - DTOs: `LatLngDto`, `RoutePlanRequestDto`, `RouteDto`, `RoutePlanResponseDto`.
  - `NetworkModule` — Retrofit (OkHttp + Moshi + logging interceptor), **base URL
    `http://localhost:8000/`** (works via `adb reverse`).
- **`data/`**
  - `Route` (domain) — `id, summary, distanceMeters, durationSeconds, points:
    List<LatLng>` (`points` already decoded).
  - `RoutesRepository` — calls `RoutesApi`, decodes each `polyline` via
    `android-maps-utils` `PolyUtil.decode`, maps DTO → domain, returns success(list)
    or error.
- **`ui/map/`**
  - `RoutesState` — `Idle / Loading / Loaded(routes, selectedId) / Error(message)`.
  - `RouteViewModel` — `planRoutes(origin, destination)`, `selectRoute(id)`; owns
    `RoutesState`; unit-tested with a fake repository.
  - `MapScreen` gains: **long-press** drops the destination marker and triggers
    planning; draws a `Polyline` per route (**selected = thick/blue, others =
    thin/gray**); a **bottom panel** of route cards (summary · ETA · distance),
    tap-to-select.

**App data flow (one long-press):**
```
long-press map → origin (MapViewModel location) + dest (tapped point)
   → RouteViewModel.planRoutes(origin, dest) → RoutesRepository → Retrofit → backend
   → decode polylines → RoutesState.Loaded(3 routes, selected=route-0)
   → MapScreen draws 3 polylines + 3 ETA cards; route-0 highlighted
```

**Required Android detail:** Android blocks cleartext HTTP by default and
`localhost` is HTTP. Add a **debug-only network-security-config** permitting
cleartext to `localhost` (debug builds only, never release).

**New app dependencies:** Retrofit + converter-moshi + okhttp logging-interceptor,
`com.google.maps.android:android-maps-utils` (for `PolyUtil`).

---

## Error Handling

**Backend:**
- Invalid body → **422** (Pydantic).
- No route found → **200** with `{"routes": []}` (empty is a valid answer).
- Google API failure/timeout (real planner) → **502** with a clear message.
- `GET /health` → **200** for connectivity checks.

**App (`RoutesState.Error` + Retry):**
- Can't reach server (backend down or `adb reverse` not set) → explicit message:
  *"Can't reach the server — is the backend running and `adb reverse` set?"*
- Timeout / unexpected response → generic message + Retry.
- Empty routes → "No route found for that destination."

---

## Testing

**Backend (pytest + FastAPI `TestClient`):**
- `POST /routes/plan` valid body → 200, 3 routes, each non-empty polyline + positive
  distance/duration.
- Malformed body → 422.
- `GET /health` → 200.

**App (JVM unit tests, fake `RoutesRepository`):**
- `planRoutes` success → `Loaded`, `selectedId` = first route.
- `planRoutes` failure → `Error`.
- `selectRoute(id)` → `selectedId` updates.

**Manual on-device (stub, then again after Google swap):**
- `adb reverse` + backend running → long-press map → 3 polylines + 3 ETA cards;
  tapping a card moves the highlight; kill backend → Error + Retry.

---

## Build Order (becomes the implementation plan)

1. **Backend, stubbed** — contract + `StubRoutePlanner` + `/routes/plan` + `/health`
   + CORS + pytest. Run locally, `curl`, see 3 fake routes.
2. **App ↔ backend wired** — `adb reverse` + debug cleartext config; Retrofit + DTOs
   + `RoutesRepository` + `RouteViewModel` + unit tests. Verify the app receives the
   stub's 3 routes (logged).
3. **App UI** — long-press destination, draw 3 polylines, bottom ETA cards,
   tap-to-select. Verify on the phone against the stub (the big visual payoff).
4. **Real Google routes** — add `GoogleRoutePlanner` + Cloud setup (enable Routes
   API, create a *server* API key) + flip `ROUTE_PLANNER=google`. Verify real routes
   end-to-end on device. App and tests unchanged.

---

## Out of Scope (Step 2)

- Traffic coloring (per-segment) — Step 2b.
- Place/address search for destinations — Step 3.
- Turn-by-turn, missed-turn detection, re-routing, background location — Step 5.
- The copilot LLM agent — Step 4.
- Cloud deployment of the backend — a pre-launch concern, not now.
- Elevation profiles and road-deck (flyover) disambiguation — not in this step;
  deck-level disambiguation is not available from Google's APIs at all.

---

## Next Step

Proceed to the **writing-plans** skill to turn this design into a detailed,
ordered implementation plan.
