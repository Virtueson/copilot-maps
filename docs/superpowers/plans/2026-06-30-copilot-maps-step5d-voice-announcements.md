# Step 5d — Spoken Turn-by-Turn Announcements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** During live navigation, speak each maneuver aloud — a "prepare" cue ahead of the turn and a "now" cue at the turn, plus start and arrival cues — sharing the existing TTS engine with the copilot.

**Architecture:** A pure decider (`data/NavAnnouncer.kt`) answers "given current progress, what should we say (if anything)?" `NavViewModel` runs it on each location update and emits the resulting line on a `SharedFlow`. `MapScreen` collects the flow and speaks each line on the shared `voiceOutput` using `QUEUE_ADD` so a turn cue queues behind an in-progress copilot reply instead of cutting it off. A nav-HUD mute toggle suppresses future cues.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidX Lifecycle ViewModel, kotlinx-coroutines (`SharedFlow`), JUnit + kotlinx-coroutines-test (unit tests), Android `TextToSpeech`.

## Global Constraints

- minSdk 24, targetSdk 36; Kotlin/Compose project under `android/`.
- Package: `com.virtueson.copilotmaps`.
- Gradle from CLI requires `JAVA_HOME` set first (see commands below); `java` is not on PATH.
- Distance units are **metric** (meters / kilometers).
- Thresholds: prepare = 300 m, now = 40 m (defaults; keep as named params).
- Do NOT commit `local.properties` or `backend/.env`.
- `NavViewModel` must stay free of Android-framework imports (coroutines only) so it remains unit-testable.

**Gradle test command (all tasks):**
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat testDebugUnitTest --console=plain
```
To run a single test class, append e.g. `--tests "com.virtueson.copilotmaps.data.NavAnnouncerTest"`.

---

## File Structure

- **Create** `android/app/src/main/java/com/virtueson/copilotmaps/data/NavAnnouncer.kt` — pure decider + distance formatter.
- **Create** `android/app/src/test/java/com/virtueson/copilotmaps/data/NavAnnouncerTest.kt` — unit tests for the decider.
- **Modify** `android/app/src/main/java/com/virtueson/copilotmaps/voice/VoiceOutput.kt` — add `flush` param to `speak`.
- **Modify** `android/app/src/main/java/com/virtueson/copilotmaps/voice/AndroidVoiceOutput.kt` — honor `flush` (QUEUE_FLUSH vs QUEUE_ADD).
- **Modify** `android/app/src/test/java/com/virtueson/copilotmaps/voice/FakeVoice.kt` — match new `speak` signature, record flush flags.
- **Modify** `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/NavViewModel.kt` — run the decider, expose `announcements: SharedFlow<String>`.
- **Modify** `android/app/src/test/java/com/virtueson/copilotmaps/ui/map/NavViewModelTest.kt` — assert emitted lines.
- **Modify** `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt` — collect announcements, speak with `flush=false`, add mute toggle.

---

## Task 1: Pure announcement decider (`NavAnnouncer.kt`)

**Files:**
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/data/NavAnnouncer.kt`
- Test: `android/app/src/test/java/com/virtueson/copilotmaps/data/NavAnnouncerTest.kt`

**Interfaces:**
- Consumes: `RouteStep` (`instruction`, `maneuver`, `distanceMeters`, `location`), `NavProgress` (`stepIndex`, `distanceToTurnMeters`, `remainingDistanceMeters`, `arrived`) — both already in `data/`.
- Produces:
  - `data class AnnouncerState(startedSpoken: Boolean = false, preparedStep: Int = -1, nowStep: Int = -1, arrivedSpoken: Boolean = false)`
  - `data class AnnouncerResult(state: AnnouncerState, utterance: String?)`
  - `fun nextAnnouncement(steps: List<RouteStep>, progress: NavProgress, state: AnnouncerState, prepareMeters: Int = 300, nowMeters: Int = 40): AnnouncerResult`
  - `fun formatDistance(meters: Int): String`

- [ ] **Step 1: Write the failing tests**

Create `android/app/src/test/java/com/virtueson/copilotmaps/data/NavAnnouncerTest.kt`:

