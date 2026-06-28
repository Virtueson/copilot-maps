# Step 5b — Continuous Location + Following Camera Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stream live location and make the map camera follow the user in a heading-up driving view (zoom 17, tilt 45°, rotate to bearing), with follow-on-by-default, auto-off on pan, and a recenter button.

**Architecture:** `LocationProvider` gains `locationUpdates(): Flow<LocationSample>` (Fused via `callbackFlow`/`requestLocationUpdates`); `MapViewModel` exposes `location: StateFlow<LocationSample?>`; `RoutingMap` animates `cameraPositionState` to each sample while `following`, disengages on user-gesture camera moves, and shows a recenter FAB to re-engage. Google's blue dot stays as the position indicator.

**Tech Stack:** Kotlin, Jetpack Compose, maps-compose 6.4.1, play-services-location 21.3.0, kotlinx-coroutines (Flow/callbackFlow).

## Global Constraints

- Package root `com.virtueson.copilotmaps`. Gradle: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; cd android; .\gradlew.bat <task>`.
- TDD for ViewModel logic; framework glue (Fused stream, camera) verified on device, not unit-tested.
- Personal-use scope: no foreground service. Existing behavior (first-fix gate, blue dot, long-press destination) must keep working.
- No secrets committed (`local.properties`, `backend/.env` stay ignored); confirm `git status` before commits.

---

### Task 1: `LocationSample` + `locationUpdates()` stream

**Files:**
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/location/LocationProvider.kt`
- Modify: `android/app/src/test/java/com/virtueson/copilotmaps/ui/map/MapViewModelTest.kt`

**Interfaces:**
- Produces: `data class LocationSample(latitude: Double, longitude: Double, bearing: Float?, speedMps: Float?)`;
  `LocationProvider.locationUpdates(): Flow<LocationSample>`; `FusedLocationProvider` implements it.
- The test `FakeLocationProvider` gains a configurable `updates: Flow<LocationSample>` (default empty).

- [ ] **Step 1: Add `LocationSample` + interface method + Fused implementation**

In `location/LocationProvider.kt`, add imports at the top (after the existing imports):
```kotlin
import android.annotation.SuppressLint
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult as GmsLocationResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
```
Add the data class above `interface LocationProvider`:
```kotlin
/** A single continuous-stream location fix. bearing/speed are null when unknown. */
data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val bearing: Float?,
    val speedMps: Float?,
)
```
Add the method to the interface:
```kotlin
interface LocationProvider {
    suspend fun getCurrentLocation(): LocationResult
    fun locationUpdates(): Flow<LocationSample>
}
```
Add the implementation inside `class FusedLocationProvider` (after `getCurrentLocation`):
```kotlin
    @SuppressLint("MissingPermission")
    override fun locationUpdates(): Flow<LocationSample> = callbackFlow {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 1000L,
        ).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: GmsLocationResult) {
                val loc = result.lastLocation ?: return
                trySend(
                    LocationSample(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        bearing = if (loc.hasBearing()) loc.bearing else null,
                        speedMps = if (loc.hasSpeed()) loc.speed else null,
                    )
                )
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }
```
(`Priority` is already imported in this file. Permission is ensured by the caller before
collecting, hence `@SuppressLint("MissingPermission")`.)

- [ ] **Step 2: Update the test fake to satisfy the new interface**

In `MapViewModelTest.kt`, add imports:
```kotlin
import com.virtueson.copilotmaps.location.LocationSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
```
Replace the `FakeLocationProvider` class with a version that also streams updates:
```kotlin
private class FakeLocationProvider(
    private val result: LocationResult,
    private val updates: Flow<LocationSample> = emptyFlow(),
) : LocationProvider {
    override suspend fun getCurrentLocation(): LocationResult = result
    override fun locationUpdates(): Flow<LocationSample> = updates
}
```

- [ ] **Step 3: Compile + run the existing app tests (nothing should regress)**

Run: `cd android; .\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (existing `MapViewModelTest` still passes; the new method compiles).

- [ ] **Step 4: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add android/app/src/main/java/com/virtueson/copilotmaps/location/LocationProvider.kt android/app/src/test/java/com/virtueson/copilotmaps/ui/map/MapViewModelTest.kt
git commit -m "feat(app): LocationSample + FusedLocationProvider location stream"
```

