# Step 5d — Spoken turn-by-turn navigation announcements

**Date:** 2026-06-30
**Status:** Design approved, pending spec review
**Depends on:** Step 5a (RouteStep data), 5b (continuous location), 5c (navigation mode + NavViewModel), 4b (VoiceOutput/TextToSpeech).

## Goal

During live navigation, speak each maneuver aloud so the user can drive without
looking at the screen. Two cues per turn: a **prepare** cue ahead of the turn
("In 300 meters, turn right onto Main St") and a **now** cue at the turn
("Turn right onto Main St"), plus a start cue and an arrival cue.

Personal-use app — no store/accessibility compliance work; just needs to work
well on the user's own (Google-services) phone.

## Decisions (from brainstorm)

- **Timing:** two-stage, distance-cued. Prepare at ~300 m, now at ~40 m.
- **TTS conflict with copilot:** nav and copilot share the single existing
  `AndroidVoiceOutput` instance. Nav cues use `QUEUE_ADD`, so a turn cue **queues
  behind** an in-progress copilot reply rather than cutting it off.
- **Structure:** pure decider in `data/` + thin ViewModel emitting events +
  Compose layer does the actual speaking (mirrors the existing
  `navProgress` (pure) → `NavViewModel` (thin) → `MapScreen` (Android) split).
- **Mute:** a 🔊/🔇 toggle on the nav HUD silences turn voice without ending
  navigation. Default on.

## Architecture

```
location update
   │
   ▼
NavViewModel.onLocation(point)
   ├─ navProgress(steps, point, stepIndex)        // existing, pure
   └─ nextAnnouncement(steps, progress, state)    // NEW, pure
         │ returns AnnouncerResult(state', utterance?)
         ▼
   if utterance != null  ->  announcements.emit(utterance)   // SharedFlow<String>
         │
         ▼
MapScreen LaunchedEffect collects announcements
   └─ if (navVoiceEnabled) voiceOutput.speak(line, flush = false)   // QUEUE_ADD
```

### 1. Pure decider — `android/.../data/NavAnnouncer.kt`

```kotlin
data class AnnouncerState(
    val startedSpoken: Boolean = false,
    val preparedStep: Int = -1,   // last step index a "prepare" cue fired for
    val nowStep: Int = -1,        // last step index a "now" cue fired for
    val arrivedSpoken: Boolean = false,
)

data class AnnouncerResult(val state: AnnouncerState, val utterance: String?)

fun nextAnnouncement(
    steps: List<RouteStep>,
    progress: NavProgress,
    state: AnnouncerState,
    prepareMeters: Int = 300,
    nowMeters: Int = 40,
): AnnouncerResult
```

Decision order, evaluated against the already-computed `progress`
(`stepIndex`, `distanceToTurnMeters`, `arrived`):

1. **Empty steps** → return state unchanged, `utterance = null`.
2. **Start cue** — if `!startedSpoken`: utterance = step 0's instruction
   (e.g. "Head north on Main St"); set `startedSpoken = true`. Returned on the
   first call (from `start()` — see below). One cue.
3. **Arrived** — if `progress.arrived && !arrivedSpoken`: utterance =
   "You have arrived."; set `arrivedSpoken = true`. One cue.