```kotlin
package com.virtueson.copilotmaps.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun step(instruction: String, dist: Int, lat: Double, lng: Double) =
    RouteStep(instruction = instruction, maneuver = "", distanceMeters = dist, location = GeoPoint(lat, lng))

private val steps = listOf(
    step("Head north on Main St", 100, 0.0, 0.000),
    step("Turn right onto Oak Ave", 200, 0.0, 0.010),
    step("Arrive at destination", 0, 0.0, 0.020),
)

private fun progress(index: Int, distToTurn: Int, arrived: Boolean = false) =
    NavProgress(stepIndex = index, distanceToTurnMeters = distToTurn, remainingDistanceMeters = distToTurn, arrived = arrived)

class NavAnnouncerTest {

    @Test
    fun `start cue speaks step zero instruction once`() {
        val r1 = nextAnnouncement(steps, progress(0, 0), AnnouncerState())
        assertEquals("Head north on Main St", r1.utterance)
        // same state, next call must not repeat the start cue
        val r2 = nextAnnouncement(steps, progress(1, 500), r1.state)
        assertNull(r2.utterance)
    }

    @Test
    fun `prepare cue fires once when within prepare distance`() {
        val started = AnnouncerState(startedSpoken = true)
        val r1 = nextAnnouncement(steps, progress(1, 250), started)
        assertEquals("In 250 meters, Turn right onto Oak Ave", r1.utterance)
        val r2 = nextAnnouncement(steps, progress(1, 120), r1.state)
        assertNull(r2.utterance) // already prepared for step 1
    }

    @Test
    fun `now cue fires once when within now distance`() {
        val prepared = AnnouncerState(startedSpoken = true, preparedStep = 1)
        val r1 = nextAnnouncement(steps, progress(1, 30), prepared)
        assertEquals("Turn right onto Oak Ave", r1.utterance)
        val r2 = nextAnnouncement(steps, progress(1, 20), r1.state)
        assertNull(r2.utterance)
    }

    @Test
    fun `jumping straight inside now distance skips a stale prepare cue`() {
        val started = AnnouncerState(startedSpoken = true)
        val r1 = nextAnnouncement(steps, progress(1, 25), started) // never saw prepare range
        assertEquals("Turn right onto Oak Ave", r1.utterance)
        // prepare for step 1 must NOT fire afterwards
        val r2 = nextAnnouncement(steps, progress(1, 200), r1.state)
        assertNull(r2.utterance)
    }

    @Test
    fun `arrived speaks once and suppresses a now cue for the final step`() {
        val s = AnnouncerState(startedSpoken = true, preparedStep = 2)
        val r1 = nextAnnouncement(steps, progress(2, 10, arrived = true), s)
        assertEquals("You have arrived.", r1.utterance)
        val r2 = nextAnnouncement(steps, progress(2, 5, arrived = true), r1.state)
        assertNull(r2.utterance)
    }

    @Test
    fun `empty steps produce no utterance`() {
        val r = nextAnnouncement(emptyList(), progress(0, 0), AnnouncerState())
        assertNull(r.utterance)
    }

    @Test
    fun `formatDistance rounds sensibly`() {
        assertEquals("300 meters", formatDistance(300))
        assertEquals("50 meters", formatDistance(47))
        assertEquals("1.2 kilometers", formatDistance(1234))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run the gradle test command with `--tests "com.virtueson.copilotmaps.data.NavAnnouncerTest"`.
Expected: FAIL — `nextAnnouncement` / `formatDistance` / `AnnouncerState` unresolved.

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/java/com/virtueson/copilotmaps/data/NavAnnouncer.kt`:

```kotlin
package com.virtueson.copilotmaps.data

/** Remembers which cues have already been spoken so none repeats. */
data class AnnouncerState(
    val startedSpoken: Boolean = false,
    val preparedStep: Int = -1,
    val nowStep: Int = -1,
    val arrivedSpoken: Boolean = false,
)

/** New announcer state plus an optional line to speak this update. */
data class AnnouncerResult(val state: AnnouncerState, val utterance: String?)

/**
 * Decide the next thing to say, given current [progress]. At most one line per
 * call; the next location update picks up any remaining cue. Pure — no Android,
 * no TTS — so it is fully unit-testable.
 */
fun nextAnnouncement(
    steps: List<RouteStep>,
    progress: NavProgress,
    state: AnnouncerState,
    prepareMeters: Int = 300,
    nowMeters: Int = 40,
): AnnouncerResult {
    if (steps.isEmpty()) return AnnouncerResult(state, null)

    if (!state.startedSpoken) {
        return AnnouncerResult(state.copy(startedSpoken = true), steps[0].instruction)
    }

    if (progress.arrived && !state.arrivedSpoken) {
        val last = steps.size - 1
        return AnnouncerResult(
            state.copy(arrivedSpoken = true, nowStep = last, preparedStep = last),
            "You have arrived.",
        )
    }

    val i = progress.stepIndex
    val step = steps.getOrNull(i) ?: return AnnouncerResult(state, null)
    val dist = progress.distanceToTurnMeters

    if (dist < nowMeters && state.nowStep != i) {
        // Mark prepare done too, so a fast jump inside the now-radius never
        // back-fires a stale prepare cue for the same step.
        return AnnouncerResult(state.copy(nowStep = i, preparedStep = i), step.instruction)
    }

    if (dist < prepareMeters && state.preparedStep != i) {
        return AnnouncerResult(
            state.copy(preparedStep = i),
            "In ${formatDistance(dist)}, ${step.instruction}",
        )
    }

    return AnnouncerResult(state, null)
}

/** Speak-friendly distance: nearest 50 m below 1 km, else one-decimal km. */
fun formatDistance(meters: Int): String =
    if (meters >= 1000) {
        val km = Math.round(meters / 100.0) / 10.0
        "$km kilometers"
    } else {
        val rounded = ((meters + 25) / 50) * 50
        "$rounded meters"
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run the gradle test command with `--tests "com.virtueson.copilotmaps.data.NavAnnouncerTest"`.
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add "android/app/src/main/java/com/virtueson/copilotmaps/data/NavAnnouncer.kt" "android/app/src/test/java/com/virtueson/copilotmaps/data/NavAnnouncerTest.kt"
git commit -m "feat(app): pure nav announcement decider (Step 5d)"
```

