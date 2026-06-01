# Copilot Maps — Step 2b (Traffic Colors) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Color the selected route's polyline by per-segment traffic (green/amber/red) using Google's `speedReadingIntervals`, with the stub producing fake intervals so it works offline.

**Architecture:** Additive contract change — each `Route` gains `traffic_intervals` (index ranges + speed). Backend returns the data; the app slices the route's decoded points with a pure `buildTrafficSegments` function and draws one colored `Polyline` per segment for the selected route only.

**Tech Stack:** Existing Step 2 stack. No new dependencies.

---

## Conventions (unchanged from Step 2)

- Backend at `backend/`; run with `.\.venv\Scripts\python.exe -m pytest` / `... -m uvicorn app.main:app --port 8000`.
- Gradle from CLI: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; cd android; .\gradlew.bat <task>`.
- App package `com.virtueson.copilotmaps`.

## File Structure

```
backend/app/
  models.py                # MODIFIED: + TrafficInterval, Route.traffic_intervals
  planners/stub.py         # MODIFIED: densify points + emit intervals
  planners/google.py       # MODIFIED: extraComputations + field mask + parse intervals
backend/tests/
  test_stub_planner.py     # MODIFIED: assert traffic_intervals

android/app/src/main/java/com/virtueson/copilotmaps/
  network/Dtos.kt          # MODIFIED: + TrafficIntervalDto, RouteDto.trafficIntervals
  data/Traffic.kt          # NEW: TrafficSpeed enum + TrafficInterval (domain)
  data/Route.kt            # MODIFIED: Route.trafficIntervals
  data/TrafficSegments.kt  # NEW: ColoredSegment + buildTrafficSegments
  data/RoutesRepository.kt # MODIFIED: map intervals + speed string -> enum
  ui/map/MapScreen.kt      # MODIFIED: selected route drawn as colored segments
android/app/src/test/java/com/virtueson/copilotmaps/data/
  TrafficSegmentsTest.kt   # NEW: unit tests for buildTrafficSegments
```

---

# PHASE 1 — Backend

### Task 1: Extend the contract (`models.py`)

**Files:**
- Modify: `backend/app/models.py`

- [ ] **Step 1: Add `TrafficInterval` and the `traffic_intervals` field**

Replace the whole file with:
```python
from pydantic import BaseModel


class LatLng(BaseModel):
    lat: float
    lng: float


class TrafficInterval(BaseModel):
    start_index: int
    end_index: int
    speed: str


class Route(BaseModel):
    id: str
    summary: str
    distance_meters: int
    duration_seconds: int
    polyline: str
    traffic_intervals: list[TrafficInterval] = []


class RoutePlanRequest(BaseModel):
    origin: LatLng
    destination: LatLng


class RoutePlanResponse(BaseModel):
    routes: list[Route]
```

- [ ] **Step 2: Verify import**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"
.\.venv\Scripts\python.exe -c "from app.models import TrafficInterval, Route; print('ok')"
```
Expected: `ok`.

- [ ] **Step 3: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add backend/app/models.py
git commit -m "feat(backend): add traffic_intervals to Route contract"
```

---

### Task 2: Stub emits fake traffic intervals (TDD)

**Files:**
- Modify: `backend/tests/test_stub_planner.py`, `backend/app/planners/stub.py`

- [ ] **Step 1: Add the failing test**

Append to `backend/tests/test_stub_planner.py`:
```python
import polyline as polyline_lib


def test_stub_routes_have_traffic_intervals():
    origin = LatLng(lat=1.2966, lng=103.7764)
    dest = LatLng(lat=1.3521, lng=103.8198)

    routes = asyncio.run(StubRoutePlanner().plan(origin, dest))

    for r in routes:
        point_count = len(polyline_lib.decode(r.polyline))
        assert len(r.traffic_intervals) >= 1
        for iv in r.traffic_intervals:
            assert 0 <= iv.start_index < iv.end_index <= point_count - 1
            assert iv.speed in {"NORMAL", "SLOW", "TRAFFIC_JAM"}