---

### Task 2: `MapViewModel.location` stream collection (TDD)

**Files:**
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapViewModel.kt`
- Modify: `android/app/src/test/java/com/virtueson/copilotmaps/ui/map/MapViewModelTest.kt`

**Interfaces:**
- Consumes: `LocationProvider.locationUpdates()` (Task 1).
- Produces: `MapViewModel.location: StateFlow<LocationSample?>`, updated on `onPermissionGranted()`.

- [ ] **Step 1: Write the failing test**

In `MapViewModelTest.kt`, add imports:
```kotlin
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertNull
```
Append this test:
```kotlin
    @Test
    fun `location stream updates the location state`() = runTest {
        val s1 = LocationSample(1.0, 2.0, 90f, 5f)
        val s2 = LocationSample(1.1, 2.1, 80f, 6f)
        val vm = MapViewModel(
            FakeLocationProvider(LocationResult.Success(1.0, 2.0), flowOf(s1, s2))
        )

        assertNull(vm.location.value)
        vm.onPermissionGranted()
        advanceUntilIdle()

        assertEquals(s2, vm.location.value)
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd android; .\gradlew.bat :app:testDebugUnitTest --tests "com.virtueson.copilotmaps.ui.map.MapViewModelTest"`
Expected: FAIL — `vm.location` unresolved.

- [ ] **Step 3: Add the `location` state + collector to `MapViewModel`**

In `MapViewModel.kt`, add imports:
```kotlin
import com.virtueson.copilotmaps.location.LocationSample
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
```
Add the state (next to `_uiState`):
```kotlin
    private val _location = MutableStateFlow<LocationSample?>(null)
    val location: StateFlow<LocationSample?> = _location.asStateFlow()
    private var locationJob: Job? = null
```
At the end of `onPermissionGranted()` (after the existing `viewModelScope.launch { ... }` block), add:
```kotlin
        startLocationUpdates()
```
Add the private method:
```kotlin
    private fun startLocationUpdates() {
        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            locationProvider.locationUpdates()
                .catch { /* keep last position; stream errors are non-fatal */ }
                .collect { _location.value = it }
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd android; .\gradlew.bat :app:testDebugUnitTest --tests "com.virtueson.copilotmaps.ui.map.MapViewModelTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapViewModel.kt android/app/src/test/java/com/virtueson/copilotmaps/ui/map/MapViewModelTest.kt
git commit -m "feat(app): MapViewModel.location stream collection"
```

---

### Task 3: Following camera + recenter FAB + on-device verify

**Files:**
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt`

**Interfaces:**
- Consumes: `MapViewModel.location` (Task 2), `LocationSample` (Task 1).
- Produces: `RoutingMap` gains `location: LocationSample?`; follow/recenter behavior.

- [ ] **Step 1: Add imports to `MapScreen.kt`**

```kotlin
import androidx.compose.material3.FloatingActionButton
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.virtueson.copilotmaps.location.LocationSample
```

- [ ] **Step 2: Collect `location` in `MapScreen` and pass it into `RoutingMap`**

After `val copilotState by copilotViewModel.state.collectAsStateWithLifecycle()`, add:
```kotlin
    val locationSample by mapViewModel.location.collectAsStateWithLifecycle()
```
In the `is MapUiState.Located ->` branch, add `location = locationSample,` to the `RoutingMap(...)`
call (after `modifier = modifier,`):
```kotlin
            RoutingMap(
                modifier = modifier,
                location = locationSample,
                origin = origin,
                routesState = routesState,
                placesState = placesState,
                copilotState = copilotState,
                onPlan = { dest -> routeViewModel.planRoutes(origin, dest) },
                onSelect = routeViewModel::selectRoute,
                onSearchPlaces = { category ->
                    val polyline = (routesState as? RoutesState.Loaded)?.let { loaded ->
                        loaded.routes.firstOrNull { it.id == loaded.selectedId }?.polyline
                    }
                    placesViewModel.search(category, origin, polyline)
                },
                onClearPlaces = placesViewModel::clear,
                onSendCopilot = { text ->
                    copilotViewModel.sendMessage(text, buildTripContext(origin, routesState))
                },
                onMicCopilot = { handleMic(buildTripContext(origin, routesState)) },
                onToggleTts = copilotViewModel::setTtsEnabled,
            )
```

- [ ] **Step 3: Add `location` param to `RoutingMap` + the follow logic**

Update the `RoutingMap` signature — add `location: LocationSample?,` after `modifier: Modifier,`:
```kotlin
private fun RoutingMap(
    modifier: Modifier,
    location: LocationSample?,
    origin: GeoPoint,
    routesState: RoutesState,
    placesState: PlacesState,
    copilotState: CopilotUiState,
    onPlan: (GeoPoint) -> Unit,
    onSelect: (String) -> Unit,
    onSearchPlaces: (PlaceCategory) -> Unit,
    onClearPlaces: () -> Unit,
    onSendCopilot: (String) -> Unit,
    onMicCopilot: () -> Unit,
    onToggleTts: (Boolean) -> Unit,
) {
```
Right after the `cameraPositionState` declaration (the `rememberCameraPositionState { ... }` block),
add the follow state + effects:
```kotlin
    var following by remember { mutableStateOf(true) }

    // Disengage follow when the user pans the map by gesture.
    LaunchedEffect(cameraPositionState.isMoving) {
        if (cameraPositionState.isMoving &&
            cameraPositionState.cameraMoveStartedReason ==
                GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE
        ) {
            following = false
        }
    }

    // While following, animate the camera to each new location in a heading-up driving view.
    LaunchedEffect(location, following) {
        val loc = location
        if (following && loc != null) {
            val target = CameraPosition.Builder()
                .target(LatLng(loc.latitude, loc.longitude))
                .zoom(17f)
                .tilt(45f)
                .bearing(loc.bearing ?: cameraPositionState.position.bearing)
                .build()
            try {
                cameraPositionState.animate(
                    CameraUpdateFactory.newCameraPosition(target), 1000,
                )
            } catch (e: Exception) {
                // Map not ready yet; the next sample will retry.
            }
        }
    }
```

- [ ] **Step 4: Add the recenter FAB (shown only when not following)**

Inside `RoutingMap`'s root `Box`, just before the existing `var showChat by remember { mutableStateOf(false) }`
line, add:
```kotlin
        if (!following) {
            FloatingActionButton(
                onClick = { following = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(end = 16.dp, bottom = 88.dp),
            ) { Text("◎") }
        }
```
(It sits above the Copilot FAB, which uses `bottom = 16.dp`.)

- [ ] **Step 5: Compile**

Run: `cd android; .\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: On-device verification (Pixel-class phone `de92c98f`)**

Backend not required for this step (location is on-device). Run the app (▶), grant location:
1. The camera centers on you, tilted ~45°, and tracks as you move (walk/drive); it rotates to
   your heading when moving.
2. Drag/pan the map → following disengages (camera stays where you put it) and the **◎** button
   appears at the bottom-right (above the Copilot button).
3. Tap **◎** → camera re-centers on you and resumes following.
4. Long-press still drops a destination and plans routes (unchanged).

- [ ] **Step 7: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt
git commit -m "feat(app): heading-up following camera + recenter button (on-device verified)"
```

---

## Definition of Done (verify all)

- `LocationProvider.locationUpdates()` streams real fixes; `MapViewModel.location` updates continuously.
- Camera follows in heading-up driving view; panning disengages; ◎ re-engages; blue dot remains.
- New `MapViewModel` unit test passes; existing app + backend tests stay green.
- Verified on device; `git status` clean; no secrets committed.

## Notes for later (next sub-projects)

- **5c** — nav mode consuming `Route.steps`: maneuver banner, distance-to-next-turn, advance steps,
  ETA, End button, keep screen on; the follow camera here is reused for the nav view.
- **5d** — voice announcements via Step 4b `VoiceOutput`.
- **5e** — off-route detection + rerouting (uses `location` vs route polyline).
