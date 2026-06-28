# Step 5a — Turn-by-Turn Route Data — Design

**Date:** 2026-06-27
**Status:** Approved (brainstorm complete; ready for implementation plan)
**Part of:** Step 5 (Live Navigation), build-our-own approach. **Project goal: personal use only** (not Play Store).
**Depends on:** existing routing (`/routes/plan`, `GoogleRoutePlanner`, `StubRoutePlanner`, app `RoutesRepository`).

## Goal

Add **turn-by-turn steps** to the route data so later sub-projects (5b–5e) can show a
maneuver banner, announce turns by voice, and track progress. This sub-project adds data
only — **nothing visible changes yet**. Steps default to empty, so all existing behavior
(route drawing, ETA cards, traffic colors, copilot context) is untouched.

## Scope decision (approved)

**Minimal per-step data:** instruction text, maneuver type, step distance, and the turn's
location. No per-step geometry and no "then" preview (deferred / YAGNI).

## Data contract

### Backend (`backend/app/models.py`)

```python
class RouteStep(BaseModel):
    instruction: str          # "Turn left onto Jl. Sudirman"
    maneuver: str             # "TURN_LEFT", "MERGE", "DEPART", "ARRIVE", "" if none
    distance_meters: int      # length of this step
    location: LatLng          # where this step's maneuver happens (step start)

class Route(BaseModel):
    id: str
    summary: str
    distance_meters: int
    duration_seconds: int
    polyline: str
    traffic_intervals: list[TrafficInterval] = []
    steps: list[RouteStep] = []      # NEW — ordered; empty when unknown
```

### App

- `network/Dtos.kt`: `RouteStepDto(instruction, maneuver, @Json("distance_meters") distanceMeters, location: LatLngDto)`;
  add `@Json("steps") steps: List<RouteStepDto> = emptyList()` to `RouteDto`.
- `data/Route.kt`: `RouteStep(instruction: String, maneuver: String, distanceMeters: Int, location: GeoPoint)`;
  add `steps: List<RouteStep> = emptyList()` to domain `Route`.
- `data/RoutesRepository.kt`: map `dto.steps` → domain, `location` → `GeoPoint(lat, lng)`.

`steps` defaults to empty everywhere, so the change is backward-compatible.

## Sources

### Google (`backend/app/planners/google.py`)

Extend `_FIELD_MASK` with:
```
routes.legs.steps.navigationInstruction,
routes.legs.steps.distanceMeters,
routes.legs.steps.startLocation
```
Add `_parse_steps(item)` (mirrors `_parse_intervals`): iterate `item["legs"][*]["steps"][*]`
(flattened in order), building `RouteStep`:
- `instruction` ← `step["navigationInstruction"]["instructions"]` (default `""`)
- `maneuver` ← `step["navigationInstruction"]["maneuver"]` (default `""`)
- `distance_meters` ← `int(step.get("distanceMeters", 0))`
- `location` ← `LatLng(step["startLocation"]["latLng"]["latitude"], ...["longitude"])`

Missing `navigationInstruction` (some steps omit it) → instruction/maneuver default to `""`.
`travelMode` is already `DRIVE`; navigation instructions come with steps at no extra cost tier.

### Stub (`backend/app/planners/stub.py`)

Emit believable fake steps per route so nav is fully developable offline:
- `DEPART` — "Head toward destination", `location` = origin (densified point 0).
- a mid turn — `TURN_RIGHT` (or `TURN_LEFT`) "Turn onto the main road", `location` = midpoint (point 3).
- `ARRIVE` — "Arrive at destination", `location` = destination (last point).

Distances split from the existing per-route length. Keeps the stub self-consistent with its
own polyline/points.

## Error handling

- Any missing/partial step field defaults (`""`, `0`) — never raises.
- A route with no `legs`/`steps` yields `steps = []` (older behavior preserved).
- App mapping tolerates empty `steps` (default).

## Testing (backend, deterministic — no live API)

- `test_parse_steps`: feed a sample Google-shaped dict (one leg, two steps, second missing
  `navigationInstruction`) → assert instruction/maneuver/distance/location of the first, and
  `""`/`""` defaults for the second.
- `test_stub_emits_steps`: stub planner output has non-empty `steps`; first `maneuver ==
  "DEPART"`, last `maneuver == "ARRIVE"`.
- `test_plan_response_includes_steps`: `POST /routes/plan` (stub via dependency override)
  returns `steps` for each route with the expected keys.

App side: no new logic in 5a (pure DTO→domain mapping, same shape as existing
`trafficIntervals`). Exercised on device starting in 5b/5c.

## Out of scope (later sub-projects)

- Consuming steps in the UI / following camera (5b, 5c).
- Voice announcements of maneuvers (5d, reuses Step 4b TTS).
- Off-route detection + rerouting (5e).
- Per-step polyline geometry and "then" preview (deferred unless 5c needs them).
- **Copilot ↔ live-nav state** ("what's my next turn?") — belongs to 5c/5d.

## Definition of Done

- `Route` carries `steps`; Google planner parses real steps; stub emits fake steps.
- `POST /routes/plan` returns `steps`; app maps them to domain `Route.steps`.
- New backend tests pass; existing backend + app tests stay green.
- No visible app change; no secrets committed; `git status` clean.