```

- [ ] **Step 2: Run it to verify it fails**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"
.\.venv\Scripts\python.exe -m pytest tests/test_stub_planner.py::test_stub_routes_have_traffic_intervals -v
```
Expected: FAIL — `traffic_intervals` is empty (assert `len >= 1`).

- [ ] **Step 3: Update `stub.py`**

Replace `backend/app/planners/stub.py` with:
```python
import math

import polyline

from app.models import LatLng, Route, TrafficInterval

# ~40 km/h average urban speed, in metres/second.
_ASSUMED_SPEED_MPS = 11.0


def _haversine_m(a: LatLng, b: LatLng) -> float:
    radius = 6_371_000.0
    phi1, phi2 = math.radians(a.lat), math.radians(b.lat)
    d_phi = math.radians(b.lat - a.lat)
    d_lambda = math.radians(b.lng - a.lng)
    h = math.sin(d_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2
    return 2 * radius * math.asin(math.sqrt(h))


def _offset_midpoint(a: LatLng, b: LatLng, perp_offset_deg: float) -> LatLng:
    """Midpoint of a->b, shifted perpendicular to the line so alternates look distinct."""
    mid_lat = (a.lat + b.lat) / 2
    mid_lng = (a.lng + b.lng) / 2
    d_lat = b.lat - a.lat
    d_lng = b.lng - a.lng
    norm = math.hypot(d_lat, d_lng) or 1.0
    perp_lat = -d_lng / norm
    perp_lng = d_lat / norm
    return LatLng(lat=mid_lat + perp_lat * perp_offset_deg, lng=mid_lng + perp_lng * perp_offset_deg)


def _densify(a: LatLng, mid: LatLng, b: LatLng) -> list[LatLng]:
    """7 points: a at index 0, mid at 3, b at 6 (so 3 clean traffic intervals fit)."""
    points: list[LatLng] = []
    for i in range(3):
        t = i / 3
        points.append(LatLng(lat=a.lat + (mid.lat - a.lat) * t, lng=a.lng + (mid.lng - a.lng) * t))
    for i in range(3):
        t = i / 3
        points.append(LatLng(lat=mid.lat + (b.lat - mid.lat) * t, lng=mid.lng + (b.lng - mid.lng) * t))
    points.append(b)
    return points


class StubRoutePlanner:
    """Offline planner: 3 fake routes that connect origin to destination, with fake traffic."""

    _VARIANTS = [
        (0.0, "Direct route"),
        (0.012, "Scenic detour"),
        (-0.018, "Alternate way"),
    ]

    async def plan(self, origin: LatLng, destination: LatLng) -> list[Route]:
        routes: list[Route] = []
        for i, (offset, summary) in enumerate(self._VARIANTS):
            mid = _offset_midpoint(origin, destination, offset)
            points = _densify(origin, mid, destination)
            last = len(points) - 1  # 6
            encoded = polyline.encode([(p.lat, p.lng) for p in points])
            intervals = [
                TrafficInterval(start_index=0, end_index=2, speed="NORMAL"),
                TrafficInterval(start_index=2, end_index=4, speed="SLOW"),
                TrafficInterval(start_index=4, end_index=last, speed="TRAFFIC_JAM"),
            ]
            leg_m = _haversine_m(origin, mid) + _haversine_m(mid, destination)
            routes.append(
                Route(
                    id=f"route-{i}",
                    summary=summary,
                    distance_meters=int(leg_m),
                    duration_seconds=int(leg_m / _ASSUMED_SPEED_MPS),
                    polyline=encoded,
                    traffic_intervals=intervals,
                )
            )
        return routes
```

- [ ] **Step 4: Run the full backend suite to verify it passes**

```powershell
.\.venv\Scripts\python.exe -m pytest -q
```
Expected: all pass (including the new interval test and the existing `test_plan_returns_three_routes`).

- [ ] **Step 5: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add backend/app/planners/stub.py backend/tests/test_stub_planner.py
git commit -m "feat(backend): stub emits fake traffic intervals over densified route"
```

---

### Task 3: Google planner returns real traffic intervals

**Files:**
- Modify: `backend/app/planners/google.py`

- [ ] **Step 1: Update `google.py`**

Replace `backend/app/planners/google.py` with:
```python
import httpx

