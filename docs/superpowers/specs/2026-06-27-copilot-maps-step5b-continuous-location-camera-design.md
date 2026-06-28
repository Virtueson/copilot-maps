# Step 5b — Continuous Location + Following Camera — Design

**Date:** 2026-06-27
**Status:** Approved (brainstorm complete; ready for implementation plan)
**Part of:** Step 5 (Live Navigation), build-our-own. **Personal use only.**
**Depends on:** existing `LocationProvider`/`FusedLocationProvider`, `MapViewModel`, `MapScreen`/`RoutingMap`.

## Goal

Replace the one-shot GPS fix with a **continuous location stream**, and make the map camera
**follow** the user in a heading-up "driving" view. First visible nav piece; reused by nav
mode (5c).

## Decisions (approved)

- **Heading-up driving camera:** rotate to GPS bearing + tilt ~45°, recenter as you move;
  hold last heading when bearing is unknown (stopped/slow).
- **Follow on by default once located; auto-off on user pan; recenter button (⌖) re-engages.**
- Keep Google's blue dot (`isMyLocationEnabled`) as the position indicator — no custom marker.
- Personal-use scope: **no foreground service**; updates run while the app/ViewModel is alive.

## Components

### Location stream (`location/LocationProvider.kt` + `FusedLocationProvider`)

```kotlin
data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val bearing: Float?,    // direction of travel; null when stopped/unknown
    val speedMps: Float?,   // for "is moving" decisions
)

interface LocationProvider {
    suspend fun getCurrentLocation(): LocationResult        // unchanged — first-fix gate
    fun locationUpdates(): Flow<LocationSample>             // NEW — continuous
}
```

- `FusedLocationProvider.locationUpdates()` = `callbackFlow` wrapping
  `fusedClient.requestLocationUpdates(LocationRequest(PRIORITY_HIGH_ACCURACY, ~1000ms), callback, Looper)`.
  Each `onLocationResult` emits `LocationSample(lat, lng, bearing if hasBearing else null,
  speed if hasSpeed else null)`. `awaitClose { fusedClient.removeLocationUpdates(callback) }`.
- `getCurrentLocation()` is unchanged (keeps the existing first-fix → `Located` gate and its test).

### `MapViewModel`

```kotlin
val location: StateFlow<LocationSample?>   // latest fix; drives the camera
```
`onPermissionGranted()` keeps the one-shot gate **and** launches a collector of
`locationProvider.locationUpdates()` that writes each sample into `_location`. The collector
job lives in `viewModelScope` (cancelled on `onCleared`, which removes the FusedLocation
callback via `awaitClose`). A previous collector is cancelled before starting a new one.

### `MapScreen` / `RoutingMap` — following camera + recenter

- Collect `location`; `var following by remember { mutableStateOf(true) }`.
- **Follow effect:** when `following` and `location != null`, `cameraPositionState.animate` to:
  ```
  target = (lat, lng), zoom = 17f, tilt = 45f,
  bearing = sample.bearing ?: cameraPositionState.position.bearing
  ```
- **Auto-off on pan:** when `cameraPositionState.cameraMoveStartedReason` indicates a user
  gesture, set `following = false`. Programmatic follow-animations use the API/developer
  reason, so they don't self-disengage.
- **Recenter FAB (⌖):** shown only when `following == false`; tap → `following = true` (and the
  follow effect immediately re-centers). Positioned above the existing Copilot FAB (no overlap).
- The existing one-shot still sets the **initial** camera; the follow effect takes over once
  samples arrive. Blue dot stays via `isMyLocationEnabled`.

## Error handling

- Stream errors (permission revoked mid-session, GPS disabled) are caught in the collector →
  keep last position + blue dot, no crash. No nav state to corrupt yet.
- Missing `bearing`/`speed` are nullable; camera holds current bearing when bearing is null.

## Testing

- **Unit (JVM, `MainDispatcherRule` + `runTest`):** `MapViewModel` with a fake provider whose
  `locationUpdates()` emits two `LocationSample`s → assert `location.value` ends on the second.
  The existing first-fix test (`getCurrentLocation` → `Located`) stays green (fake gains a
  default empty `locationUpdates()`).
- **On-device (Pixel-class phone):** walk/drive — camera follows, rotates to heading, tilts;
  panning disengages and ⌖ reappears; tapping ⌖ re-centers; blue dot tracks position.
- `FusedLocationProvider.locationUpdates()` and camera are framework/Maps glue — device-verified,
  not unit-tested (consistent with prior policy).

## Out of scope (later)

- Maneuver banner, distance-to-turn, step advancing, ETA, End button (5c).
- Voice announcements (5d), off-route/reroute (5e).
- Foreground/background navigation service; snapping location to the route line.

## Definition of Done

- `LocationProvider` exposes `locationUpdates()`; `FusedLocationProvider` streams real fixes.
- `MapViewModel.location` updates continuously; camera follows in heading-up driving view.
- Panning disengages follow; ⌖ re-engages; blue dot remains.
- New `MapViewModel` unit test passes; existing app + backend tests stay green.
- Verified on device; no secrets committed; `git status` clean.
