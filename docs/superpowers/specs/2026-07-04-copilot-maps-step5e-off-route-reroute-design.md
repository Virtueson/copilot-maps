# Step 5E — Off-route detection + automatic reroute

**Date:** 2026-07-04
**Status:** Design approved, pending spec review
**Depends on:** 5b (continuous location), 5c (navigation mode + NavViewModel + navProgress),
5d (announcements via VoiceOutput), Routing (RouteViewModel / `/routes/plan`).

## Goal

During live navigation, notice when the driver has left the route and
automatically re-plan from the current position to the same destination, then
resume guidance on the new route — with a spoken "Rerouting" cue. Personal-use
app; just needs to work well on the user's own (Google-services) phone.

## Decisions (from brainstorm)

- **Sensitivity:** off-route when the distance from the current location to the
  route line is **> 50 m for 3 consecutive GPS fixes** (~3 s). Ignores GPS
  wobble and on-road drift; reacts within a few seconds of a real wrong turn.
- **Reroute is automatic** (no prompt), announced with a spoken "Rerouting".
- **On reroute failure** (no network / routing API error): keep guiding the
  current route, speak "Rerouting failed", and auto-retry as the driver stays
  off-route, with a **~10 s backoff** between attempts.
- **Orchestration:** `NavViewModel` *detects* off-route and *requests* a
  reroute (emits an event); `RouteViewModel` performs the re-plan (so the map's
  drawn polyline updates in sync); `NavViewModel` adopts the result. Keeps
  `NavViewModel` free of networking and keeps map + guidance consistent.

## Architecture

```
NavViewModel.onLocation(point)
  ├─ navProgress(...)                 (existing) → HUD + announcements
  └─ distanceToRouteMeters(point, route.points)   (NEW, pure)
        │ > 50 m?  count++ : count = 0
        │ count >= 3  AND  !rerouting  AND  now - lastAttempt >= backoff
        ▼
     rerouting = true; announce "Rerouting"; _rerouteRequest.emit(destination)
        │
        ▼
MapScreen collects rerouteRequest
  → routeViewModel.planRoutes(currentLocation, destination)   [map polyline updates]
  → observes routesState:
        Loaded  → navViewModel.onRerouteResult(firstRoute)   [guidance continues]
        Error   → navViewModel.onRerouteResult(null)         ["Rerouting failed", keep old]
```

`destination` for the reroute is the active route's final step location
(`route.steps.last().location`).

### 1. Pure detector — `android/.../data/OffRoute.kt`

```kotlin
/** Shortest distance (metres) from [location] to the route polyline [points],
 *  measured against segments (not just vertices) so long straight legs with
 *  sparse vertices don't read as off-route. Empty/single-point → 0.0. */
fun distanceToRouteMeters(location: GeoPoint, points: List<GeoPoint>): Double
```

Implementation: for each consecutive segment `(a, b)` in `points`, compute the
point-to-segment distance and take the minimum. Segment distance uses a local
equirectangular projection around `location` (metres per degree of latitude is
constant; longitude scaled by `cos(lat)`), projecting `location`, `a`, `b` to a
local metre plane, then the standard point-to-segment formula. Reuses
`haversineMeters` only as a fallback for the degenerate single-point case.
Pure — no Android, no TTS — fully unit-testable.

### 2. `NavViewModel` — off-route detection + reroute lifecycle

New private state:
- `private var offRouteCount = 0`
- `private var rerouting = false`
- `private var lastRerouteAttemptSec = 0L`
- `private val _rerouteRequest = MutableSharedFlow<GeoPoint>(replay = 0, extraBufferCapacity = 4, onBufferOverflow = DROP_OLDEST)`
- `val rerouteRequest: SharedFlow<GeoPoint> = _rerouteRequest.asSharedFlow()`

Constants: `offRouteMeters = 50.0`, `offRouteFixes = 3`, `rerouteBackoffSec = 10`.

In `onLocation(point)` (after the existing navProgress + announce work):
```kotlin
val r = route ?: return
if (rerouting) return
val off = distanceToRouteMeters(point, r.points) > offRouteMeters
offRouteCount = if (off) offRouteCount + 1 else 0
if (offRouteCount >= offRouteFixes &&
    nowEpochSeconds() - lastRerouteAttemptSec >= rerouteBackoffSec
) {
    rerouting = true
    lastRerouteAttemptSec = nowEpochSeconds()
    offRouteCount = 0
    _state.value = activeCopy(rerouting = true)          // banner "Rerouting…"
    _announcements.tryEmit("Rerouting")
    _rerouteRequest.tryEmit(r.steps.last().location)     // destination
}
```

New `fun onRerouteResult(newRoute: Route?)`:
```kotlin
rerouting = false
if (newRoute != null && newRoute.steps.isNotEmpty()) {
    route = newRoute
    stepIndex = 0
    announcer = AnnouncerState(startedSpoken = true)     // skip "Head north…" departure
    // next onLocation drives prepare/now cues on the new route naturally
    _state.value = firstActiveState(newRoute, rerouting = false)
} else {
    _announcements.tryEmit("Rerouting failed")           // keep old route; retry after backoff
    _state.value = activeCopy(rerouting = false)
}
```