from app.models import LatLng, Route, TrafficInterval

_COMPUTE_ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
_FIELD_MASK = (
    "routes.polyline.encodedPolyline,routes.duration,"
    "routes.distanceMeters,routes.description,"
    "routes.travelAdvisory.speedReadingIntervals"
)


def _parse_intervals(item: dict) -> list[TrafficInterval]:
    raw = item.get("travelAdvisory", {}).get("speedReadingIntervals", [])
    intervals: list[TrafficInterval] = []
    for iv in raw:
        intervals.append(
            TrafficInterval(
                # startPolylinePointIndex is omitted by the API when it is 0.
                start_index=int(iv.get("startPolylinePointIndex", 0)),
                end_index=int(iv.get("endPolylinePointIndex", 0)),
                speed=iv.get("speed", "SPEED_UNSPECIFIED"),
            )
        )
    return intervals


class GoogleRoutePlanner:
    def __init__(self, api_key: str):
        self._api_key = api_key

    async def plan(self, origin: LatLng, destination: LatLng) -> list[Route]:
        body = {
            "origin": {"location": {"latLng": {"latitude": origin.lat, "longitude": origin.lng}}},
            "destination": {"location": {"latLng": {"latitude": destination.lat, "longitude": destination.lng}}},
            "travelMode": "DRIVE",
            "routingPreference": "TRAFFIC_AWARE",
            "computeAlternativeRoutes": True,
            "extraComputations": ["TRAFFIC_ON_POLYLINE"],
        }
        headers = {
            "Content-Type": "application/json",
            "X-Goog-Api-Key": self._api_key,
            "X-Goog-FieldMask": _FIELD_MASK,
        }
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.post(_COMPUTE_ROUTES_URL, json=body, headers=headers)
        resp.raise_for_status()

        routes: list[Route] = []
        for i, item in enumerate(resp.json().get("routes", [])[:3]):
            duration = item.get("duration", "0s")
            seconds = int(duration[:-1]) if duration.endswith("s") else 0
            routes.append(
                Route(
                    id=f"route-{i}",
                    summary=item.get("description") or f"Route {i + 1}",
                    distance_meters=int(item.get("distanceMeters", 0)),
                    duration_seconds=seconds,
                    polyline=item["polyline"]["encodedPolyline"],
                    traffic_intervals=_parse_intervals(item),
                )
            )
        return routes
```

- [ ] **Step 2: Confirm the suite still passes (stub path unaffected)**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"
.\.venv\Scripts\python.exe -m pytest -q
```
Expected: all pass.

- [ ] **Step 3: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add backend/app/planners/google.py
git commit -m "feat(backend): request + parse Google speedReadingIntervals"
```

---

# PHASE 2 — App

### Task 4: DTOs

**Files:**
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/network/Dtos.kt`

- [ ] **Step 1: Add `TrafficIntervalDto` and the `RouteDto` field**

Replace `Dtos.kt` with:
```kotlin
package com.virtueson.copilotmaps.network

import com.squareup.moshi.Json

data class LatLngDto(
    val lat: Double,
    val lng: Double,
)

data class RoutePlanRequestDto(
    val origin: LatLngDto,
    val destination: LatLngDto,
)

data class TrafficIntervalDto(
    @Json(name = "start_index") val startIndex: Int,
    @Json(name = "end_index") val endIndex: Int,
    val speed: String,
)

data class RouteDto(
    val id: String,
    val summary: String,
    @Json(name = "distance_meters") val distanceMeters: Int,
    @Json(name = "duration_seconds") val durationSeconds: Int,
    val polyline: String,
    @Json(name = "traffic_intervals") val trafficIntervals: List<TrafficIntervalDto> = emptyList(),
)

data class RoutePlanResponseDto(
    val routes: List<RouteDto>,
)
```