---

## Task 2: VoiceOutput queue mode (`flush` param)

**Files:**
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/voice/VoiceOutput.kt`
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/voice/AndroidVoiceOutput.kt`
- Modify: `android/app/src/test/java/com/virtueson/copilotmaps/voice/FakeVoice.kt`

**Interfaces:**
- Produces: `fun VoiceOutput.speak(text: String, flush: Boolean = true, onDone: () -> Unit = {})`. `flush = true` → `TextToSpeech.QUEUE_FLUSH` (interrupt, current behavior); `flush = false` → `QUEUE_ADD` (queue behind in-progress speech).
- Consumes: nothing new.

> Note: existing copilot call sites (`CopilotViewModel.speak(result.reply){...}`) keep working unchanged because `flush` defaults to `true`. No source change needed there.

- [ ] **Step 1: Update the interface**

In `voice/VoiceOutput.kt`, change the `speak` signature:

```kotlin
interface VoiceOutput {
    fun speak(text: String, flush: Boolean = true, onDone: () -> Unit = {})
    fun stop()
    fun shutdown()
}
```

- [ ] **Step 2: Honor `flush` in the Android engine**

In `voice/AndroidVoiceOutput.kt`, replace the `speak` override:

```kotlin
    override fun speak(text: String, flush: Boolean, onDone: () -> Unit) {
        if (!ready) {
            pending = text to onDone
            return
        }
        val id = "u${counter++}"
        callbacks[id] = onDone
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, mode, null, id)
    }
```

(The `pending?.let { (text, cb) -> speak(text, cb) }` call inside `init` will now resolve to `speak(text, flush = true, cb)` via the default — fine.)

- [ ] **Step 3: Update the fake to match and record flush**

In `test/.../voice/FakeVoice.kt`, replace the `FakeVoiceOutput.speak` override:

```kotlin
class FakeVoiceOutput : VoiceOutput {
    val spoken = mutableListOf<String>()
    val flushFlags = mutableListOf<Boolean>()
    var stopped = false
    var shutdownCalled = false

    override fun speak(text: String, flush: Boolean, onDone: () -> Unit) {
        spoken += text
        flushFlags += flush
        onDone()
    }

    override fun stop() { stopped = true }
    override fun shutdown() { shutdownCalled = true }
}
```

- [ ] **Step 4: Run the full unit test suite to verify nothing broke**

Run the gradle test command (no `--tests` filter).
Expected: PASS — existing copilot voice tests still green with the new signature.

- [ ] **Step 5: Commit**

```bash
git add "android/app/src/main/java/com/virtueson/copilotmaps/voice/VoiceOutput.kt" "android/app/src/main/java/com/virtueson/copilotmaps/voice/AndroidVoiceOutput.kt" "android/app/src/test/java/com/virtueson/copilotmaps/voice/FakeVoice.kt"
git commit -m "feat(app): VoiceOutput queue mode (flush param) for nav cues (Step 5d)"
```

---

## Task 3: NavViewModel emits announcements

