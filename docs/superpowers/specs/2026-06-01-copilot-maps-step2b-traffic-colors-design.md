# Copilot Maps — Step 2b: Traffic Colors — Design Spec

**Date:** 2026-06-01
**Status:** Approved (design), pending implementation plan
**Author:** Russell (with Claude)

---

## Context

Step 2 (Routing) is complete: the app draws up to 3 alternate routes (polyline +
ETA + distance), long-press sets a destination, tap selects a route. Step 2 
deliberately deferred **per-segment traffic coloring** to this follow-up.

**This spec covers Step 2b: coloring the selected route by live traffic.** It is a
small, self-contained extension of Step 2 — no new screens, no new endpoint.

---

## Goal & Scope

**Goal:** Color the **selected** route's polyline by per-segment traffic
(green = normal, amber = slow, red = jam), using Google's
`travelAdvisory.speedReadingIntervals`.

**Scope decisions (agreed):**

- **Selected route only** gets traffic colors. Unselected alternates stay thin gray
  (unchanged). Rationale: coloring all overlapping routes is visually noisy; users
  care about traffic on the route they'd take. Selection reads as thick+colored vs.
  thin+gray.
- **Data-oriented contract** (Approach 1): the backend returns traffic *intervals*
  (index ranges + speed); the **app** slices the route's points and draws colored
  segments. Backend stays data-only; app owns presentation (colors).

**Definition of Done:**

1. `GoogleRoutePlanner` requests and returns per-route traffic intervals.
2. `StubRoutePlanner` returns fake intervals so coloring works offline.
3. On the phone: the selected route shows green/amber/red segments; switching
   selection moves the colors to the newly selected route.
4. A route with no intervals still draws (single fallback-colored line) — no crash.
5. Backend pytest and the app `buildTrafficSegments` unit tests pass.

---

## Verified Google API facts

`speedReadingIntervals` are only populated when **all** of these hold:

- `routingPreference` is `TRAFFIC_AWARE` or `TRAFFIC_AWARE_OPTIMAL` — we already
  send `TRAFFIC_AWARE`. ✓
- the field mask includes `routes.travelAdvisory.speedReadingIntervals` — **add**.
- the request body includes `extraComputations: ["TRAFFIC_ON_POLYLINE"]` — **add**.

Each interval = `{startPolylinePointIndex, endPolylinePointIndex, speed}` where
`speed` ∈ `NORMAL | SLOW | TRAFFIC_JAM` (and possibly `SPEED_UNSPECIFIED`). Indices
point into the route's single polyline. Google does **not** return pre-sliced
segments — the slicing is the app's job.

Sources:
- https://developers.google.com/maps/documentation/routes_preferred/traffic_on_polylines
- https://developers.google.com/maps/documentation/routes/reference/rest/v2/RouteTravelAdvisory

---

## Contract Extension (additive, backward-compatible)

`backend/app/models.py`:

```python
class TrafficInterval(BaseModel):
    start_index: int
    end_index: int
    speed: str          # "NORMAL" | "SLOW" | "TRAFFIC_JAM" | "SPEED_UNSPECIFIED"

class Route(BaseModel):
    id: str
    summary: str
    distance_meters: int
    duration_seconds: int
    polyline: str
    traffic_intervals: list[TrafficInterval] = []   # NEW, default empty
```

`traffic_intervals` defaults to an empty list, so existing behavior and tests are
unaffected when absent.

---

## Backend Design

**`GoogleRoutePlanner` (`planners/google.py`):**
- Add `"extraComputations": ["TRAFFIC_ON_POLYLINE"]` to the request body.
- Add `routes.travelAdvisory.speedReadingIntervals` to the field mask.
- For each route, map `travelAdvisory.speedReadingIntervals[]` →
  `list[TrafficInterval]` (`startPolylinePointIndex` → `start_index`,
  `endPolylinePointIndex` → `end_index`, `speed` → `speed`). Missing/absent →
  empty list.