- [ ] **Step 2: Compile**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add android/app/src/main/java/com/virtueson/copilotmaps/network/Dtos.kt
git commit -m "feat(app): add traffic_intervals DTO field"
```

---

### Task 5: Domain types + repository mapping

**Files:**
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/data/Traffic.kt`
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/data/Route.kt`, `android/app/src/main/java/com/virtueson/copilotmaps/data/RoutesRepository.kt`

- [ ] **Step 1: Create `Traffic.kt`**

```kotlin
package com.virtueson.copilotmaps.data

enum class TrafficSpeed { NORMAL, SLOW, JAM, UNKNOWN }

data class TrafficInterval(
    val startIndex: Int,
    val endIndex: Int,
    val speed: TrafficSpeed,
)
```

- [ ] **Step 2: Add `trafficIntervals` to `Route` (with a default so existing constructions still compile)**

Replace `Route.kt` with:
```kotlin
package com.virtueson.copilotmaps.data

/** A drawable route: geometry already decoded into points. */
data class Route(
    val id: String,
    val summary: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val points: List<GeoPoint>,
    val trafficIntervals: List<TrafficInterval> = emptyList(),
)

sealed interface RoutesResult {
    data class Success(val routes: List<Route>) : RoutesResult
    data class Failure(val reason: String) : RoutesResult
}
```

- [ ] **Step 3: Map intervals + speed in `RoutesRepository.kt`**

Replace `RoutesRepository.kt` with:
```kotlin
package com.virtueson.copilotmaps.data

import com.google.maps.android.PolyUtil
import com.virtueson.copilotmaps.network.LatLngDto
import com.virtueson.copilotmaps.network.RoutePlanRequestDto
import com.virtueson.copilotmaps.network.RoutesApi

interface RoutesRepository {
    suspend fun planRoutes(origin: GeoPoint, destination: GeoPoint): RoutesResult
}

private fun String.toTrafficSpeed(): TrafficSpeed = when (this) {
    "NORMAL" -> TrafficSpeed.NORMAL
    "SLOW" -> TrafficSpeed.SLOW
    "TRAFFIC_JAM" -> TrafficSpeed.JAM
    else -> TrafficSpeed.UNKNOWN
}

class DefaultRoutesRepository(
    private val api: RoutesApi,
) : RoutesRepository {
    override suspend fun planRoutes(origin: GeoPoint, destination: GeoPoint): RoutesResult {
        return try {
            val response = api.planRoutes(
                RoutePlanRequestDto(
                    origin = LatLngDto(origin.lat, origin.lng),
                    destination = LatLngDto(destination.lat, destination.lng),
                )
            )
            val routes = response.routes.map { dto ->
                Route(
                    id = dto.id,
                    summary = dto.summary,
                    distanceMeters = dto.distanceMeters,
                    durationSeconds = dto.durationSeconds,
                    points = PolyUtil.decode(dto.polyline).map { GeoPoint(it.latitude, it.longitude) },
                    trafficIntervals = dto.trafficIntervals.map {
                        TrafficInterval(it.startIndex, it.endIndex, it.speed.toTrafficSpeed())
                    },
                )
            }
            if (routes.isEmpty()) {
                RoutesResult.Failure("No route found for that destination.")
            } else {
                RoutesResult.Success(routes)
            }
        } catch (e: Exception) {
            RoutesResult.Failure("Can't reach the server — is the backend running and adb reverse set?")
        }
    }
}
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
git add android/app/src/main/java/com/virtueson/copilotmaps/data/Traffic.kt android/app/src/main/java/com/virtueson/copilotmaps/data/Route.kt android/app/src/main/java/com/virtueson/copilotmaps/data/RoutesRepository.kt
git commit -m "feat(app): traffic domain types + repository mapping"
```

---

### Task 6: `buildTrafficSegments` (TDD)

**Files:**
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/data/TrafficSegments.kt`
- Test: `android/app/src/test/java/com/virtueson/copilotmaps/data/TrafficSegmentsTest.kt`

- [ ] **Step 1: Write the failing test**

