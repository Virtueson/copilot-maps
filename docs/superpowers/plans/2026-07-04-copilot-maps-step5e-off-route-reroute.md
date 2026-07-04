# Step 5E — Off-route Detection + Auto-reroute Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** During live navigation, detect when the driver has left the route and automatically re-plan from the current position to the same destination, resuming guidance on the new route with a spoken "Rerouting" cue.

**Architecture:** A pure `distanceToRouteMeters` measures how far the location is from the route line. `NavViewModel` counts consecutive off-route fixes and, past a threshold, speaks "Rerouting" and emits a `rerouteRequest` event (destination only) — staying free of networking. `MapScreen` collects that event, calls `RouteViewModel.planRoutes` (which updates the map's drawn polyline), and feeds the resulting route back via `NavViewModel.onRerouteResult`, which adopts it (or announces failure and keeps the old route with a backoff).

**Tech Stack:** Kotlin, Jetpack Compose, AndroidX Lifecycle ViewModel, kotlinx-coroutines (`SharedFlow`), JUnit + kotlinx-coroutines-test.

## Global Constraints

- minSdk 24, targetSdk 36; Kotlin/Compose project under `android/`. Package `com.virtueson.copilotmaps`.
- Gradle from CLI needs `JAVA_HOME` set first (`java` not on PATH).
- Off-route threshold **50 m**, **3** consecutive fixes; reroute backoff **~10 s**. Keep as named constants.
- Metric units. Reroute reuses the existing `/routes/plan` (via `RouteViewModel.planRoutes`) — **no backend changes**.
- `NavViewModel` stays free of Android-framework/networking imports (coroutines + data only) so it remains unit-testable. It only *requests* a reroute; the network call + map update happen through `RouteViewModel` in `MapScreen`.
- Do NOT commit `local.properties` or `backend/.env`.

**Gradle test command (all tasks):**
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat testDebugUnitTest --console=plain
```
Single class: append e.g. `--tests "com.virtueson.copilotmaps.data.OffRouteTest"`.

---

## File Structure

- **Create** `android/app/src/main/java/com/virtueson/copilotmaps/data/OffRoute.kt` — pure `distanceToRouteMeters`.
- **Create** `android/app/src/test/java/com/virtueson/copilotmaps/data/OffRouteTest.kt` — its tests.
- **Modify** `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/NavState.kt` — add `rerouting` to `Active`.
- **Modify** `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/NavViewModel.kt` — off-route detection, `rerouteRequest`, `onRerouteResult`.
- **Modify** `android/app/src/test/java/com/virtueson/copilotmaps/ui/map/NavViewModelTest.kt` — reroute tests.
- **Modify** `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt` — wire reroute + "Rerouting…" banner.

---

## Task 1: Pure `distanceToRouteMeters` (`OffRoute.kt`)

**Files:**
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/data/OffRoute.kt`
- Test: `android/app/src/test/java/com/virtueson/copilotmaps/data/OffRouteTest.kt`

**Interfaces:**
- Consumes: `GeoPoint`, `haversineMeters` (existing in `data/`).
- Produces: `fun distanceToRouteMeters(location: GeoPoint, points: List<GeoPoint>): Double` — shortest distance in metres from `location` to the route polyline (point-to-segment). Empty list → `0.0`; single point → haversine to it.

- [ ] **Step 1: Write the failing tests**

Create `android/app/src/test/java/com/virtueson/copilotmaps/data/OffRouteTest.kt`:

```kotlin
package com.virtueson.copilotmaps.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun pt(lat: Double, lng: Double) = GeoPoint(lat, lng)

// Route heading east along the equator: (0,0) -> (0, 0.010) ~1113 m long.
private val routePts = listOf(pt(0.0, 0.000), pt(0.0, 0.010))

class OffRouteTest {

    @Test
    fun `on the route line is near zero`() {
        assertTrue(distanceToRouteMeters(pt(0.0, 0.005), routePts) < 1.0)
    }

    @Test
    fun `perpendicular offset equals the offset distance`() {
        // 0.0009 deg latitude north of the line ~ 100 m
        assertEquals(100.0, distanceToRouteMeters(pt(0.0009, 0.005), routePts), 5.0)
    }

    @Test
    fun `past the end uses the endpoint distance`() {
        // 0.001 deg lng east of the last vertex ~ 111 m
        assertEquals(111.3, distanceToRouteMeters(pt(0.0, 0.011), routePts), 5.0)
    }

    @Test
    fun `mid segment with sparse vertices stays small`() {
        // far from both vertices but still on the single long segment
        assertTrue(distanceToRouteMeters(pt(0.0, 0.009), routePts) < 1.0)
    }

    @Test
    fun `empty route is zero`() {
        assertEquals(0.0, distanceToRouteMeters(pt(1.0, 1.0), emptyList()), 0.0001)
    }
}
```

- [ ] **Step 2: Run to verify they FAIL**

Run the gradle test command with `--tests "com.virtueson.copilotmaps.data.OffRouteTest"`.
Expected: FAIL — `distanceToRouteMeters` unresolved.

- [ ] **Step 3: Implement**

Create `android/app/src/main/java/com/virtueson/copilotmaps/data/OffRoute.kt`:

```kotlin
package com.virtueson.copilotmaps.data

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Shortest distance in metres from [location] to the route polyline [points],
 * measured against segments (not just vertices) so long straight legs with
 * sparse vertices don't read as off-route. Empty list → 0.0 (treated as
 * on-route); single point → straight-line distance to it. Pure — no Android.
 */
fun distanceToRouteMeters(location: GeoPoint, points: List<GeoPoint>): Double {
    if (points.isEmpty()) return 0.0
    if (points.size == 1) return haversineMeters(location, points[0])

    // Project to a local metre plane centred on `location` (so location = origin).
    val mPerDegLat = 111_320.0
    val mPerDegLng = 111_320.0 * cos(Math.toRadians(location.lat))
    fun px(p: GeoPoint) = (p.lng - location.lng) * mPerDegLng
    fun py(p: GeoPoint) = (p.lat - location.lat) * mPerDegLat

    var min = Double.MAX_VALUE
    for (i in 0 until points.size - 1) {
        val ax = px(points[i]); val ay = py(points[i])
        val bx = px(points[i + 1]); val by = py(points[i + 1])
        val dx = bx - ax; val dy = by - ay
        val segLen2 = dx * dx + dy * dy
        val t = if (segLen2 == 0.0) 0.0 else ((-ax * dx + -ay * dy) / segLen2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        val d = sqrt(cx * cx + cy * cy) // distance from origin (location) to closest point
        if (d < min) min = d
    }
    return min
}
```

- [ ] **Step 4: Run to verify they PASS**

Run the gradle test command with `--tests "com.virtueson.copilotmaps.data.OffRouteTest"`.
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add "android/app/src/main/java/com/virtueson/copilotmaps/data/OffRoute.kt" "android/app/src/test/java/com/virtueson/copilotmaps/data/OffRouteTest.kt"
git commit -m "feat(app): pure distanceToRouteMeters for off-route detection (Step 5e)"
```

---

## Task 2: NavViewModel off-route detection + reroute lifecycle

**Files:**
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/NavState.kt`
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/NavViewModel.kt`
- Test: `android/app/src/test/java/com/virtueson/copilotmaps/ui/map/NavViewModelTest.kt`

**Interfaces:**
- Consumes: `distanceToRouteMeters` (Task 1); existing `Route` (`points`, `steps`, `distanceMeters`, `durationSeconds`), `GeoPoint`, `navProgress`, `nextAnnouncement`, `AnnouncerState`.
- Produces:
  - `NavUiState.Active` gains `val rerouting: Boolean = false`.
  - `val NavViewModel.rerouteRequest: SharedFlow<GeoPoint>` — emits the destination when a reroute is needed.
  - `fun NavViewModel.onRerouteResult(newRoute: Route?)` — adopt the new route (silent, no departure cue) or announce "Rerouting failed" and keep the old one.

- [ ] **Step 1: Add the `rerouting` flag to `NavUiState.Active`**

In `NavState.kt`, add the field (default keeps existing constructions valid):

```kotlin
    data class Active(
        val instruction: String,
        val maneuver: String,
        val distanceToTurnMeters: Int,
        val remainingDistanceMeters: Int,
        val etaEpochSeconds: Long,
        val arrived: Boolean,
        val rerouting: Boolean = false,
    ) : NavUiState
```

- [ ] **Step 2: Write the failing tests**

Add to `NavViewModelTest.kt`. First add imports (if missing) and helpers at the top of the file (keep the existing `step`, `route`, `steps`, and existing tests):

```kotlin
import org.junit.Assert.assertFalse
```
(`ExperimentalCoroutinesApi`, `UnconfinedTestDispatcher`, `runTest`, `launch`, `assertEquals`, `assertTrue`, `GeoPoint`, `Route`, `RouteStep` are already imported from Step 5d.)

Add a route-with-geometry helper next to the existing `route(...)` helper:

```kotlin
// A route whose polyline runs east along the equator, matching `steps` (lng 0.000..0.020).
private val linePts = listOf(GeoPoint(0.0, 0.000), GeoPoint(0.0, 0.020))
private fun routeP(steps: List<RouteStep>, points: List<GeoPoint>) = Route(
    id = "r0", summary = "Test", distanceMeters = 600, durationSeconds = 600,
    points = points, polyline = "", trafficIntervals = emptyList(), steps = steps,
)
```

Add these tests inside `NavViewModelTest`:

```kotlin
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `sustained off-route emits one reroute request and Rerouting`() = runTest {
        val vm = NavViewModel(nowEpochSeconds = { 1000L })
        val lines = mutableListOf<String>()
        val reqs = mutableListOf<GeoPoint>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.announcements.collect { lines.add(it) } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.rerouteRequest.collect { reqs.add(it) } }

        vm.start(routeP(steps, linePts))
        val off = GeoPoint(0.0018, 0.005) // ~200 m north of the line
        vm.onLocation(off); vm.onLocation(off); vm.onLocation(off)

        assertEquals(1, reqs.size)
        assertEquals(GeoPoint(0.0, 0.020), reqs.first()) // destination = last step location
        assertTrue(lines.contains("Rerouting"))
        assertTrue((vm.state.value as NavUiState.Active).rerouting)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a single off-route fix does not trigger a reroute`() = runTest {
        val vm = NavViewModel(nowEpochSeconds = { 1000L })
        val reqs = mutableListOf<GeoPoint>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.rerouteRequest.collect { reqs.add(it) } }

        vm.start(routeP(steps, linePts))
        vm.onLocation(GeoPoint(0.0018, 0.005)) // off (1)
        vm.onLocation(GeoPoint(0.0, 0.005))    // back on route → counter resets
        vm.onLocation(GeoPoint(0.0018, 0.005)) // off (1 again)

        assertTrue(reqs.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `onRerouteResult adopts the new route without a departure cue`() = runTest {
        val vm = NavViewModel(nowEpochSeconds = { 1000L })
        val lines = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.announcements.collect { lines.add(it) } }

        vm.start(routeP(steps, linePts)) // emits "Head"
        lines.clear()
        val newSteps = listOf(
            step("Continue onto Elm", "DEPART", 100, 0.0, 0.000),
            step("Turn right", "TURN_RIGHT", 100, 0.0, 0.010),
            step("Arrive", "ARRIVE", 0, 0.0, 0.020),
        )
        vm.onRerouteResult(routeP(newSteps, linePts))

        assertFalse(lines.contains("Continue onto Elm")) // no departure cue on reroute
        val s = vm.state.value as NavUiState.Active
        assertEquals("Continue onto Elm", s.instruction)  // HUD shows the new route
        assertFalse(s.rerouting)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `onRerouteResult null announces failure, keeps navigating, and backs off`() = runTest {
        val vm = NavViewModel(nowEpochSeconds = { 1000L })
        val lines = mutableListOf<String>()
        val reqs = mutableListOf<GeoPoint>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.announcements.collect { lines.add(it) } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.rerouteRequest.collect { reqs.add(it) } }

        vm.start(routeP(steps, linePts))
        val off = GeoPoint(0.0018, 0.005)
        vm.onLocation(off); vm.onLocation(off); vm.onLocation(off) // first reroute attempt
        assertEquals(1, reqs.size)

        vm.onRerouteResult(null)
        assertTrue(lines.contains("Rerouting failed"))
        assertFalse((vm.state.value as NavUiState.Active).rerouting)

        // still off-route, but within the 10 s backoff (nowEpochSeconds fixed at 1000) → no new request
        vm.onLocation(off); vm.onLocation(off); vm.onLocation(off)
        assertEquals(1, reqs.size)
    }
```

- [ ] **Step 3: Run to verify they FAIL**

Run with `--tests "com.virtueson.copilotmaps.ui.map.NavViewModelTest"`.
Expected: FAIL — `rerouteRequest` / `onRerouteResult` / `rerouting` unresolved.

- [ ] **Step 4: Implement in `NavViewModel.kt`**

Add imports:
```kotlin
import com.virtueson.copilotmaps.data.distanceToRouteMeters
```

Add fields after the announcements block:
```kotlin
    private var offRouteCount = 0
    private var rerouting = false
    private var lastRerouteAttemptSec = 0L
    private val _rerouteRequest = MutableSharedFlow<GeoPoint>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val rerouteRequest: SharedFlow<GeoPoint> = _rerouteRequest.asSharedFlow()

    private val offRouteMeters = 50.0
    private val offRouteFixes = 3
    private val rerouteBackoffSec = 10L
```

In `start(route)`, reset the off-route state (add right after `stepIndex = 0`):
```kotlin
        offRouteCount = 0
        rerouting = false
```

Replace `onLocation(point)` with a version that builds the state with the `rerouting` flag and runs detection at the end:
```kotlin
    fun onLocation(point: GeoPoint) {
        val r = route ?: return
        val p = navProgress(r.steps, point, stepIndex)
        stepIndex = p.stepIndex
        val step = r.steps[p.stepIndex]
        val fraction =
            if (r.distanceMeters > 0) p.remainingDistanceMeters.toDouble() / r.distanceMeters else 0.0
        _state.value = NavUiState.Active(
            instruction = step.instruction,
            maneuver = step.maneuver,
            distanceToTurnMeters = p.distanceToTurnMeters,
            remainingDistanceMeters = p.remainingDistanceMeters,
            etaEpochSeconds = nowEpochSeconds() + (r.durationSeconds * fraction).toLong(),
            arrived = p.arrived,
            rerouting = rerouting,
        )
        announce(p)

        if (!rerouting) {
            val off = distanceToRouteMeters(point, r.points) > offRouteMeters
            offRouteCount = if (off) offRouteCount + 1 else 0
            if (offRouteCount >= offRouteFixes &&
                nowEpochSeconds() - lastRerouteAttemptSec >= rerouteBackoffSec
            ) {
                rerouting = true
                lastRerouteAttemptSec = nowEpochSeconds()
                offRouteCount = 0
                _state.value = (_state.value as? NavUiState.Active)?.copy(rerouting = true) ?: _state.value
                _announcements.tryEmit("Rerouting")
                _rerouteRequest.tryEmit(r.steps.last().location)
            }
        }
    }
```

Add `onRerouteResult` after `onLocation`:
```kotlin
    /** Result of a reroute request: adopt [newRoute] and continue (silently, no
     *  departure cue), or announce failure and keep the current route. */
    fun onRerouteResult(newRoute: Route?) {
        rerouting = false
        if (newRoute != null && newRoute.steps.isNotEmpty()) {
            route = newRoute
            stepIndex = 0
            offRouteCount = 0
            announcer = AnnouncerState(startedSpoken = true) // skip the "Head…" departure cue
            val first = newRoute.steps[0]
            _state.value = NavUiState.Active(
                instruction = first.instruction,
                maneuver = first.maneuver,
                distanceToTurnMeters = 0,
                remainingDistanceMeters = newRoute.distanceMeters,
                etaEpochSeconds = nowEpochSeconds() + newRoute.durationSeconds,
                arrived = false,
                rerouting = false,
            )
        } else {
            _announcements.tryEmit("Rerouting failed")
            _state.value = (_state.value as? NavUiState.Active)?.copy(rerouting = false) ?: _state.value
        }
    }
```

In `end()`, reset the off-route state (add before `_state.value = NavUiState.Inactive`):
```kotlin
        offRouteCount = 0
        rerouting = false
```

- [ ] **Step 5: Run tests — all green**

Run with `--tests "com.virtueson.copilotmaps.ui.map.NavViewModelTest"` → all pass (existing 6 + new 4 = 10).
Then the full suite (no filter) → BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add "android/app/src/main/java/com/virtueson/copilotmaps/ui/map/NavState.kt" "android/app/src/main/java/com/virtueson/copilotmaps/ui/map/NavViewModel.kt" "android/app/src/test/java/com/virtueson/copilotmaps/ui/map/NavViewModelTest.kt"
git commit -m "feat(app): NavViewModel off-route detection + reroute lifecycle (Step 5e)"
```

---

## Task 3: MapScreen wiring + "Rerouting…" banner

**Files:**
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt`

**Interfaces:**
- Consumes: `navViewModel.rerouteRequest` + `navViewModel.onRerouteResult(Route?)` (Task 2); existing `routeViewModel.planRoutes(GeoPoint, GeoPoint)`, `routesState` (`RoutesState.Loaded(routes, selectedId)` / `.Error`), `locationSample` (`LocationSample?`), `navState.rerouting`.
- Produces: no new public interface — UI wiring only. Verified on device.

> No unit test (Compose + device audio/GPS). Gated by the manual device check in Step 4.

- [ ] **Step 1: Collect reroute requests and drive the re-plan**

In `MapScreen.kt`, near the existing `navViewModel`/`navState` declarations and the `LaunchedEffect(locationSample)` that feeds `onLocation` (around line 115-124), add a `rerouteInFlight` gate and two effects. Add the state declaration next to the other `remember`s at the top level of `MapScreen`:

```kotlin
    var rerouteInFlight by remember { mutableStateOf(false) }
```

Then, after the existing `LaunchedEffect(locationSample) { ... }`, add:

```kotlin
    // A reroute was requested → re-plan from the current position to the destination.
    LaunchedEffect(Unit) {
        navViewModel.rerouteRequest.collect { dest ->
            val here = locationSample?.let { GeoPoint(it.latitude, it.longitude) }
            if (here == null) {
                navViewModel.onRerouteResult(null) // can't locate → treat as failure
            } else {
                rerouteInFlight = true
                routeViewModel.planRoutes(here, dest)
            }
        }
    }

    // Deliver the re-plan result back to the nav session (exactly once per reroute).
    LaunchedEffect(routesState) {
        if (!rerouteInFlight) return@LaunchedEffect
        when (val s = routesState) {
            is RoutesState.Loaded -> { rerouteInFlight = false; navViewModel.onRerouteResult(s.routes.firstOrNull()) }
            is RoutesState.Error -> { rerouteInFlight = false; navViewModel.onRerouteResult(null) }
            else -> {} // Idle/Loading: wait
        }
    }
```

Ensure these imports exist (add any missing):
```kotlin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
```

- [ ] **Step 2: Show "Rerouting…" in the nav banner**

In `MapScreen.kt`, in the `NavBanner` composable (around line 549-557), replace the instruction `Column` contents so the rerouting state overrides the instruction and hides the distance:

```kotlin
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        state.rerouting -> "Rerouting…"
                        state.arrived -> "You've arrived"
                        else -> state.instruction
                    },
                    fontWeight = FontWeight.Bold,
                )
                if (!state.arrived && !state.rerouting) {
                    Text(formatDistance(state.distanceToTurnMeters))
                }
            }
```

- [ ] **Step 3: Compile**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat compileDebugKotlin --console=plain
```
Expected: BUILD SUCCESSFUL. Fix any unresolved-reference error (usually a missing import above). Then run `.\gradlew.bat testDebugUnitTest --console=plain` → BUILD SUCCESSFUL (existing tests still pass).

- [ ] **Step 4: Device verification (manual)**

Build/run on the Pixel-class device (`de92c98f`) with the backend up and `adb reverse tcp:8000 tcp:8000`. Then:
1. Start navigation to a destination.
2. Deliberately drive/walk off the route (or use an emulator route playback that diverges).
3. Confirm after ~3 s off-route you hear **"Rerouting"**, the banner shows **"Rerouting…"**, the **map redraws a new route** from your current position, and guidance continues on it (without repeating "Head…").
4. (Optional) Toggle airplane mode briefly while off-route to confirm a failed reroute says **"Rerouting failed"** and keeps the old route, then retries.

- [ ] **Step 5: Commit**

```bash
git add "android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt"
git commit -m "feat(app): wire off-route reroute + Rerouting banner (Step 5e)"
```

---

## Self-Review

**Spec coverage:**
- `distanceToRouteMeters` point-to-segment, empty→0 → Task 1 + tests.
- Detect >50 m for 3 fixes, backoff 10 s → Task 2 `onLocation` block + tests.
- Speak "Rerouting", emit destination event → Task 2 + `sustained off-route` test.
- Adopt new route silently (no departure cue), `rerouting` flag → Task 2 `onRerouteResult` + test; `NavState` field.
- Failure → "Rerouting failed", keep old route, backoff → Task 2 + `onRerouteResult null` test.
- MapScreen re-plans via RouteViewModel (map redraws) + delivers result once → Task 3 effects + `rerouteInFlight` gate.
- Banner "Rerouting…" → Task 3 Step 2.
- No backend changes → confirmed (reuses `planRoutes`).

**Placeholder scan:** none — all code steps are complete; the one judgment step (device test) is manual by design.

**Type consistency:** `distanceToRouteMeters(GeoPoint, List<GeoPoint>): Double` identical across Tasks 1/2. `rerouteRequest: SharedFlow<GeoPoint>` and `onRerouteResult(Route?)` identical across Tasks 2/3. `NavUiState.Active.rerouting: Boolean` added in Task 2 Step 1, consumed in Task 3. `RoutesState.Loaded(routes, selectedId)` matches `RoutesState.kt`. `LocationSample.latitude/longitude` matches existing usage in the `onLocation` feed.
