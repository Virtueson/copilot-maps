# Step 5c — Navigation Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn-by-turn nav mode — Start a route, get a maneuver banner (arrow + instruction + distance-to-turn), live ETA + remaining distance, steps that advance by proximity, and an End button; browse UI hidden, Copilot kept, screen stays awake.

**Architecture:** A pure `navProgress(steps, location, fromIndex)` calculator (haversine, ~30 m proximity advance) drives a `NavViewModel`/`NavUiState`. `MapScreen` feeds `MapViewModel.location` into `NavViewModel.onLocation(...)` while active, and `RoutingMap` swaps the browse overlays for the nav HUD. Reuses `Route.steps` (5a) + the following camera (5b).

**Tech Stack:** Kotlin, Jetpack Compose, AndroidX Lifecycle ViewModel, kotlin.math.

## Global Constraints

- Package root `com.virtueson.copilotmaps`. Gradle: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; cd android; .\gradlew.bat <task>`.
- TDD for pure logic + ViewModel; HUD composables + keep-screen-on are framework glue (device-verified).
- Personal-use scope: proximity-only advancement (no snap/reroute — that's 5e); no voice (5d).
- Existing behavior (browse, routes, copilot, follow camera) must keep working when nav is Inactive.
- No secrets committed; confirm `git status` before commits.

---

### Task 1: Pure `navProgress` calculator (TDD)

**Files:**
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/data/NavProgress.kt`
- Create: `android/app/src/test/java/com/virtueson/copilotmaps/data/NavProgressTest.kt`

**Interfaces:**
- Produces: `data class NavProgress(stepIndex, distanceToTurnMeters, remainingDistanceMeters, arrived)`;
  `fun navProgress(steps: List<RouteStep>, location: GeoPoint, fromIndex: Int, arriveThresholdMeters: Double = 30.0): NavProgress`;
  `fun haversineMeters(a: GeoPoint, b: GeoPoint): Double`.

- [ ] **Step 1: Write the failing tests**

`android/app/src/test/java/com/virtueson/copilotmaps/data/NavProgressTest.kt`:
```kotlin
package com.virtueson.copilotmaps.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun step(name: String, maneuver: String, dist: Int, lat: Double, lng: Double) =
    RouteStep(instruction = name, maneuver = maneuver, distanceMeters = dist, location = GeoPoint(lat, lng))

// 4 steps roughly along the equator heading east. ~0.01 deg lng ~= 1.11 km.
private val steps = listOf(
    step("Head", "DEPART", 100, 0.0, 0.000),
    step("Turn left", "TURN_LEFT", 200, 0.0, 0.010),
    step("Turn right", "TURN_RIGHT", 300, 0.0, 0.020),
    step("Arrive", "ARRIVE", 0, 0.0, 0.030),
)

class NavProgressTest {

    @Test
    fun `stays on step when far from the upcoming turn`() {
        val p = navProgress(steps, GeoPoint(0.0, 0.000), fromIndex = 1)
        assertEquals(1, p.stepIndex)               // far from step 1 (~1.1 km), no advance
        assertTrue(p.distanceToTurnMeters > 900)
        assertFalse(p.arrived)
    }

    @Test
    fun `advances when within threshold of the upcoming turn`() {
        val p = navProgress(steps, GeoPoint(0.0, 0.0100001), fromIndex = 1) // ~1 m from step 1
        assertEquals(2, p.stepIndex)
    }

    @Test
    fun `remaining is distance to turn plus later step distances`() {
        val p = navProgress(steps, GeoPoint(0.0, 0.000), fromIndex = 1)
        // later steps after index 1: 300 (idx2) + 0 (idx3) = 300
        assertEquals(300, p.remainingDistanceMeters - p.distanceToTurnMeters)
    }

    @Test
    fun `arrived when within threshold of the final step`() {
        val p = navProgress(steps, GeoPoint(0.0, 0.0300001), fromIndex = 3) // ~1 m from arrive
        assertEquals(3, p.stepIndex)
        assertTrue(p.arrived)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd android; .\gradlew.bat :app:testDebugUnitTest --tests "com.virtueson.copilotmaps.data.NavProgressTest"`