`android/app/src/test/java/com/virtueson/copilotmaps/data/TrafficSegmentsTest.kt`:
```kotlin
package com.virtueson.copilotmaps.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficSegmentsTest {

    private fun points(n: Int): List<GeoPoint> =
        (0 until n).map { GeoPoint(it.toDouble(), it.toDouble()) }

    @Test
    fun `splits into inclusive segments that share boundary points`() {
        val pts = points(7) // indices 0..6
        val intervals = listOf(
            TrafficInterval(0, 2, TrafficSpeed.NORMAL),
            TrafficInterval(2, 4, TrafficSpeed.SLOW),
            TrafficInterval(4, 6, TrafficSpeed.JAM),
        )

        val segments = buildTrafficSegments(pts, intervals)

        assertEquals(3, segments.size)
        assertEquals(listOf(pts[0], pts[1], pts[2]), segments[0].points)
        assertEquals(TrafficSpeed.NORMAL, segments[0].speed)
        assertEquals(listOf(pts[2], pts[3], pts[4]), segments[1].points)
        assertEquals(TrafficSpeed.SLOW, segments[1].speed)
        assertEquals(listOf(pts[4], pts[5], pts[6]), segments[2].points)
        assertEquals(TrafficSpeed.JAM, segments[2].speed)
        // boundary points are shared so the line is continuous
        assertEquals(segments[0].points.last(), segments[1].points.first())
    }

    @Test
    fun `no intervals yields one UNKNOWN segment spanning all points`() {
        val pts = points(4)

        val segments = buildTrafficSegments(pts, emptyList())

        assertEquals(1, segments.size)
        assertEquals(pts, segments[0].points)
        assertEquals(TrafficSpeed.UNKNOWN, segments[0].speed)
    }

    @Test
    fun `out-of-range indices are clamped and degenerate intervals skipped`() {
        val pts = points(4) // indices 0..3
        val intervals = listOf(
            TrafficInterval(-5, 2, TrafficSpeed.NORMAL), // start clamps to 0
            TrafficInterval(2, 99, TrafficSpeed.JAM),    // end clamps to 3
            TrafficInterval(3, 3, TrafficSpeed.SLOW),    // degenerate -> skipped
        )

        val segments = buildTrafficSegments(pts, intervals)

        assertEquals(2, segments.size)
        assertEquals(listOf(pts[0], pts[1], pts[2]), segments[0].points)
        assertEquals(listOf(pts[2], pts[3]), segments[1].points)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat :app:testDebugUnitTest --tests "com.virtueson.copilotmaps.data.TrafficSegmentsTest"
```
Expected: FAIL — `buildTrafficSegments` unresolved.

- [ ] **Step 3: Write `TrafficSegments.kt`**

```kotlin
package com.virtueson.copilotmaps.data

data class ColoredSegment(
    val points: List<GeoPoint>,
    val speed: TrafficSpeed,
)

/**
 * Slices [points] into colored segments by [intervals], inclusive on both ends so
 * adjacent segments share a boundary point (continuous line). Indices are clamped;
 * single-point intervals are skipped. With no usable intervals, returns one UNKNOWN
 * segment spanning all points so the route still draws.
 */
fun buildTrafficSegments(
    points: List<GeoPoint>,
    intervals: List<TrafficInterval>,
): List<ColoredSegment> {
    if (points.size < 2) return emptyList()
    val lastIndex = points.lastIndex
    val segments = mutableListOf<ColoredSegment>()
    for (interval in intervals) {
        val start = interval.startIndex.coerceIn(0, lastIndex)
        val end = interval.endIndex.coerceIn(start, lastIndex)
        if (end == start) continue
        segments.add(ColoredSegment(points.subList(start, end + 1).toList(), interval.speed))
    }
    if (segments.isEmpty()) {
        return listOf(ColoredSegment(points, TrafficSpeed.UNKNOWN))
    }
    return segments
}
```

- [ ] **Step 4: Run the test to verify it passes**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.virtueson.copilotmaps.data.TrafficSegmentsTest"
```
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add android/app/src/main/java/com/virtueson/copilotmaps/data/TrafficSegments.kt android/app/src/test/java/com/virtueson/copilotmaps/data/TrafficSegmentsTest.kt
git commit -m "feat(app): buildTrafficSegments with unit tests"
```

