# Step 5a — Turn-by-Turn Route Data Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add turn-by-turn `steps` (instruction, maneuver, distance, location) to the route data — backend model, Google parsing, stub steps, and app DTO→domain mapping — with no visible UI change.

**Architecture:** Extend the existing route contract. `Route` gains `steps: list[RouteStep]` (default empty, backward-compatible). `GoogleRoutePlanner` fetches + parses `legs.steps`; `StubRoutePlanner` emits fake steps for offline dev; the app threads `steps` through `RouteDto` → domain `Route` exactly like the existing `trafficIntervals`.

**Tech Stack:** Python/FastAPI/Pydantic v2/pytest (backend); Kotlin/Moshi/Retrofit (app).

## Global Constraints

- Backend tests: `cd backend; .\.venv\Scripts\python.exe -m pytest -q` — must run against the **stub** (tests override `get_planner`); never hit live Google.
- App compile: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; cd android; .\gradlew.bat :app:compileDebugKotlin`.
- TDD: failing test → implement → pass → commit. Each task ends green.
- `steps` defaults to empty everywhere — existing behavior must stay unchanged.
- Personal-use project; no secrets committed (`backend/.env`, `local.properties` stay git-ignored — confirm `git status` before each commit).
- Package root: `com.virtueson.copilotmaps`.

---

### Task 1: Backend `RouteStep` model + stub steps

**Files:**
- Modify: `backend/app/models.py`
- Modify: `backend/app/planners/stub.py`
- Modify: `backend/tests/test_routes.py`

**Interfaces:**
- Produces: `RouteStep(instruction: str, maneuver: str, distance_meters: int, location: LatLng)`;
  `Route.steps: list[RouteStep] = []`. Stub routes carry `steps` (first `DEPART`, last `ARRIVE`).

- [ ] **Step 1: Write the failing contract test**

In `backend/tests/test_routes.py`, append:
```python
def test_plan_includes_turn_by_turn_steps():
    body = {
        "origin": {"lat": 1.2966, "lng": 103.7764},
        "destination": {"lat": 1.3521, "lng": 103.8198},
    }
    resp = client.post("/routes/plan", json=body)
    assert resp.status_code == 200
    for route in resp.json()["routes"]:
        steps = route["steps"]
        assert len(steps) >= 2
        assert steps[0]["maneuver"] == "DEPART"
        assert steps[-1]["maneuver"] == "ARRIVE"
        assert steps[0]["instruction"]
        assert steps[0]["distance_meters"] >= 0
        assert "lat" in steps[0]["location"] and "lng" in steps[0]["location"]
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend; .\.venv\Scripts\python.exe -m pytest tests/test_routes.py::test_plan_includes_turn_by_turn_steps -q`
Expected: FAIL with `KeyError: 'steps'` (Route has no steps field yet).

- [ ] **Step 3: Add the `RouteStep` model + `steps` field**

In `backend/app/models.py`, add `RouteStep` just above `class Route` and add the `steps` field:
```python
class RouteStep(BaseModel):
    instruction: str
    maneuver: str
    distance_meters: int
    location: LatLng


class Route(BaseModel):
    id: str
    summary: str
    distance_meters: int
    duration_seconds: int
    polyline: str
    traffic_intervals: list[TrafficInterval] = []
    steps: list[RouteStep] = []
```

- [ ] **Step 4: Emit steps from the stub planner**

In `backend/app/planners/stub.py`, change the import line:
```python
from app.models import LatLng, Route, RouteStep, TrafficInterval
```
Then, inside `plan()`'s loop, after `intervals = [...]` and before `leg_m = ...`, add:
```python
            mid_point = points[3]
            steps = [
                RouteStep(
                    instruction="Head toward destination",
                    maneuver="DEPART",
                    distance_meters=int(_haversine_m(origin, mid)),
                    location=points[0],
                ),
                RouteStep(
                    instruction="Turn onto the main road",
                    maneuver="TURN_RIGHT",
                    distance_meters=int(_haversine_m(mid, destination)),
                    location=mid_point,
                ),
                RouteStep(
                    instruction="Arrive at destination",
                    maneuver="ARRIVE",
                    distance_meters=0,
                    location=points[len(points) - 1],
                ),
            ]
```
Then add `steps=steps,` to the `Route(...)` constructor (after `traffic_intervals=intervals,`):
```python
            routes.append(
                Route(
                    id=f"route-{i}",
                    summary=summary,
                    distance_meters=int(leg_m),
                    duration_seconds=int(leg_m / _ASSUMED_SPEED_MPS),
                    polyline=encoded,
                    traffic_intervals=intervals,
                    steps=steps,
                )
            )