`NavUiState.Active` gains `val rerouting: Boolean = false`. `start()` and the
normal `onLocation` state-building set `rerouting = false`. `end()` resets
`offRouteCount = 0`, `rerouting = false`.

> The `activeCopy(...)` / `firstActiveState(...)` names above are illustrative
> shorthand: in practice, toggling the flag on the live state is
> `(_state.value as? NavUiState.Active)?.copy(rerouting = …)`, and adopting a new
> route rebuilds `NavUiState.Active` exactly the way `start()` already does
> (instruction/eta/remaining from the new route's first step), only with
> `rerouting = false` and no departure cue. The implementation plan will make
> these concrete.

> Off-route detection is skipped while `rerouting` is true (in-flight) and while
> the route has no geometry (`points` empty). The backoff timer means a failed
> reroute won't re-fire for ~10 s even though the driver is still off-route.

### 3. `MapScreen` wiring

- Collect `navViewModel.rerouteRequest`:
  ```kotlin
  LaunchedEffect(Unit) {
      navViewModel.rerouteRequest.collect { dest ->
          val here = locationSample?.let { GeoPoint(it.latitude, it.longitude) } ?: origin
          rerouteInFlight = true
          routeViewModel.planRoutes(here, dest)
      }
  }
  ```
- Observe `routesState` to deliver the result exactly once per reroute:
  ```kotlin
  LaunchedEffect(routesState) {
      if (!rerouteInFlight) return@LaunchedEffect
      when (val s = routesState) {
          is RoutesState.Loaded -> { rerouteInFlight = false; navViewModel.onRerouteResult(s.routes.first()) }
          is RoutesState.Error  -> { rerouteInFlight = false; navViewModel.onRerouteResult(null) }
          else -> {}
      }
  }
  ```
  (`rerouteInFlight` is a `remember { mutableStateOf(false) }` gate so a normal
  user-initiated `planRoutes` isn't mistaken for a reroute result.)
- `NavBanner` shows "Rerouting…" when `navState.rerouting` is true (instruction
  area), otherwise the normal maneuver + instruction.

## Error handling / edge cases

- **Reroute failure:** `onRerouteResult(null)` keeps the current route, speaks
  "Rerouting failed"; the backoff lets it auto-retry after ~10 s as the driver
  remains off-route.
- **In-flight guard:** no new reroute is started while one is pending
  (`rerouting`), preventing a storm of requests.
- **Empty geometry:** if `route.points` is empty, `distanceToRouteMeters`
  returns 0.0 → never off-route (safe no-op).
- **GPS wobble / parallel roads:** the 50 m × 3-fix debounce absorbs brief
  spikes; parallel-road false triggers are an accepted risk of the "Balanced"
  setting.
- **Reroute while genuinely near destination:** if off-route within a few
  metres of arrival, `navProgress` will already report `arrived`; the arrived
  cue path is unaffected (reroute only fires on sustained >50 m offset).

## Testing

Pure unit tests on `distanceToRouteMeters` (no Android):
- location on the route line → ~0 m.
- location 100 m to the side of a segment → ~100 m.
- location off the *end* of a segment → distance to the nearest endpoint.
- mid-segment with sparse vertices → small (segment, not vertex, distance).
- empty / single-point route → 0.0.

`NavViewModel` tests (fake collectors, injectable `nowEpochSeconds`):
- sustained off-route for 3 fixes emits exactly one `rerouteRequest` (carrying
  the destination) and one "Rerouting" announcement; sets `rerouting`.
- a single off-route fix (then back on route) does **not** trigger.
- `onRerouteResult(newRoute)` adopts the new route, clears `rerouting`, and does
  NOT emit a departure ("Head…") cue.
- `onRerouteResult(null)` emits "Rerouting failed", keeps the previous route
  active, and a further off-route within the backoff window does not re-fire.

No backend changes — reroute reuses the existing `/routes/plan` via
`RouteViewModel.planRoutes`.

## Out of scope (later)

- Choosing among alternate reroutes (auto-picks the first/best).
- Off-route *voice* beyond "Rerouting" / "Rerouting failed".
- Copilot ↔ nav-state context; actionable copilot ("take me there", 4c);
  copilot place results as map markers (Level A).
- Snapping the blue dot to the route line.

## Definition of done

- `distanceToRouteMeters` implemented and unit-tested (TDD).
- `NavViewModel` detects sustained off-route, emits `rerouteRequest` + speaks
  "Rerouting", adopts a new route via `onRerouteResult`, handles failure with
  backoff; `NavUiState.Active.rerouting` added; unit-tested.
- `MapScreen` wires `rerouteRequest` → `RouteViewModel.planRoutes` → map redraw
  → `onRerouteResult`; banner shows "Rerouting…".
- Verified on the Pixel-class device: deliberately leaving the route triggers a
  spoken "Rerouting", the map redraws a new route from the current position, and
  guidance continues on it.