---

### Task 7: Render colored segments for the selected route + on-device verify

**Files:**
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt`

- [ ] **Step 1: Replace the polyline-drawing block + add a color helper**

In `MapScreen.kt`, find the block inside the `GoogleMap { ... }` content that currently draws routes:
```kotlin
            if (routesState is RoutesState.Loaded) {
                routesState.routes.forEach { route ->
                    val selected = route.id == routesState.selectedId
                    Polyline(
                        points = route.points.map { LatLng(it.lat, it.lng) },
                        color = if (selected) Color(0xFF1A73E8) else Color(0xFF9AA0A6),
                        width = if (selected) 18f else 9f,
                        zIndex = if (selected) 2f else 1f,
                        clickable = true,
                        onClick = { onSelect(route.id) },
                    )
                }
            }
```
Replace it with:
```kotlin
            if (routesState is RoutesState.Loaded) {
                routesState.routes.forEach { route ->
                    val selected = route.id == routesState.selectedId
                    if (selected) {
                        buildTrafficSegments(route.points, route.trafficIntervals).forEach { segment ->
                            Polyline(
                                points = segment.points.map { LatLng(it.lat, it.lng) },
                                color = trafficColor(segment.speed),
                                width = 18f,
                                zIndex = 2f,
                                clickable = true,
                                onClick = { onSelect(route.id) },
                            )
                        }
                    } else {
                        Polyline(
                            points = route.points.map { LatLng(it.lat, it.lng) },
                            color = Color(0xFF9AA0A6),
                            width = 9f,
                            zIndex = 1f,
                            clickable = true,
                            onClick = { onSelect(route.id) },
                        )
                    }
                }
            }
```

- [ ] **Step 2: Add the color helper + imports**

Add these imports near the other `com.virtueson.copilotmaps.data` imports at the top of `MapScreen.kt`:
```kotlin
import com.virtueson.copilotmaps.data.TrafficSpeed
import com.virtueson.copilotmaps.data.buildTrafficSegments
```

Add this private function next to `formatDistance` / `formatDuration` at the bottom of the file:
```kotlin
private fun trafficColor(speed: TrafficSpeed): Color = when (speed) {
    TrafficSpeed.NORMAL -> Color(0xFF34A853)   // green
    TrafficSpeed.SLOW -> Color(0xFFFBBC04)     // amber
    TrafficSpeed.JAM -> Color(0xFFEA4335)      // red
    TrafficSpeed.UNKNOWN -> Color(0xFF1A73E8)  // blue (fallback = old selected color)
}
```
(`Color` is already imported as `androidx.compose.ui.graphics.Color`.)

- [ ] **Step 3: Compile**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: On-device verification**

Ensure the backend is running and `adb reverse tcp:8000 tcp:8000` is set. Run the app (▶).
- With the **stub** backend (`ROUTE_PLANNER=stub`): long-press a destination → the selected (thick) route shows **green → amber → red** segments; alternates are thin gray. Tap another card → colors move to the newly selected route, previous goes gray.
- With the **Google** backend (`ROUTE_PLANNER=google`): the selected route's colors reflect **real** traffic (mostly green off-peak; amber/red where congested).

- [ ] **Step 5: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt
git commit -m "feat(app): color selected route by per-segment traffic"
```

---

## Definition of Done (verify all)

- [ ] Backend stub + Google planner return `traffic_intervals`.
- [ ] Selected route renders green/amber/red segments; alternates stay thin gray.
- [ ] Switching selection moves the colors; a route with no intervals still draws (blue).
- [ ] Backend `pytest` and app `TrafficSegmentsTest` pass.
- [ ] `git status` clean; `.env` / `local.properties` never committed.

---

## Notes for later

- A small traffic legend (green/amber/red key) could be added to the UI later if useful.
- Step 5 (live nav) can re-poll `/routes/plan` to refresh colors as traffic changes.
```
