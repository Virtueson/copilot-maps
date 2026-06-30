# Step 5c — Navigation Mode (turn-by-turn guidance) — Design

**Date:** 2026-06-27
**Status:** Approved (brainstorm complete; ready for implementation plan)
**Part of:** Step 5 (Live Navigation), build-our-own. **Personal use only.**
**Depends on:** 5a (`Route.steps`), 5b (location stream + following camera), routing (`RoutesState.Loaded`).

## Goal

The "it's actually navigating" step: pick a route, tap **Start**, and the app guides you
turn-by-turn — a maneuver banner with distance-to-next-turn, live ETA, steps that advance as
you pass each turn, and an **End** button. Reuses `Route.steps` and the 5b following camera.

## Decisions (approved)

- **Minimal HUD:** top banner = arrow + next instruction + distance-to-turn; bottom = ETA
  (arrival clock time) + remaining distance + **End**. Screen stays awake while navigating.
- **Proximity-threshold advancement:** advance to the next step when within ~30 m of the
  upcoming maneuver's point. (Snap-to-route deferred to 5e if needed.)
- **Nav takes over the screen:** hide Gas/Food + route cards; **keep the Copilot button**
  (no nav-state context yet — later tie-in). Hide the recenter ◎ (nav always follows).

## Components

### Pure progress calculator (`data/NavProgress.kt`) — framework-free, unit-tested

```kotlin
data class NavProgress(
    val stepIndex: Int,
    val distanceToTurnMeters: Int,
    val remainingDistanceMeters: Int,
    val arrived: Boolean,
)

/** Advance from `fromIndex` while within threshold of each upcoming maneuver point. */
fun navProgress(
    steps: List<RouteStep>,
    location: GeoPoint,
    fromIndex: Int,
    arriveThresholdMeters: Double = 30.0,
): NavProgress
```
- Helper `haversineMeters(a: GeoPoint, b: GeoPoint): Double` (plain math on our `GeoPoint`,
  no Maps/Android types — keeps the calculator JVM-unit-testable).
- Logic: while `stepIndex < lastIndex` and `haversine(location, steps[stepIndex].location) <
  threshold`, increment `stepIndex`. Then `distanceToTurnMeters = haversine(location,
  steps[stepIndex].location)`; `remainingDistanceMeters = distanceToTurn + Σ
  steps[(stepIndex+1)..].distanceMeters`; `arrived = stepIndex == lastIndex &&
  distanceToTurn < threshold`.
- Empty `steps` → caller guards (nav doesn't start).

### `NavViewModel` + `NavUiState` (`ui/map/NavViewModel.kt`)

```kotlin
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

class NavViewModel : ViewModel() {
    val state: StateFlow<NavUiState>
    fun start(route: Route)          // stores route + currentStepIndex = 0
    fun onLocation(point: GeoPoint)  // recompute progress; advance step; emit Active
    fun end()                        // -> Inactive
}
```
- Holds the active `Route` + `currentStepIndex`. `onLocation` calls `navProgress(...)`, updates
  `currentStepIndex`, and emits `Active` with the current step's instruction/maneuver.
- **ETA:** `etaEpochSeconds = now + route.durationSeconds × (remaining / route.distanceMeters)`
  (scales the traffic-aware duration by the fraction of distance left; guarded against
  divide-by-zero).
- `start` with empty `route.steps` stays `Inactive` (guard).

### UI (`ui/map/MapScreen.kt`)

- **Start entry:** in `RouteCards` (on `RoutesState.Loaded`), add a **Start** button →
  `navViewModel.start(selectedRoute)` and set `following = true`.
- **Feed location:** a `LaunchedEffect` that, while `navState is Active`, calls
  `navViewModel.onLocation(GeoPoint(sample.latitude, sample.longitude))` on each new sample.
- **When `Active`:** hide the top Gas/Food row and the `RouteCards`; show:
  - **Banner (top):** `maneuverArrow(maneuver)` + `instruction` + `formatDistance(distanceToTurn)`.
    `maneuverArrow`: `TURN_LEFT→←`, `TURN_RIGHT→→`, `DEPART`/`STRAIGHT→↑`, `MERGE→↗`,
    `UTURN→↩`, `ARRIVE→◎`, else `↑`. On `arrived`, banner shows "You've arrived".
  - **Bottom bar:** `ETA <clock>` + `formatDistance(remaining)` + **End** button
    (`navViewModel.end()`).
  - Keep the Copilot FAB; hide the recenter ◎.
- **Keep awake:** `val view = LocalView.current; DisposableEffect(active) { view.keepScreenOn =
  active; onDispose { view.keepScreenOn = false } }`.
- Route polyline + following camera reused from 5b (follow forced on at Start).
- Reuse existing `formatDistance`/`formatDuration`; ETA rendered as `HH:mm`.

## Error handling

- **No steps on the route:** Start disabled / no-op with a brief note; nav doesn't begin.
- **No location yet while Active:** banner shows the first instruction with a "—" distance until
  the first fix; never crashes.
- **Off-route:** proximity advancement simply won't advance; acceptable here — **5e** adds
  detection/reroute. Not faked.
- **End** always available → `Inactive`, browse UI restored, screen-on cleared.

## Testing

- **Pure `navProgress` (JVM, no Android):**
  1. Far from next turn → `stepIndex` unchanged; `distanceToTurnMeters` ≈ haversine.
  2. Within threshold of the upcoming maneuver → `stepIndex` advances by one.
  3. `remainingDistanceMeters` = distance-to-turn + sum of later step distances.
  4. At the final (ARRIVE) point within threshold → `arrived == true`.
- **`NavViewModel` (JVM):** `start(route)` → `Active` with first instruction; a sequence of
  `onLocation(...)` advances steps and updates remaining/ETA; `start` with empty steps stays
  `Inactive`; `end()` → `Inactive`.
- **On-device (emulator route playback ideal):** Start → banner shows next maneuver + distance;
  driving counts distance down and advances instructions; ETA shows; browse UI hidden, Copilot
  kept, screen stays awake; **End** restores browse.

HUD composables + keep-screen-on flag are framework glue — verified on device, not unit-tested.

## Out of scope (later)

- Voice announcements of maneuvers — **5d** (reuses 4b `VoiceOutput`).
- Off-route detection + rerouting — **5e**.
- Snap-to-route progress, lane guidance, speed limit, "then" preview, current speed.
- Copilot awareness of nav state ("what's my next turn?") — later tie-in.

## Definition of Done

- Start a selected route → maneuver banner with arrow + instruction + distance-to-turn;
  steps advance by proximity; ETA + remaining distance shown; **End** returns to browse.
- Browse UI (Gas/Food, route cards) hidden during nav; Copilot kept; screen stays awake.
- `navProgress` + `NavViewModel` unit tests pass; existing app + backend tests stay green.
- Verified on device; no secrets committed; `git status` clean.