**`StubRoutePlanner` (`planners/stub.py`):**
- Densify each fake route from 3 points (origin, mid, dest) to ~6 by interpolating
  along origin→mid and mid→dest.
- Emit 3 contiguous intervals across those points with speeds `NORMAL`, `SLOW`,
  `TRAFFIC_JAM` — yielding a green→amber→red line to test offline.

---

## App Design

**Data layer:**
- DTOs (`network/Dtos.kt`): add `TrafficIntervalDto(start_index, end_index, speed)`
  and `traffic_intervals: List<TrafficIntervalDto> = emptyList()` on `RouteDto`.
- Domain (`data/`):
  ```kotlin
  enum class TrafficSpeed { NORMAL, SLOW, JAM, UNKNOWN }
  data class TrafficInterval(val startIndex: Int, val endIndex: Int, val speed: TrafficSpeed)
  ```
  `Route` gains `trafficIntervals: List<TrafficInterval>`.
- `RoutesRepository` maps DTO → domain; speed string → enum (`"NORMAL"`→NORMAL,
  `"SLOW"`→SLOW, `"TRAFFIC_JAM"`→JAM, anything else → UNKNOWN).

**The testable core (`data/TrafficSegments.kt`):**
```kotlin
data class ColoredSegment(val points: List<GeoPoint>, val speed: TrafficSpeed)

fun buildTrafficSegments(
    points: List<GeoPoint>,
    intervals: List<TrafficInterval>,
): List<ColoredSegment>
```
Behavior:
- For each interval, segment points = `points.subList(start, end + 1)` (inclusive on
  both ends). Adjacent intervals share their boundary point, which stitches the
  colored lines together with no gap.
- Clamp `start` into `[0, lastIndex]` and `end` into `[start, lastIndex]`.
- Skip degenerate intervals where `start == end` (can't draw a 1-point line).
- **No intervals (or none usable) → one `ColoredSegment(points, UNKNOWN)`** spanning
  the whole route, so the line always draws.

**Rendering (`ui/map/MapScreen.kt`):**
- **Selected route:** draw one `Polyline` per `ColoredSegment` from
  `buildTrafficSegments(route.points, route.trafficIntervals)`, colored by speed:
  `NORMAL`→green `#34A853`, `SLOW`→amber `#FBBC04`, `JAM`→red `#EA4335`,
  `UNKNOWN`→blue `#1A73E8` (today's selected-blue fallback). Thick (width ~18).
- **Unselected routes:** unchanged — one thin gray `Polyline` (width ~9).
- Selection behavior unchanged: tapping a card/line re-selects; colors follow the
  selected route, the deselected one returns to thin gray.

---

## Error Handling

- Route with empty `traffic_intervals` → single blue (`UNKNOWN`) line via the
  fallback in `buildTrafficSegments`. No crash, still drawn.
- Out-of-range / overlapping indices from the API → clamped; degenerate intervals
  skipped.
- Backend/stub never need to fail for this feature; existing network error handling
  (Step 2) is unchanged.

---

## Testing

**App unit tests (`buildTrafficSegments`, pure JVM):**
- Multi-interval split → correct inclusive segments with shared boundary points and
  correct speeds.
- Empty intervals → exactly one `UNKNOWN` segment spanning all points.
- Out-of-range indices → clamped, no exception.

**Backend pytest:**
- Stub routes come back with non-empty `traffic_intervals`; each interval has
  `0 <= start_index < end_index <= last point index` and a valid speed string.

**Manual on-device:**
- Select a route → green/amber/red segments appear along the thick line.
- Tap another card → colors move to the newly selected route; previous goes thin
  gray.

---

## Out of Scope

- Coloring unselected alternates (selected-only by decision).
- A traffic legend / UI chrome beyond the colored line.
- Re-fetching on selection (intervals already arrive for all routes in the existing
  `/routes/plan` response).
- Live re-coloring as traffic changes over time (that's live-nav territory, Step 5).

---

## Next Step

Proceed to the **writing-plans** skill for the implementation plan.