**Files:**
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/NavViewModel.kt`
- Test: `android/app/src/test/java/com/virtueson/copilotmaps/ui/map/NavViewModelTest.kt`

**Interfaces:**
- Consumes: `nextAnnouncement`, `AnnouncerState`, `NavProgress` (Task 1); existing `navProgress`, `Route`, `RouteStep`, `GeoPoint`.
- Produces: `val NavViewModel.announcements: SharedFlow<String>` — emits one line per cue, in order: start → (prepare/now per turn) → arrived.

- [ ] **Step 1: Write the failing tests**

Add to `test/.../ui/map/NavViewModelTest.kt` (keep existing tests; add imports + two tests). The new imports at the top of the file:

```kotlin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
```

Add inside the `NavViewModelTest` class:

```kotlin
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `start emits the departure announcement`() = runTest {
        val vm = NavViewModel(nowEpochSeconds = { 1000L })
        val lines = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.announcements.collect { lines.add(it) }
        }

        vm.start(route(steps))

        assertEquals("Head", lines.first())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `onLocation emits a now cue when reaching the turn`() = runTest {
        val vm = NavViewModel(nowEpochSeconds = { 1000L })
        val lines = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.announcements.collect { lines.add(it) }
        }

        vm.start(route(steps))                    // "Head"
        vm.onLocation(GeoPoint(0.0, 0.0100001))   // ~1 m from step 1 -> now cue

        assertEquals("Turn left", lines.last())
    }
```

> Uses the existing `route(...)` / `steps` helpers already defined in this test file (steps: "Head"/DEPART, "Turn left"/TURN_LEFT at lng 0.010, "Arrive"/ARRIVE at lng 0.020).

- [ ] **Step 2: Run the tests to verify they fail**

Run the gradle test command with `--tests "com.virtueson.copilotmaps.ui.map.NavViewModelTest"`.
Expected: FAIL — `announcements` unresolved.

- [ ] **Step 3: Implement emission in NavViewModel**

Edit `main/.../ui/map/NavViewModel.kt`. Add imports:

```kotlin
import com.virtueson.copilotmaps.data.AnnouncerState
import com.virtueson.copilotmaps.data.NavProgress
import com.virtueson.copilotmaps.data.nextAnnouncement
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
```

Add fields alongside the existing `_state` / `route` / `stepIndex`:

```kotlin
    private var announcer = AnnouncerState()
    private val _announcements = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val announcements: SharedFlow<String> = _announcements.asSharedFlow()

    private fun announce(progress: NavProgress) {
        val steps = route?.steps ?: return
        val result = nextAnnouncement(steps, progress, announcer)
        announcer = result.state
        result.utterance?.let { _announcements.tryEmit(it) }
    }
```

In `start(route)`, after setting `_state.value = NavUiState.Active(...)`, reset and fire the start cue:

```kotlin
        announcer = AnnouncerState()
        announce(NavProgress(stepIndex = 0, distanceToTurnMeters = 0, remainingDistanceMeters = route.distanceMeters, arrived = false))
```

In `onLocation(point)`, after the existing `val p = navProgress(r.steps, point, stepIndex)` (and after `stepIndex = p.stepIndex`), add at the end of the method:

```kotlin
        announce(p)
```

In `end()`, add:

```kotlin
        announcer = AnnouncerState()
```

- [ ] **Step 4: Run the tests to verify they pass**

Run the gradle test command with `--tests "com.virtueson.copilotmaps.ui.map.NavViewModelTest"`.
Expected: PASS (existing 4 + new 2 = 6 tests).

- [ ] **Step 5: Commit**

```bash
git add "android/app/src/main/java/com/virtueson/copilotmaps/ui/map/NavViewModel.kt" "android/app/src/test/java/com/virtueson/copilotmaps/ui/map/NavViewModelTest.kt"
git commit -m "feat(app): NavViewModel emits spoken announcement cues (Step 5d)"
```

---

## Task 4: MapScreen speaks cues + mute toggle

**Files:**
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt`

**Interfaces:**
- Consumes: `navViewModel.announcements` (Task 3); shared `voiceOutput` (line ~101) with `speak(text, flush = false)` (Task 2).
- Produces: no new public interface — UI wiring only. Verified on device.

> This task has no unit test (Compose UI + device audio). It is gated by the manual device check in Step 4.

- [ ] **Step 1: Collect announcements and speak them queued**

In `MapScreen.kt`, near the other top-level `LaunchedEffect`s (after the `navViewModel`/`navState` declarations around line 115-124), add nav-voice state and a collector. First add the state declaration next to other `remember`s:

```kotlin
    var navVoiceEnabled by rememberSaveable { mutableStateOf(true) }
```

Then the collector:

```kotlin
    // Speak nav cues on the shared TTS engine, queued behind any copilot reply.
    LaunchedEffect(Unit) {
        navViewModel.announcements.collect { line ->
            if (navVoiceEnabled) voiceOutput.speak(line, flush = false)
        }
    }
```

Ensure these imports exist (add any that are missing):

```kotlin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
```

- [ ] **Step 2: Add a mute toggle to the nav HUD**

Thread `navVoiceEnabled` and a toggle lambda into `RoutingMap` and down to `NavBottomBar`. In the `RoutingMap(...)` call (the `MapUiState.Located` branch, around line 186-210), add two arguments:

```kotlin
                navVoiceEnabled = navVoiceEnabled,
                onToggleNavVoice = { navVoiceEnabled = !navVoiceEnabled },
```

Add matching parameters to the `RoutingMap` composable signature (after `onEndNav`):

```kotlin
    navVoiceEnabled: Boolean,
    onToggleNavVoice: () -> Unit,
```

Where `NavBottomBar(navState, onEnd = onEndNav, ...)` is called (around line 398), pass the toggle through:

```kotlin
            NavBottomBar(
                navState,
                onEnd = onEndNav,
                voiceEnabled = navVoiceEnabled,
                onToggleVoice = onToggleNavVoice,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
```

In the `NavBottomBar` composable definition, add the two parameters and a speaker `IconButton`. Add params to its signature:

```kotlin
    voiceEnabled: Boolean,
    onToggleVoice: () -> Unit,
```

Inside its row, before (or after) the "End" control, add:

```kotlin
            IconButton(onClick = onToggleVoice) {
                Icon(
                    imageVector = if (voiceEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    contentDescription = if (voiceEnabled) "Mute turn voice" else "Unmute turn voice",
                )
            }
```

Add the icon imports if missing:

```kotlin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
```

> If `NavBottomBar`'s current layout doesn't have an obvious row to host the icon, place the `IconButton` adjacent to the existing End button within the same `Row`. Match the existing spacing/style already used in that bar.

- [ ] **Step 3: Compile**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat compileDebugKotlin --console=plain
```
Expected: BUILD SUCCESSFUL. Fix any unresolved-reference errors (usually a missing import listed above).

- [ ] **Step 4: Device verification (manual)**

Build/run on the Pixel-class device (`de92c98f`). Start the backend and `adb reverse tcp:8000 tcp:8000` first. Then:
1. Long-press a destination ~1–2 km away, pick a route, tap **Start**.
2. Confirm the app **speaks the departure line** at start.
3. Drive/playback toward the first turn: confirm a **"In … meters, …"** cue ~300 m out and a **"<instruction>"** cue at the turn.
4. Near the end, confirm **"You have arrived."**
5. While a turn cue is pending, ask the copilot something — confirm the **copilot reply is not cut off**; the turn cue follows it.
6. Tap the **🔇 mute** button — confirm no further turn cues are spoken; tap again to re-enable.

- [ ] **Step 5: Commit**

```bash
git add "android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt"
git commit -m "feat(app): speak nav cues + nav-voice mute toggle (Step 5d)"
```

---

## Self-Review

**Spec coverage:**
- Two-stage cues (prepare 300 m / now 40 m) → Task 1 `nextAnnouncement` + tests.
- Start cue, arrival cue → Task 1 (start-cue + arrived branches) + tests.
- Distance rounding → Task 1 `formatDistance` + test.
- Nav cues queue behind copilot (shared engine, QUEUE_ADD) → Task 2 (`flush`) + Task 4 (`speak(line, flush=false)`); device check Step 4.5.
- Pure decider + thin emit + Compose speaks → Tasks 1/3/4 respectively.
- Mute toggle, default on → Task 4 (`navVoiceEnabled`, `rememberSaveable`, HUD button).
- Dedup / no-repeat, skip-stale-prepare, empty-steps → Task 1 tests.
- No backend changes → confirmed; `RouteStep.instruction` already supplied by Step 5a.

**Placeholder scan:** none — all code steps contain full code; the one judgment call (icon placement in `NavBottomBar`) is bounded with an explicit fallback.

**Type consistency:** `nextAnnouncement` / `AnnouncerState` / `AnnouncerResult` / `formatDistance` signatures identical across Tasks 1 and 3. `speak(text, flush, onDone)` identical across Tasks 2 and 4 and the fake. `announcements: SharedFlow<String>` identical across Tasks 3 and 4. `NavProgress` constructor args (`stepIndex`, `distanceToTurnMeters`, `remainingDistanceMeters`, `arrived`) match `data/NavProgress.kt`.