Expected: FAIL — `navProgress` / `NavProgress` unresolved.

- [ ] **Step 3: Implement `NavProgress.kt`**

```kotlin
package com.virtueson.copilotmaps.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class NavProgress(
    val stepIndex: Int,
    val distanceToTurnMeters: Int,
    val remainingDistanceMeters: Int,
    val arrived: Boolean,
)

fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
    val r = 6_371_000.0
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}

/** Advance from `fromIndex` while within threshold of each upcoming maneuver point. */
fun navProgress(
    steps: List<RouteStep>,
    location: GeoPoint,
    fromIndex: Int,
    arriveThresholdMeters: Double = 30.0,
): NavProgress {
    if (steps.isEmpty()) return NavProgress(0, 0, 0, arrived = true)
    val last = steps.size - 1
    var index = fromIndex.coerceIn(0, last)
    while (index < last && haversineMeters(location, steps[index].location) < arriveThresholdMeters) {
        index++
    }
    val distanceToTurn = haversineMeters(location, steps[index].location)
    var remaining = distanceToTurn
    for (i in (index + 1)..last) remaining += steps[i].distanceMeters
    val arrived = index == last && distanceToTurn < arriveThresholdMeters
    return NavProgress(
        stepIndex = index,
        distanceToTurnMeters = distanceToTurn.toInt(),
        remainingDistanceMeters = remaining.toInt(),
        arrived = arrived,
    )
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd android; .\gradlew.bat :app:testDebugUnitTest --tests "com.virtueson.copilotmaps.data.NavProgressTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add android/app/src/main/java/com/virtueson/copilotmaps/data/NavProgress.kt android/app/src/test/java/com/virtueson/copilotmaps/data/NavProgressTest.kt
git commit -m "feat(app): navProgress turn-by-turn progress calculator"
```

---

### Task 2: `NavViewModel` + `NavUiState` (TDD)

**Files:**
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/NavState.kt`
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/NavViewModel.kt`
- Create: `android/app/src/test/java/com/virtueson/copilotmaps/ui/map/NavViewModelTest.kt`

**Interfaces:**
- Consumes: `navProgress` (Task 1), `Route`, `RouteStep`, `GeoPoint`.
- Produces: `NavUiState { Inactive | Active(instruction, maneuver, distanceToTurnMeters, remainingDistanceMeters, etaEpochSeconds, arrived) }`;
  `NavViewModel(nowEpochSeconds)` with `state: StateFlow<NavUiState>`, `start(route)`, `onLocation(point)`, `end()`; `NavViewModelFactory`.

- [ ] **Step 1: Write `NavState.kt`**

```kotlin
package com.virtueson.copilotmaps.ui.map

sealed interface NavUiState {
    data object Inactive : NavUiState
    data class Active(
        val instruction: String,
        val maneuver: String,
        val distanceToTurnMeters: Int,
        val remainingDistanceMeters: Int,
        val etaEpochSeconds: Long,
        val arrived: Boolean,
    ) : NavUiState
}
```

- [ ] **Step 2: Write the failing test**