```
(`points` are `LatLng`; index 0 = origin, 3 = mid, last = destination — per `_densify`.)

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend; .\.venv\Scripts\python.exe -m pytest tests/test_routes.py -q`
Expected: PASS (all route tests).

- [ ] **Step 6: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add backend/app/models.py backend/app/planners/stub.py backend/tests/test_routes.py
git commit -m "feat(backend): RouteStep model + stub turn-by-turn steps"
```

---

### Task 2: Google planner step parsing

**Files:**
- Modify: `backend/app/planners/google.py`
- Create: `backend/tests/test_google_planner.py`

**Interfaces:**
- Consumes: `RouteStep`, `LatLng` (Task 1).
- Produces: `_parse_steps(item: dict) -> list[RouteStep]`; `GoogleRoutePlanner.plan` returns routes with `steps`.

- [ ] **Step 1: Write the failing parser test**

Create `backend/tests/test_google_planner.py`:
```python
from app.planners.google import _parse_steps


def test_parse_steps_extracts_fields_and_defaults_missing():
    item = {
        "legs": [
            {
                "steps": [
                    {
                        "navigationInstruction": {
                            "instructions": "Turn left onto Jl. Sudirman",
                            "maneuver": "TURN_LEFT",
                        },
                        "distanceMeters": 250,
                        "startLocation": {"latLng": {"latitude": -6.2, "longitude": 106.8}},
                    },
                    {
                        # navigationInstruction omitted (Google does this for some steps)
                        "distanceMeters": 100,
                        "startLocation": {"latLng": {"latitude": -6.21, "longitude": 106.81}},
                    },
                ]
            }
        ]
    }
    steps = _parse_steps(item)
    assert len(steps) == 2
    assert steps[0].instruction == "Turn left onto Jl. Sudirman"
    assert steps[0].maneuver == "TURN_LEFT"
    assert steps[0].distance_meters == 250
    assert steps[0].location.lat == -6.2
    assert steps[0].location.lng == 106.8
    assert steps[1].instruction == ""
    assert steps[1].maneuver == ""
    assert steps[1].distance_meters == 100


def test_parse_steps_no_legs_returns_empty():
    assert _parse_steps({}) == []
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend; .\.venv\Scripts\python.exe -m pytest tests/test_google_planner.py -q`
Expected: FAIL with `ImportError: cannot import name '_parse_steps'`.

- [ ] **Step 3: Implement `_parse_steps` + extend the field mask**

In `backend/app/planners/google.py`, update the import and field mask:
```python
from app.models import LatLng, Route, RouteStep, TrafficInterval

_COMPUTE_ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
_FIELD_MASK = (
    "routes.polyline.encodedPolyline,routes.duration,"
    "routes.distanceMeters,routes.description,"
    "routes.travelAdvisory.speedReadingIntervals,"
    "routes.legs.steps.navigationInstruction,"
    "routes.legs.steps.distanceMeters,"
    "routes.legs.steps.startLocation"
)
```
Add `_parse_steps` next to `_parse_intervals`:
```python
def _parse_steps(item: dict) -> list[RouteStep]:
    steps: list[RouteStep] = []
    for leg in item.get("legs", []):
        for s in leg.get("steps", []):
            nav = s.get("navigationInstruction", {})
            latlng = s.get("startLocation", {}).get("latLng", {})
            steps.append(
                RouteStep(
                    instruction=nav.get("instructions", ""),
                    maneuver=nav.get("maneuver", ""),
                    distance_meters=int(s.get("distanceMeters", 0)),
                    location=LatLng(
                        lat=latlng.get("latitude", 0.0),
                        lng=latlng.get("longitude", 0.0),
                    ),
                )
            )
    return steps
```
In `plan()`, add `steps=_parse_steps(item),` to the `Route(...)` constructor (after `traffic_intervals=_parse_intervals(item),`):
```python
            routes.append(
                Route(
                    id=f"route-{i}",
                    summary=item.get("description") or f"Route {i + 1}",
                    distance_meters=int(item.get("distanceMeters", 0)),
                    duration_seconds=seconds,
                    polyline=item["polyline"]["encodedPolyline"],
                    traffic_intervals=_parse_intervals(item),
                    steps=_parse_steps(item),
                )
            )