4. **Now cue** — if `distanceToTurn < nowMeters` and `nowStep != stepIndex`:
   utterance = the step's instruction ("Turn right onto Main St"); set
   `nowStep = stepIndex` **and** `preparedStep = stepIndex` (so a fast jump
   straight inside `nowMeters` doesn't later fire a stale prepare cue).
5. **Prepare cue** — else if `distanceToTurn < prepareMeters` and
   `preparedStep != stepIndex`: utterance =
   "In {rounded} meters, {instruction}"; set `preparedStep = stepIndex`.
6. Otherwise `utterance = null`.

Only **one** utterance per call (highest-priority rule wins); the next location
update picks up any remaining cue.

**Distance rounding** (`formatDistance`, pure helper):
- `>= 1000 m` → "{x.x} kilometers" (one decimal).
- else → round to nearest 50 m → "{n} meters".

`maneuver` is available on `RouteStep` but the spoken text uses the
human-readable `instruction` from Google (already turn-aware, e.g. "Turn left
onto ..."); `maneuver` is not needed for speech.

### 2. `NavViewModel` (ui/map/NavViewModel.kt)

- Add `private var announcer = AnnouncerState()`.
- Add `private val _announcements = MutableSharedFlow<String>(replay = 0,
  extraBufferCapacity = 8, onBufferOverflow = DROP_OLDEST)` exposed as
  `val announcements: SharedFlow<String>`.
- A private `announce(progress: NavProgress)` helper: call `nextAnnouncement`,
  store the new `announcer` state, and `tryEmit(utterance)` if non-null.
- `start(route)`: after building the initial `Active` state, reset
  `announcer = AnnouncerState()` and emit the start cue by running
  `announce(NavProgress(stepIndex = 0, distanceToTurnMeters = 0,
  remainingDistanceMeters = route.distanceMeters, arrived = false))`.
- `onLocation(point)`: after computing `progress` (already done), call
  `announce(progress)`.
- `end()`: reset `announcer = AnnouncerState()`.

No Android imports added to `NavViewModel` (SharedFlow is coroutines, not
Android) — stays unit-testable.

### 3. `VoiceOutput` queue mode

`voice/VoiceOutput.kt`:
```kotlin
fun speak(text: String, flush: Boolean = true, onDone: () -> Unit = {})
```
`voice/AndroidVoiceOutput.kt`: map `flush=true → TextToSpeech.QUEUE_FLUSH`,
`flush=false → TextToSpeech.QUEUE_ADD`. The existing `pending`/`onDone` plumbing
is unchanged. Copilot callers keep the default (`flush = true`).

### 4. `MapScreen` wiring

- Add `var navVoiceEnabled by rememberSaveable { mutableStateOf(true) }`.
- `LaunchedEffect(Unit) { navViewModel.announcements.collect { line ->
  if (navVoiceEnabled) voiceOutput.speak(line, flush = false) } }`.
- Nav HUD (`NavBottomBar` or `NavBanner`) gains a 🔊/🔇 `IconButton` toggling
  `navVoiceEnabled`. When muting, also `voiceOutput.stop()` is **not** called
  (a copilot reply may be playing); muting only suppresses future nav cues.
- `keepScreenOn` during nav already in place (5c).

## Error handling / edge cases

- **TTS not ready** at the first cue: `AndroidVoiceOutput` already buffers one
  `pending` utterance until the engine initializes; the engine is constructed at
  `MapScreen` entry, so it is ready well before navigation starts. Acceptable.
- **GPS jitter** re-entering a step's radius: dedup is per-step-index via
  `nowStep`/`preparedStep`, so a cue never repeats for the same step. If
  `navProgress` were to move the index backwards (it does not — it only
  advances), the older step's flags would still be set, so no spurious repeat.
- **Very short legs** (first turn already < 300 m at start): the prepare cue
  fires on the first `onLocation` (threshold crossing handles it); the start cue
  still speaks step 0 first.
- **Mute mid-trip:** suppresses future cues only; already-queued/playing audio is
  left alone.

## Testing

Pure unit tests on `nextAnnouncement` (no Android, no TTS):
- start cue fires once on first call, not again.
- prepare cue fires once when crossing < 300 m, not repeated.
- now cue fires once when crossing < 40 m, not repeated.
- jumping straight inside 40 m fires the now cue and suppresses a later prepare.
- arrived fires "You have arrived." once.
- empty steps → no utterance, no crash.
- `formatDistance`: 300 → "300 meters", 1234 → "1.2 kilometers", 47 → "50 meters".

`NavViewModel` test with a collected `announcements` flow:
- `start()` emits the departure line.
- a sequence of `onLocation` calls emits prepare → now → arrived in order.

No backend changes (Step 5a already supplies `RouteStep.instruction`).

## Out of scope (later)

- Off-route detection + reroute (Step 5e).
- Copilot ↔ nav-state context ("what's my next turn?").
- Localized / non-metric distance units.
- Lane guidance, speed-limit voice, "then" chained instructions.

## Definition of done

- `nextAnnouncement` + `formatDistance` implemented and unit-tested (TDD).
- `NavViewModel` emits cues via `announcements`; tested with a fake collector.
- `VoiceOutput.speak` supports `flush`; `AndroidVoiceOutput` honors it.
- `MapScreen` speaks queued nav cues on the shared engine and has a working
  nav-voice mute toggle.
- Verified on the Pixel-class device: prepare + now + arrival spoken during a
  real drive/route; a copilot reply is not cut off by a turn cue.

## Revision — I1 now-cue safety net (2026-06-30, post-build)

The now cue (40 m) sits just above `navProgress`'s 30 m step-advance threshold,
leaving only a ~10 m window for the cue to fire. At driving speed with sparse /
laggy GPS, a single fix can jump that window, advancing past the turn so its
"Turn left" confirmation is never spoken. Fix: `nextAnnouncement` gained a
**safety net** evaluated after the arrived check and before the now/prepare
checks — if the step index has advanced past a real turn (`i >= 2`) whose now
cue never fired (`state.nowStep < i - 1`), it speaks that turn's `instruction`
the moment we register reaching it, then marks `nowStep = i - 1` (fires once,
never for the departure step, never stale after arrival). The reliable 300 m
prepare cue still gives advance warning; the safety net guarantees the at-the-
turn confirmation is spoken regardless of GPS spacing. Tested: a skipped-window
case, the departure-advance no-cue case, and the post-arrival no-stale-cue case.

## Revision — turn chaining for tightly-clustered turns (2026-06-30, post-build)

Originally listed as out of scope ("then chained instructions"). Added because
the safety net only recovers one skipped turn per GPS update, so a cluster of
turns a few metres apart (roundabout exits, complex junctions) could still drop
announcements at speed. Chaining folds a cluster into ONE spoken line delivered
by the reliable 300 m prepare cue, so nothing is dropped even under GPS lag.

**Detection is free from existing data:** `RouteStep.distanceMeters` is the
distance from that maneuver to the next, so `steps[i].distanceMeters < chainMeters`
means turn i+1 is within `chainMeters` of turn i — a cluster.

**Behavior:**
- When building the **prepare** cue and the **now** cue for step `i`, chain
  forward: append `", then " + steps[j+1].instruction` while
  `steps[j].distanceMeters < chainMeters`, up to `maxChain` turns total, and
  stop before an `ARRIVE` step (the arrival cue stays separate).
- `chainMeters = 40` (matches the now threshold), `maxChain = 3`
  (e.g. "Turn left, then turn right, then turn left"); a 4th+ clustered turn
  falls to the following cue.
- The cue that fires marks every chained step announced (`preparedStep` /
  `nowStep` set to the last chained index), so the folded turns don't re-announce
  individually and the safety net won't re-fire them.
- Prepare speaks "In 300 meters, <chain>"; now speaks "<chain>".
- No cluster (large gaps) → chain is just the single instruction — fully
  backward-compatible with the existing cues and tests.

**Tests:** 2-turn cluster, 3-turn cluster, cap at 3 (4 clustered → 3 spoken),
no-chain when gaps are large, don't chain into ARRIVE, prepare-cue chaining.