`android/app/src/test/java/com/virtueson/copilotmaps/ui/map/NavViewModelTest.kt`:
```kotlin
package com.virtueson.copilotmaps.ui.map

import com.virtueson.copilotmaps.data.GeoPoint
import com.virtueson.copilotmaps.data.Route
import com.virtueson.copilotmaps.data.RouteStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun step(name: String, maneuver: String, dist: Int, lat: Double, lng: Double) =
    RouteStep(name, maneuver, dist, GeoPoint(lat, lng))

private fun route(steps: List<RouteStep>) = Route(
    id = "r0", summary = "Test", distanceMeters = 600, durationSeconds = 600,
    points = emptyList(), polyline = "", trafficIntervals = emptyList(), steps = steps,
)

private val steps = listOf(
    step("Head", "DEPART", 100, 0.0, 0.000),
    step("Turn left", "TURN_LEFT", 200, 0.0, 0.010),
    step("Arrive", "ARRIVE", 0, 0.0, 0.020),
)

class NavViewModelTest {

    @Test
    fun `start emits Active with the first instruction`() {
        val vm = NavViewModel(nowEpochSeconds = { 1000L })
        vm.start(route(steps))

        val s = vm.state.value
        assertTrue(s is NavUiState.Active)
        s as NavUiState.Active
        assertEquals("Head", s.instruction)
    }

    @Test
    fun `start with no steps stays Inactive`() {
        val vm = NavViewModel(nowEpochSeconds = { 1000L })
        vm.start(route(emptyList()))
        assertTrue(vm.state.value is NavUiState.Inactive)
    }

    @Test
    fun `onLocation advances to the next instruction`() {
        val vm = NavViewModel(nowEpochSeconds = { 1000L })
        vm.start(route(steps))

        vm.onLocation(GeoPoint(0.0, 0.0000001)) // ~at step 0 -> advances to step 1

        val s = vm.state.value as NavUiState.Active
        assertEquals("Turn left", s.instruction)
    }

    @Test
    fun `end returns to Inactive`() {
        val vm = NavViewModel(nowEpochSeconds = { 1000L })
        vm.start(route(steps))
        vm.end()
        assertTrue(vm.state.value is NavUiState.Inactive)
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd android; .\gradlew.bat :app:testDebugUnitTest --tests "com.virtueson.copilotmaps.ui.map.NavViewModelTest"`
Expected: FAIL — `NavViewModel` unresolved.

- [ ] **Step 4: Implement `NavViewModel.kt`**

```kotlin
package com.virtueson.copilotmaps.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.virtueson.copilotmaps.data.GeoPoint
import com.virtueson.copilotmaps.data.Route
import com.virtueson.copilotmaps.data.navProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NavViewModel(
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : ViewModel() {

    private val _state = MutableStateFlow<NavUiState>(NavUiState.Inactive)
    val state: StateFlow<NavUiState> = _state.asStateFlow()

    private var route: Route? = null
    private var stepIndex = 0

    fun start(route: Route) {
        if (route.steps.isEmpty()) return
        this.route = route
        stepIndex = 0
        val first = route.steps[0]
        _state.value = NavUiState.Active(
            instruction = first.instruction,
            maneuver = first.maneuver,
            distanceToTurnMeters = 0,
            remainingDistanceMeters = route.distanceMeters,
            etaEpochSeconds = nowEpochSeconds() + route.durationSeconds,
            arrived = false,
        )
    }

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
        )
    }

    fun end() {
        route = null
        stepIndex = 0
        _state.value = NavUiState.Inactive
    }
}

class NavViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = NavViewModel() as T
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd android; .\gradlew.bat :app:testDebugUnitTest --tests "com.virtueson.copilotmaps.ui.map.NavViewModelTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add android/app/src/main/java/com/virtueson/copilotmaps/ui/map/NavState.kt android/app/src/main/java/com/virtueson/copilotmaps/ui/map/NavViewModel.kt android/app/src/test/java/com/virtueson/copilotmaps/ui/map/NavViewModelTest.kt
git commit -m "feat(app): NavViewModel + NavUiState navigation session"
```

---

### Task 3: Nav HUD + MapScreen wiring + on-device verify

**Files:**
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt`

**Interfaces:**
- Consumes: `NavViewModel`/`NavUiState` (Task 2), `MapViewModel.location` (5b), `RoutesState.Loaded`.
- Produces: nav HUD composables + Start/End wiring inside `MapScreen`/`RoutingMap`.

- [ ] **Step 1: Add imports**

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.virtueson.copilotmaps.data.Route
```
(If any of these are already imported, keep the single existing import — do not duplicate. `Arrangement`, `FontWeight`, and `Route` are likely already present.)

- [ ] **Step 2: Create the `NavViewModel` + collect its state in `MapScreen`**

After the `copilotViewModel` block and `val locationSample by mapViewModel.location.collectAsStateWithLifecycle()`, add:
```kotlin
    val navViewModel: NavViewModel = viewModel(factory = NavViewModelFactory())
    val navState by navViewModel.state.collectAsStateWithLifecycle()

    // Feed live location into the nav session while navigating.
    LaunchedEffect(locationSample) {
        val s = locationSample
        if (s != null && navState is NavUiState.Active) {
            navViewModel.onLocation(GeoPoint(s.latitude, s.longitude))
        }
    }
```