```

- [ ] **Step 4: Run the parser test to verify it passes**

Run: `cd backend; .\.venv\Scripts\python.exe -m pytest tests/test_google_planner.py -q`
Expected: PASS (2 tests).

- [ ] **Step 5: Run the full backend suite**

Run: `cd backend; .\.venv\Scripts\python.exe -m pytest -q`
Expected: PASS (all prior tests + the new ones).

- [ ] **Step 6: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add backend/app/planners/google.py backend/tests/test_google_planner.py
git commit -m "feat(backend): parse Google legs.steps into RouteStep"
```

---

### Task 3: App DTO + domain + repository mapping

**Files:**
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/network/Dtos.kt`
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/data/Route.kt`
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/data/RoutesRepository.kt`

**Interfaces:**
- Consumes: backend `steps` JSON (Task 1/2).
- Produces: `RouteStepDto`; `RouteDto.steps`; domain `RouteStep(instruction, maneuver, distanceMeters, location: GeoPoint)`; `Route.steps`.

> No new app logic to unit-test in 5a (pure DTO→domain mapping, same shape as `trafficIntervals`). Deliverable: compiles. Consumed on device starting in 5b/5c.

- [ ] **Step 1: Add `RouteStepDto` + `steps` to the DTO**

In `network/Dtos.kt`, add after `TrafficIntervalDto` and add the field to `RouteDto`:
```kotlin
data class RouteStepDto(
    val instruction: String,
    val maneuver: String,
    @Json(name = "distance_meters") val distanceMeters: Int,
    val location: LatLngDto,
)

data class RouteDto(
    val id: String,
    val summary: String,
    @Json(name = "distance_meters") val distanceMeters: Int,
    @Json(name = "duration_seconds") val durationSeconds: Int,
    val polyline: String,
    @Json(name = "traffic_intervals") val trafficIntervals: List<TrafficIntervalDto> = emptyList(),
    @Json(name = "steps") val steps: List<RouteStepDto> = emptyList(),
)
```

- [ ] **Step 2: Add `RouteStep` + `steps` to the domain model**

In `data/Route.kt`, add `RouteStep` and the `steps` field:
```kotlin
package com.virtueson.copilotmaps.data

/** One turn-by-turn maneuver. `location` is where the maneuver happens (step start). */
data class RouteStep(
    val instruction: String,
    val maneuver: String,
    val distanceMeters: Int,
    val location: GeoPoint,
)

/** A drawable route: geometry already decoded into points. */
data class Route(
    val id: String,
    val summary: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val points: List<GeoPoint>,
    val polyline: String = "",
    val trafficIntervals: List<TrafficInterval> = emptyList(),
    val steps: List<RouteStep> = emptyList(),
)

sealed interface RoutesResult {
    data class Success(val routes: List<Route>) : RoutesResult
    data class Failure(val reason: String) : RoutesResult
}
```

- [ ] **Step 3: Map `steps` in the repository**

In `data/RoutesRepository.kt`, add the `steps` mapping inside the `Route(...)` built in `planRoutes` (after the `trafficIntervals = ...` block):
```kotlin
                    trafficIntervals = dto.trafficIntervals.map {
                        TrafficInterval(it.startIndex, it.endIndex, it.speed.toTrafficSpeed())
                    },
                    steps = dto.steps.map {
                        RouteStep(
                            instruction = it.instruction,
                            maneuver = it.maneuver,
                            distanceMeters = it.distanceMeters,
                            location = GeoPoint(it.location.lat, it.location.lng),
                        )
                    },
```

- [ ] **Step 4: Compile**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; cd android; .\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run app unit tests (confirm nothing regressed)**

Run: `cd android; .\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add android/app/src/main/java/com/virtueson/copilotmaps/network/Dtos.kt android/app/src/main/java/com/virtueson/copilotmaps/data/Route.kt android/app/src/main/java/com/virtueson/copilotmaps/data/RoutesRepository.kt
git commit -m "feat(app): map turn-by-turn steps into domain Route"
```

---

## Definition of Done (verify all)

- `Route` carries `steps`; Google planner parses real `legs.steps`; stub emits `DEPART…ARRIVE`.
- `POST /routes/plan` returns `steps`; app maps them to domain `Route.steps`.
- New backend tests pass; full backend + app suites stay green.
- No visible app change; `backend/.env` / `local.properties` never committed; `git status` clean.

## Notes for later (next sub-projects)

- **5b** — continuous location stream + following camera (consumes nothing from steps yet).
- **5c** — nav mode: maneuver banner, distance-to-next-turn, advance steps, ETA, End button (consumes `Route.steps`).
- **5d** — voice announcements via Step 4b `VoiceOutput`.
- **5e** — off-route detection + rerouting.
- **Copilot ↔ nav state** — give the copilot the current/next step as context (5c/5d).