- [ ] **Step 3: Pass nav into `RoutingMap` (the `MapUiState.Located` branch)**

Add three arguments to the `RoutingMap(...)` call (after `onToggleTts = copilotViewModel::setTtsEnabled,`):
```kotlin
                navState = navState,
                onStartNav = { route -> navViewModel.start(route) },
                onEndNav = navViewModel::end,
```

- [ ] **Step 4: Extend the `RoutingMap` signature**

Add to the signature (after `onToggleTts: (Boolean) -> Unit,`):
```kotlin
    navState: NavUiState,
    onStartNav: (Route) -> Unit,
    onEndNav: () -> Unit,
```

- [ ] **Step 5: Force-follow + keep-screen-on while navigating**

Right after `var following by remember { mutableStateOf(true) }` in `RoutingMap`, add:
```kotlin
    val navActive = navState is NavUiState.Active
    LaunchedEffect(navActive) { if (navActive) following = true }

    val view = LocalView.current
    DisposableEffect(navActive) {
        view.keepScreenOn = navActive
        onDispose { view.keepScreenOn = false }
    }
```

- [ ] **Step 6: Swap the browse overlays for the nav HUD**

Wrap the **top overlay** `Column` (the `// Top overlay: category buttons + status/notes.` block) and the **bottom** `when (routesState) { ... }` block in `if (!navActive) { ... }`, and add the nav HUD. Replace from the `// Top overlay` comment through the end of the `when (routesState)` block with:
```kotlin
        if (!navActive) {
            // Top overlay: category buttons + status/notes.
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
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
                val banner: String? = when {
                    placesState is PlacesState.Loading -> "Searching…"
                    placesState is PlacesState.Error -> placesState.message
                    placesState is PlacesState.Loaded && placesState.places.isEmpty() -> "No places found nearby."
                    placesState is PlacesState.Loaded && placesState.fellBackToNearby -> "None on your route — showing nearest."
                    else -> null
                }
                if (banner != null) {
                    Spacer(Modifier.height(6.dp))
                    Surface(tonalElevation = 3.dp) {
                        Text(banner, modifier = Modifier.padding(8.dp), textAlign = TextAlign.Center)
                    }
                }
            }

            // Bottom overlay: route ETA cards / status.
            when (routesState) {
                is RoutesState.Idle -> BottomBar { Text("Long-press the map to set a destination") }
                is RoutesState.Loading -> BottomBar { Text("Finding routes…") }
                is RoutesState.Error -> BottomBar {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(routesState.message, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { destination?.let { onPlan(GeoPoint(it.latitude, it.longitude)) } }) {
                            Text("Retry")
                        }
                    }
                }
                is RoutesState.Loaded -> RouteCards(
                    state = routesState,
                    onSelect = onSelect,
                    onStart = onStartNav,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        } else if (navState is NavUiState.Active) {
            NavBanner(navState, modifier = Modifier.align(Alignment.TopCenter))
            NavBottomBar(navState, onEnd = onEndNav, modifier = Modifier.align(Alignment.BottomCenter))
        }
```

- [ ] **Step 7: Hide the recenter ◎ during nav**

Change the recenter FAB guard from `if (!following) {` to:
```kotlin
        if (!following && !navActive) {
```

- [ ] **Step 8: Add `onStart` to `RouteCards` with a Start button**

Update the `RouteCards` signature and body. Replace the existing `RouteCards` composable header + its `Row(...)` with a `Column` that holds the cards plus a Start button:
```kotlin
@Composable
private fun RouteCards(
    state: RoutesState.Loaded,
    onSelect: (String) -> Unit,
    onStart: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .fillMaxWidth()
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.routes.forEach { route ->
                val selected = route.id == state.selectedId
                Card(
                    onClick = { onSelect(route.id) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) Color(0xFFD2E3FC) else Color(0xFFF1F3F4)
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = route.summary,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                        Text(formatDuration(route.durationSeconds))
                        Text(formatDistance(route.distanceMeters))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val selectedRoute = state.routes.firstOrNull { it.id == state.selectedId }
        Button(
            onClick = { selectedRoute?.let(onStart) },
            enabled = selectedRoute != null && selectedRoute.steps.isNotEmpty(),
        ) { Text("Start") }
    }
}
```
(This replaces the old `RouteCards` that was a bare `Row`. The card contents are unchanged — only wrapped in a `Column` with a Start button.)

- [ ] **Step 9: Add the nav HUD composables + helpers at the bottom of `MapScreen.kt`**

After the `trafficColor(...)` function (or anywhere at file top-level scope), add:
```kotlin
@Composable
private fun NavBanner(state: NavUiState.Active, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .fillMaxWidth()
            .padding(12.dp),
        tonalElevation = 3.dp,
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(maneuverArrow(state.maneuver), fontSize = 28.sp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.arrived) "You've arrived" else state.instruction,
                    fontWeight = FontWeight.Bold,
                )
                if (!state.arrived) {
                    Text(formatDistance(state.distanceToTurnMeters))
                }
            }
        }
    }
}

@Composable
private fun NavBottomBar(state: NavUiState.Active, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .fillMaxWidth()
            .padding(12.dp),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("ETA ${formatClock(state.etaEpochSeconds)}")
                Text("${formatDistance(state.remainingDistanceMeters)} left")
            }
            Button(onClick = onEnd) { Text("End") }
        }
    }
}

private fun maneuverArrow(maneuver: String): String = when {
    maneuver == "ARRIVE" -> "◎"
    maneuver.contains("LEFT") -> "←"
    maneuver.contains("RIGHT") -> "→"
    maneuver.contains("UTURN") -> "↩"
    maneuver.contains("MERGE") || maneuver.contains("RAMP") -> "↗"
    else -> "↑"
}

private fun formatClock(epochSeconds: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(epochSeconds * 1000))
}
```
(`Spacer`, `Row`, `Column`, `Surface`, `Text`, `Button`, `formatDistance`, `formatDuration`, `Color`,
`CardDefaults`, `Card`, `horizontalScroll`, `rememberScrollState`, `width`, `height` are already
imported in `MapScreen.kt`.)

- [ ] **Step 10: Compile**

Run: `cd android; .\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 11: On-device verification (emulator route playback is ideal)**

Backend + `adb reverse` needed to plan a route. Run the app (▶), grant location:
1. Long-press a destination → routes appear → a **Start** button shows under the route cards.
2. Tap **Start** → Gas/Food + route cards disappear; a maneuver **banner** appears on top
   (arrow + instruction + distance), and a bottom bar shows **ETA + remaining + End**; the
   camera locks to follow; the screen no longer dims.
3. Drive the route (or play a route in the emulator): the distance counts down and the
   instruction **advances** as you pass each turn; ETA/remaining update.
4. Near the destination the banner shows **"You've arrived"**.
5. Tap **End** → browse UI (Gas/Food, route cards, recenter ◎) returns; screen-on resets.
6. Copilot button still works during nav.

- [ ] **Step 12: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt
git commit -m "feat(app): navigation mode HUD + Start/End wiring (on-device verified)"
```

---

## Definition of Done (verify all)

- Start a selected route → maneuver banner (arrow + instruction + distance-to-turn); steps
  advance by proximity; ETA + remaining distance shown; End returns to browse.
- Browse UI hidden during nav; Copilot kept; recenter ◎ hidden; screen stays awake.
- `NavProgressTest` + `NavViewModelTest` pass; existing app + backend suites stay green.
- Verified on device; `git status` clean; no secrets committed.

## Notes for later (next sub-projects)

- **5d** — speak `instruction` on step change via Step 4b `VoiceOutput` (announce when `stepIndex` advances).
- **5e** — off-route detection (distance from route polyline) + reroute via `planRoutes`.
- Copilot nav-state context ("what's my next turn?") — feed `NavUiState.Active` into the copilot context.
