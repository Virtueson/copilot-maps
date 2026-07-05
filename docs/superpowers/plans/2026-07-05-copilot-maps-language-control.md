# In-App Language Control (EN / ID) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a single in-app EN/ID language choice that makes navigation (STT, route instructions, wrapper words, voice) consistent in the chosen language, while the Copilot replies and speaks in whichever language the user actually used.

**Architecture:** A persisted `LanguageSettings` (SharedPreferences + `StateFlow<AppLanguage>`) is the one source of truth, read directly by the components that need it — no AppCompat, no activity recreation. The backend gains a route `languageCode` passthrough to Google and a small `id`/`en` detector that tags each Copilot reply. `NavAnnouncer` stays a pure function by taking a `NavPhrases` bundle built from localized string resources.

**Tech Stack:** Backend — FastAPI, Pydantic v2, pytest, httpx. App — Kotlin, Jetpack Compose, MVVM (manual DI factories), Moshi/Retrofit, JUnit4 + kotlinx-coroutines-test, Android TextToSpeech / SpeechRecognizer.

## Global Constraints

- **NEVER stage or commit `backend/.env` or `local.properties`** (API keys). Confirm `git status` excludes them before every commit. Never stage scratch files (`test_output.txt`, `overlay.jpeg`, screenshots).
- **Only English and Indonesian ship.** `AppLanguage` has exactly `ENGLISH("en")` and `INDONESIAN("id")` (BCP-47 tags).
- **`NavAnnouncer` must remain pure** — no Android imports, no resource lookups inside it. It receives a `NavPhrases` value.
- **Backend unit tests must run against stubs** (override `get_planner` / `get_copilot`); never hit live Google/LLM.
- **App unit tests are plain JVM JUnit** — no Android framework (no `Context`, no `SharedPreferences`) in unit-tested code paths. Android-dependent glue (`LanguageSettings`, the `NavPhrases` resource builder, `AndroidVoice*`) is verified by compilation + manual device testing, not unit tests.
- **Backend test command:** `cd backend && .\.venv\Scripts\python.exe -m pytest -q`
- **Android test command (JAVA_HOME required):**
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  cd "D:\Russell\Data Science Job\AI Maps\android"
  .\gradlew.bat testDebugUnitTest --console=plain
  ```

---

## Task 1: Route `languageCode` passthrough (backend)

**Files:**
- Modify: `backend/app/models.py` (`RoutePlanRequest`)
- Modify: `backend/app/planners/base.py` (`RoutePlanner` protocol)
- Modify: `backend/app/planners/stub.py` (`StubRoutePlanner.plan`)
- Modify: `backend/app/planners/google.py` (`_build_body` + `plan`)
- Modify: `backend/app/routers/routes.py` (forward `language_code`)
- Test: `backend/tests/test_google_planner.py`, `backend/tests/test_routes.py`

**Interfaces:**
- Consumes: nothing new.
- Produces: `RoutePlanRequest.language_code: str | None`; `RoutePlanner.plan(origin, destination, language_code=None)`; `google._build_body(origin, destination, language_code) -> dict`.

- [ ] **Step 1: Write the failing test — Google body includes/omits `languageCode`**

Add to `backend/tests/test_google_planner.py`:

```python
from app.models import LatLng
from app.planners.google import _build_body


def test_build_body_includes_language_code_when_set():
    body = _build_body(LatLng(lat=-6.2, lng=106.8), LatLng(lat=-6.3, lng=106.9), "id")
    assert body["languageCode"] == "id"


def test_build_body_omits_language_code_when_none():
    body = _build_body(LatLng(lat=-6.2, lng=106.8), LatLng(lat=-6.3, lng=106.9), None)
    assert "languageCode" not in body
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && .\.venv\Scripts\python.exe -m pytest tests/test_google_planner.py -q`
Expected: FAIL — `ImportError: cannot import name '_build_body'`.

- [ ] **Step 3: Extract `_build_body` and thread `language_code` in `google.py`**

In `backend/app/planners/google.py`, add the helper and use it; add the parameter to `plan`:

```python
def _build_body(origin: LatLng, destination: LatLng, language_code: str | None) -> dict:
    body = {
        "origin": {"location": {"latLng": {"latitude": origin.lat, "longitude": origin.lng}}},
        "destination": {"location": {"latLng": {"latitude": destination.lat, "longitude": destination.lng}}},
        "travelMode": "DRIVE",
        "routingPreference": "TRAFFIC_AWARE",
        "computeAlternativeRoutes": True,
        "extraComputations": ["TRAFFIC_ON_POLYLINE"],
    }
    if language_code:
        body["languageCode"] = language_code
    return body
```

Change the method signature and body construction:

```python
    async def plan(self, origin: LatLng, destination: LatLng, language_code: str | None = None) -> list[Route]:
        body = _build_body(origin, destination, language_code)
        headers = {
```

(Leave the rest of `plan` unchanged.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && .\.venv\Scripts\python.exe -m pytest tests/test_google_planner.py -q`
Expected: PASS.

- [ ] **Step 5: Add `language_code` to the request model, base protocol, stub, and router**

`backend/app/models.py` — add field to `RoutePlanRequest`:

```python
class RoutePlanRequest(BaseModel):
    origin: LatLng
    destination: LatLng
    language_code: str | None = None
```

`backend/app/planners/base.py`:

```python
class RoutePlanner(Protocol):
    async def plan(
        self, origin: LatLng, destination: LatLng, language_code: str | None = None
    ) -> list[Route]: ...
```

`backend/app/planners/stub.py` — accept and ignore the arg (signature parity):

```python
    async def plan(
        self, origin: LatLng, destination: LatLng, language_code: str | None = None
    ) -> list[Route]:
```

`backend/app/routers/routes.py` — forward it:

```python
        routes = await planner.plan(request.origin, request.destination, request.language_code)
```

- [ ] **Step 6: Write the failing test — router accepts and tolerates `language_code`**

Add to `backend/tests/test_routes.py`:

```python
def test_plan_accepts_language_code():
    body = {
        "origin": {"lat": 1.2966, "lng": 103.7764},
        "destination": {"lat": 1.3521, "lng": 103.8198},
        "language_code": "id",
    }
    resp = client.post("/routes/plan", json=body)
    assert resp.status_code == 200
    assert len(resp.json()["routes"]) == 3
```

- [ ] **Step 7: Run the full backend suite**

Run: `cd backend && .\.venv\Scripts\python.exe -m pytest -q`
Expected: PASS (all existing + new).

- [ ] **Step 8: Commit**

```bash
git add backend/app/models.py backend/app/planners/base.py backend/app/planners/stub.py backend/app/planners/google.py backend/app/routers/routes.py backend/tests/test_google_planner.py backend/tests/test_routes.py
git commit -m "feat(backend): route languageCode passthrough to Google Routes"
```

---

## Task 2: Copilot language detector, prompt rule, and reply `language` (backend)

**Files:**
- Create: `backend/app/copilot/language.py`
- Modify: `backend/app/copilot/prompt.py` (`PERSONA`)
- Modify: `backend/app/models.py` (`CopilotAskResponse`)
- Modify: `backend/app/routers/copilot.py` (set `language`)
- Test: `backend/tests/test_copilot_language.py` (new), `backend/tests/test_copilot.py` (router; create if absent)

**Interfaces:**
- Consumes: nothing new.
- Produces: `detect_language(text: str) -> str` (returns `"id"` or `"en"`); `CopilotAskResponse.language: str`.

- [ ] **Step 1: Write the failing test — the detector**

Create `backend/tests/test_copilot_language.py`:

```python
from app.copilot.language import detect_language


def test_detects_indonesian():
    assert detect_language("Belok kiri di jalan berikutnya lalu lurus") == "id"


def test_detects_english():
    assert detect_language("Turn left at the next street then go straight") == "en"


def test_empty_defaults_to_english():
    assert detect_language("") == "en"


def test_ambiguous_short_defaults_to_english():
    assert detect_language("OK") == "en"
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && .\.venv\Scripts\python.exe -m pytest tests/test_copilot_language.py -q`
Expected: FAIL — module `app.copilot.language` does not exist.

- [ ] **Step 3: Implement the detector**

Create `backend/app/copilot/language.py`:

```python
"""Tiny, dependency-free Indonesian-vs-English detector for spoken Copilot replies.

Only two languages ship, so a high-frequency Indonesian marker-word count is enough.
Returns "id" when Indonesian markers clearly dominate, else "en" (the safe default).
"""

import re

_ID_MARKERS = {
    "yang", "tidak", "ada", "dari", "ke", "di", "dan", "atau", "dengan",
    "belok", "kanan", "kiri", "lurus", "terus", "menit", "kilometer", "meter",
    "jalan", "sampai", "tujuan", "macet", "lewat", "menuju", "sekitar", "depan",
    "anda", "saya", "ya", "tidak", "sudah", "belum", "akan",
}


def detect_language(text: str) -> str:
    words = re.findall(r"[a-zA-Z]+", text.lower())
    if not words:
        return "en"
    hits = sum(1 for w in words if w in _ID_MARKERS)
    # Require at least 2 marker hits, or 1 hit in a very short reply, to claim Indonesian.
    if hits >= 2 or (hits >= 1 and len(words) <= 3):
        return "id"
    return "en"
```

- [ ] **Step 4: Run the detector test to verify it passes**

Run: `cd backend && .\.venv\Scripts\python.exe -m pytest tests/test_copilot_language.py -q`
Expected: PASS.

- [ ] **Step 5: Add the persona language rule**

In `backend/app/copilot/prompt.py`, append one sentence to `PERSONA`:

```python
PERSONA = (
    "You are Copilot, a concise in-car navigation assistant. Answer in one or two "
    "short, spoken-style sentences — the driver will hear this aloud later. Use the "
    "current trip context for route and traffic questions. Use the search_places tool "
    "to find gas, food, or other places on the route or nearby. If you don't have the "
    "data, say so briefly. Reply in the same language the user used."
)
```

- [ ] **Step 6: Add `language` to the response model and set it in the router**

`backend/app/models.py`:

```python
class CopilotAskResponse(BaseModel):
    reply: str
    language: str = "en"
```

`backend/app/routers/copilot.py`:

```python
from app.copilot.language import detect_language
from app.models import CopilotAskRequest, CopilotAskResponse
...
    reply = await copilot.ask(request.messages, request.context)
    ...
    return CopilotAskResponse(reply=reply, language=detect_language(reply))
```

(Keep the existing `try/except CopilotError` around the `ask` call.)

- [ ] **Step 7: Write the failing test — router returns a `language` field**

Create `backend/tests/test_copilot.py` (or add to the existing copilot router test):

```python
from fastapi.testclient import TestClient

from app.config import get_copilot
from app.main import app


class _FakeCopilot:
    def __init__(self, reply):
        self._reply = reply

    async def ask(self, messages, context):
        return self._reply


def _body():
    return {
        "messages": [{"role": "user", "content": "test"}],
        "context": {"origin": {"lat": 1.0, "lng": 2.0}},
    }


def test_reply_language_indonesian():
    app.dependency_overrides[get_copilot] = lambda: _FakeCopilot("Belok kiri lalu lurus terus")
    try:
        resp = TestClient(app).post("/copilot/ask", json=_body())
        assert resp.status_code == 200
        assert resp.json()["language"] == "id"
    finally:
        app.dependency_overrides.pop(get_copilot, None)


def test_reply_language_english():
    app.dependency_overrides[get_copilot] = lambda: _FakeCopilot("Turn left then continue straight")
    try:
        resp = TestClient(app).post("/copilot/ask", json=_body())
        assert resp.status_code == 200
        assert resp.json()["language"] == "en"
    finally:
        app.dependency_overrides.pop(get_copilot, None)
```

- [ ] **Step 8: Run the full backend suite**

Run: `cd backend && .\.venv\Scripts\python.exe -m pytest -q`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/app/copilot/language.py backend/app/copilot/prompt.py backend/app/models.py backend/app/routers/copilot.py backend/tests/test_copilot_language.py backend/tests/test_copilot.py
git commit -m "feat(backend): Copilot replies in user language + reply-language tag"
```

---

## Task 3: `AppLanguage` + `LanguageSettings` (app)

**Files:**
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/data/AppLanguage.kt`
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/data/LanguageSettings.kt`
- Test: `android/app/src/test/java/com/virtueson/copilotmaps/data/AppLanguageTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `enum AppLanguage(tag)` with `locale`, `AppLanguage.forTag(String?): AppLanguage?`, `AppLanguage.fromLocale(Locale): AppLanguage`; `class LanguageSettings(context)` exposing `language: StateFlow<AppLanguage>` and `set(AppLanguage)`.

- [ ] **Step 1: Write the failing test — `AppLanguage` mapping (pure)**

Create `android/app/src/test/java/com/virtueson/copilotmaps/data/AppLanguageTest.kt`:

```kotlin
package com.virtueson.copilotmaps.data

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLanguageTest {
    @Test fun tags_are_bcp47() {
        assertEquals("en", AppLanguage.ENGLISH.tag)
        assertEquals("id", AppLanguage.INDONESIAN.tag)
    }

    @Test fun forTag_maps_known_and_null_for_unknown() {
        assertEquals(AppLanguage.INDONESIAN, AppLanguage.forTag("id"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.forTag("en"))
        assertNull(AppLanguage.forTag("fr"))
        assertNull(AppLanguage.forTag(null))
    }

    @Test fun fromLocale_picks_indonesian_for_id_else_english() {
        assertEquals(AppLanguage.INDONESIAN, AppLanguage.fromLocale(Locale.forLanguageTag("id")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLocale(Locale.US))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run the Android test command (see header). Expected: FAIL — `AppLanguage` unresolved.

- [ ] **Step 3: Implement `AppLanguage` (pure)**

Create `android/app/src/main/java/com/virtueson/copilotmaps/data/AppLanguage.kt`:

```kotlin
package com.virtueson.copilotmaps.data

import java.util.Locale

/** The two languages the app supports. [tag] is a BCP-47 code. */
enum class AppLanguage(val tag: String) {
    ENGLISH("en"),
    INDONESIAN("id");

    val locale: Locale get() = Locale.forLanguageTag(tag)

    companion object {
        fun forTag(tag: String?): AppLanguage? = entries.firstOrNull { it.tag == tag }

        /** Indonesian iff the locale's language is Indonesian; English otherwise. */
        fun fromLocale(locale: Locale): AppLanguage =
            if (locale.language == Locale.forLanguageTag("id").language) INDONESIAN else ENGLISH
    }
}
```

Note: `Locale.forLanguageTag("id").language` resolves to `"in"` on the JVM (legacy code), which is exactly what `Locale("id").language` yields — comparing the two keeps the mapping correct on Android.

- [ ] **Step 4: Run the test to verify it passes**

Run the Android test command. Expected: PASS.

- [ ] **Step 5: Implement `LanguageSettings` (Android glue, no unit test)**

Create `android/app/src/main/java/com/virtueson/copilotmaps/data/LanguageSettings.kt`:

```kotlin
package com.virtueson.copilotmaps.data

import android.content.Context
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persisted EN/ID choice, the single source of truth for app language. */
class LanguageSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("language", Context.MODE_PRIVATE)

    private val _language = MutableStateFlow(load())
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    fun set(lang: AppLanguage) {
        prefs.edit().putString(KEY, lang.tag).apply()
        _language.value = lang
    }

    private fun load(): AppLanguage {
        val saved = prefs.getString(KEY, null)
        return AppLanguage.forTag(saved) ?: AppLanguage.fromLocale(Locale.getDefault())
    }

    private companion object {
        const val KEY = "app_language"
    }
}
```

- [ ] **Step 6: Verify it compiles**

Run: `.\gradlew.bat compileDebugKotlin --console=plain` (with `JAVA_HOME` set).
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/virtueson/copilotmaps/data/AppLanguage.kt android/app/src/main/java/com/virtueson/copilotmaps/data/LanguageSettings.kt android/app/src/test/java/com/virtueson/copilotmaps/data/AppLanguageTest.kt
git commit -m "feat(app): AppLanguage + persisted LanguageSettings"
```

---

## Task 4: `NavPhrases` + pure `NavAnnouncer` localization (app)

**Files:**
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/data/NavPhrases.kt`
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/data/NavAnnouncer.kt`
- Create: `android/app/src/main/res/values-in/strings.xml`
- Modify: `android/app/src/main/res/values/strings.xml`
- Test: the existing NavAnnouncer unit test file (add Indonesian cases); adjust existing calls to pass `EnglishNavPhrases`.

**Interfaces:**
- Consumes: nothing.
- Produces: `data class NavPhrases(inPrefix, thenJoiner, metersUnit, kilometersUnit, arrived)`; `val EnglishNavPhrases: NavPhrases`; `navPhrases(context, locale): NavPhrases` (resource-backed builder). `nextAnnouncement(..., phrases: NavPhrases = EnglishNavPhrases, ...)` and `formatDistance(meters, phrases)`.

**Interfaces (consumed by later tasks):** Task 7's `NavViewModel` calls `nextAnnouncement(steps, progress, state, phrases)`; MapScreen builds `NavPhrases` via `navPhrases(context, language.locale)`.

- [ ] **Step 1: Write the failing test — Indonesian phrases produce Indonesian cues**

Add to the existing NavAnnouncer test file (`android/app/src/test/java/com/virtueson/copilotmaps/data/NavAnnouncerTest.kt`). Define an Indonesian phrases fixture and assert:

```kotlin
private val idPhrases = NavPhrases(
    inPrefix = { d -> "Dalam $d, " },
    thenJoiner = ", lalu ",
    metersUnit = "meter",
    kilometersUnit = "kilometer",
    arrived = "Anda telah sampai di tujuan.",
)

@Test fun prepare_cue_uses_indonesian_phrases() {
    val steps = listOf(
        RouteStep("Mulai", "DEPART", 1000, GeoPoint(0.0, 0.0)),
        RouteStep("belok kanan", "TURN_RIGHT", 500, GeoPoint(0.0, 0.01)),
        RouteStep("tiba", "ARRIVE", 0, GeoPoint(0.0, 0.02)),
    )
    val state = AnnouncerState(startedSpoken = true)
    val progress = NavProgress(stepIndex = 1, distanceToTurnMeters = 250, remainingDistanceMeters = 250, arrived = false)
    val result = nextAnnouncement(steps, progress, state, idPhrases)
    assertEquals("Dalam 250 meter, belok kanan", result.utterance)
}

@Test fun arrived_cue_uses_indonesian_phrases() {
    val steps = listOf(RouteStep("Mulai", "DEPART", 0, GeoPoint(0.0, 0.0)))
    val state = AnnouncerState(startedSpoken = true)
    val progress = NavProgress(stepIndex = 0, distanceToTurnMeters = 0, remainingDistanceMeters = 0, arrived = true)
    val result = nextAnnouncement(steps, progress, state, idPhrases)
    assertEquals("Anda telah sampai di tujuan.", result.utterance)
}
```

(Match the real `RouteStep`/`NavProgress` constructor argument order used elsewhere in the file; adjust if the existing fixtures differ.)

- [ ] **Step 2: Run it to verify it fails**

Run the Android test command. Expected: FAIL — `NavPhrases` unresolved / `nextAnnouncement` arity mismatch.

- [ ] **Step 3: Create `NavPhrases` with an English default and a resource builder**

Create `android/app/src/main/java/com/virtueson/copilotmaps/data/NavPhrases.kt`:

```kotlin
package com.virtueson.copilotmaps.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import com.virtueson.copilotmaps.R

/** Localized wrapper words that surround Google's own (already-localized) instruction. */
data class NavPhrases(
    val inPrefix: (String) -> String,   // e.g. { d -> "In $d, " }
    val thenJoiner: String,             // ", then "
    val metersUnit: String,             // "meters"
    val kilometersUnit: String,         // "kilometers"
    val arrived: String,                // "You have arrived."
)

/** Fallback used by unit tests and as the default parameter — English. */
val EnglishNavPhrases = NavPhrases(
    inPrefix = { d -> "In $d, " },
    thenJoiner = ", then ",
    metersUnit = "meters",
    kilometersUnit = "kilometers",
    arrived = "You have arrived.",
)

/** Build [NavPhrases] from string resources for [locale], independent of the system locale. */
fun navPhrases(context: Context, locale: Locale): NavPhrases {
    val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
    val res = context.createConfigurationContext(config).resources
    return NavPhrases(
        inPrefix = { d -> res.getString(R.string.nav_in_prefix, d) },
        thenJoiner = res.getString(R.string.nav_then_joiner),
        metersUnit = res.getString(R.string.nav_meters),
        kilometersUnit = res.getString(R.string.nav_kilometers),
        arrived = res.getString(R.string.nav_arrived),
    )
}
```

- [ ] **Step 4: Add string resources**

`android/app/src/main/res/values/strings.xml` — add (keep `app_name`):

```xml
    <string name="nav_in_prefix">In %s, </string>
    <string name="nav_then_joiner">, then </string>
    <string name="nav_meters">meters</string>
    <string name="nav_kilometers">kilometers</string>
    <string name="nav_arrived">You have arrived.</string>
```

Create `android/app/src/main/res/values-in/strings.xml` (`values-in` is the Android resource qualifier for Indonesian):

```xml
<resources>
    <string name="nav_in_prefix">Dalam %s, </string>
    <string name="nav_then_joiner">, lalu </string>
    <string name="nav_meters">meter</string>
    <string name="nav_kilometers">kilometer</string>
    <string name="nav_arrived">Anda telah sampai di tujuan.</string>
</resources>
```

- [ ] **Step 5: Thread `NavPhrases` through the pure `NavAnnouncer`**

In `android/app/src/main/java/com/virtueson/copilotmaps/data/NavAnnouncer.kt`:

- Add `phrases: NavPhrases = EnglishNavPhrases` as a parameter of `nextAnnouncement` (place it right after `state`).
- Replace the hardcoded strings:
  - Arrived line: `phrases.arrived` instead of `"You have arrived."`.
  - Prepare line: `"${phrases.inPrefix(formatDistance(dist, phrases))}${chain.text}"` instead of `"In ${formatDistance(dist)}, ${chain.text}"`.
- Pass `phrases` into `chainFrom(...)` and use `phrases.thenJoiner` instead of the literal `", then "`.
- Change `formatDistance` to take phrases and use the unit strings:

```kotlin
fun formatDistance(meters: Int, phrases: NavPhrases = EnglishNavPhrases): String =
    if (meters >= 1000) {
        val km = Math.round(meters / 100.0) / 10.0
        "$km ${phrases.kilometersUnit}"
    } else {
        val rounded = ((meters + 25) / 50) * 50
        "$rounded ${phrases.metersUnit}"
    }
```

And update `chainFrom`:

```kotlin
private fun chainFrom(steps: List<RouteStep>, i: Int, chainMeters: Int, maxChain: Int, joiner: String): Chain {
    ...
        sb.append(joiner).append(next.instruction)
    ...
}
```

Update both `chainFrom(...)` call sites inside `nextAnnouncement` to pass `phrases.thenJoiner`.

- [ ] **Step 6: Update existing NavAnnouncer tests that call the changed signatures**

Any existing test that calls `formatDistance(x)` still compiles (default param). Existing `nextAnnouncement(steps, progress, state, ...)` calls that passed `prepareMeters`/`nowMeters` positionally must now insert `EnglishNavPhrases` before those, or switch to named arguments. Prefer named arguments where a test passes tuning params, e.g. `nextAnnouncement(steps, progress, state, prepareMeters = 300)`.

- [ ] **Step 7: Run the Android unit tests**

Run the Android test command. Expected: PASS (existing English cases + new Indonesian cases).

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/virtueson/copilotmaps/data/NavPhrases.kt android/app/src/main/java/com/virtueson/copilotmaps/data/NavAnnouncer.kt android/app/src/main/res/values/strings.xml android/app/src/main/res/values-in/strings.xml android/app/src/test/java/com/virtueson/copilotmaps/data/NavAnnouncerTest.kt
git commit -m "feat(app): localize nav announcement wrapper via NavPhrases (pure)"
```

---

## Task 5: Per-utterance TTS locale + STT language provider (app)

**Files:**
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/voice/VoiceOutput.kt`
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/voice/AndroidVoiceOutput.kt`
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/voice/AndroidVoiceInput.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `VoiceOutput.speak(text, flush = true, locale: Locale? = null, onDone = {})`; `AndroidVoiceInput(context, languageProvider: () -> Locale = { Locale.getDefault() })`.

- [ ] **Step 1: Add `locale` to the `VoiceOutput` interface**

`android/app/src/main/java/com/virtueson/copilotmaps/voice/VoiceOutput.kt`:

```kotlin
package com.virtueson.copilotmaps.voice

import java.util.Locale

/** One job: speak a line of text aloud. Easy to fake in tests. */
interface VoiceOutput {
    fun speak(text: String, flush: Boolean = true, locale: Locale? = null, onDone: () -> Unit = {})
    fun stop()
    fun shutdown()
}
```

- [ ] **Step 2: Honor `locale` per utterance in `AndroidVoiceOutput`**

In `android/app/src/main/java/com/virtueson/copilotmaps/voice/AndroidVoiceOutput.kt`:

- Change the pending field to carry a locale: `private var pending: Triple<String, Locale?, () -> Unit>? = null`.
- New signature and behavior:

```kotlin
    override fun speak(text: String, flush: Boolean, locale: Locale?, onDone: () -> Unit) {
        if (!ready) {
            pending = Triple(text, locale, onDone)
            return
        }
        if (locale != null) {
            val res = tts.setLanguage(locale)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.language = Locale.getDefault() // fall back; still speak
            }
        }
        val id = "u${counter++}"
        callbacks[id] = onDone
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, mode, null, id)
    }
```

- Update the init `pending?.let { ... }` block to the triple form:

```kotlin
                pending?.let { (text, locale, cb) ->
                    pending = null
                    speak(text, true, locale, cb)
                }
```

- Keep `tts.language = Locale.getDefault()` as the initial default in `init`.

- [ ] **Step 3: Inject a language provider into `AndroidVoiceInput`**

In `android/app/src/main/java/com/virtueson/copilotmaps/voice/AndroidVoiceInput.kt`:

```kotlin
class AndroidVoiceInput(
    context: Context,
    private val languageProvider: () -> Locale = { Locale.getDefault() },
) : VoiceInput {
```

Replace the `EXTRA_LANGUAGE` line in `start(...)` so it reads the current choice each time:

```kotlin
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageProvider().toLanguageTag())
```

(`EXTRA_LANGUAGE` accepts a BCP-47 tag string; `toLanguageTag()` yields e.g. `"id"`.)

- [ ] **Step 4: Verify compilation**

Run: `.\gradlew.bat compileDebugKotlin --console=plain` (with `JAVA_HOME`). Expected: FAIL only at the two known call sites (`MapScreen` nav-cue `speak`, `CopilotViewModel` reply `speak`) if they don't yet pass the new arg — those are fixed in Task 7. If you run this task in isolation, temporarily confirm the voice files themselves compile by checking the error list contains only those two call sites. Otherwise proceed to Task 7 before compiling.

> Note: because `speak`'s new `locale` is inserted before `onDone`, the existing trailing-lambda call `voiceOutput.speak(result.reply) { ... }` still resolves (`onDone` is the trailing lambda, `locale` defaults to null). The nav call `voiceOutput.speak(line, flush = false)` also still resolves. So this task compiles on its own; the explicit locale wiring happens in Task 7.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/virtueson/copilotmaps/voice/VoiceOutput.kt android/app/src/main/java/com/virtueson/copilotmaps/voice/AndroidVoiceOutput.kt android/app/src/main/java/com/virtueson/copilotmaps/voice/AndroidVoiceInput.kt
git commit -m "feat(app): per-utterance TTS locale + STT language provider"
```

---

## Task 6: DTO + repository plumbing for language (app)

**Files:**
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/network/Dtos.kt` (`RoutePlanRequestDto`)
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/network/CopilotDtos.kt` (`CopilotAskResponseDto`)
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/data/ChatMessage.kt` (`CopilotResult.Success`)
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/data/RoutesRepository.kt`
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/data/CopilotRepository.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `RoutePlanRequestDto.languageCode`; `CopilotAskResponseDto.language`; `CopilotResult.Success(reply, language)`; `RoutesRepository.planRoutes(origin, destination, languageTag: String?)`.

- [ ] **Step 1: Add DTO fields**

`android/app/src/main/java/com/virtueson/copilotmaps/network/Dtos.kt` — `RoutePlanRequestDto`:

```kotlin
data class RoutePlanRequestDto(
    val origin: LatLngDto,
    val destination: LatLngDto,
    @Json(name = "language_code") val languageCode: String? = null,
)
```

(Ensure `import com.squareup.moshi.Json` is present in the file.)

`android/app/src/main/java/com/virtueson/copilotmaps/network/CopilotDtos.kt` — `CopilotAskResponseDto`:

```kotlin
data class CopilotAskResponseDto(
    val reply: String,
    val language: String? = null,
)
```

- [ ] **Step 2: Carry `language` on the domain result**

`android/app/src/main/java/com/virtueson/copilotmaps/data/ChatMessage.kt`:

```kotlin
    data class Success(val reply: String, val language: String? = null) : CopilotResult
```

- [ ] **Step 3: Send the route language tag**

`android/app/src/main/java/com/virtueson/copilotmaps/data/RoutesRepository.kt` — widen the interface and pass it through:

```kotlin
interface RoutesRepository {
    suspend fun planRoutes(origin: GeoPoint, destination: GeoPoint, languageTag: String? = null): RoutesResult
}
```

In `DefaultRoutesRepository.planRoutes`, accept `languageTag: String? = null` and set it on the DTO:

```kotlin
    override suspend fun planRoutes(origin: GeoPoint, destination: GeoPoint, languageTag: String?): RoutesResult {
        return try {
            val response = api.planRoutes(
                RoutePlanRequestDto(
                    origin = LatLngDto(origin.lat, origin.lng),
                    destination = LatLngDto(destination.lat, destination.lng),
                    languageCode = languageTag,
                )
            )
```

- [ ] **Step 4: Read `language` off the copilot response**

`android/app/src/main/java/com/virtueson/copilotmaps/data/CopilotRepository.kt`:

```kotlin
            CopilotResult.Success(response.reply, response.language)
```

- [ ] **Step 5: Verify compilation**

Run: `.\gradlew.bat compileDebugKotlin --console=plain` (with `JAVA_HOME`).
Expected: FAIL only where `RouteViewModel` calls `planRoutes` positionally (still resolves via default) — none expected here; the `languageTag` default keeps existing `planRoutes(origin, destination)` calls valid. BUILD SUCCESSFUL expected.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/virtueson/copilotmaps/network/Dtos.kt android/app/src/main/java/com/virtueson/copilotmaps/network/CopilotDtos.kt android/app/src/main/java/com/virtueson/copilotmaps/data/ChatMessage.kt android/app/src/main/java/com/virtueson/copilotmaps/data/RoutesRepository.kt android/app/src/main/java/com/virtueson/copilotmaps/data/CopilotRepository.kt
git commit -m "feat(app): thread language through route request + copilot response DTOs"
```

---

## Task 7: Wire the setting into view models + MapScreen, add the EN/ID toggle (app)

**Files:**
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/NavViewModel.kt` (phrases provider)
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/RouteViewModel.kt` (language provider)
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/copilot/CopilotViewModel.kt` (speak reply in its language)
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt` (build `LanguageSettings`, thread providers, nav-cue locale, toggle UI)

**Interfaces:**
- Consumes: `LanguageSettings`, `AppLanguage`, `navPhrases`, `EnglishNavPhrases`, `NavPhrases`, `AppLanguage.forTag`, `VoiceOutput.speak(..., locale=...)`, `RoutesRepository.planRoutes(..., languageTag)`.
- Produces: user-visible EN/ID toggle; fully wired language behavior.

- [ ] **Step 1: `NavViewModel` takes a phrases provider**

In `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/NavViewModel.kt`:

- Import `NavPhrases` and `EnglishNavPhrases`.
- Constructor: add `private val phrasesProvider: () -> NavPhrases = { EnglishNavPhrases }` alongside `nowEpochSeconds`.
- In `announce`, pass phrases: `val result = nextAnnouncement(steps, progress, announcer, phrasesProvider())`.
- `NavViewModelFactory` gains the provider:

```kotlin
class NavViewModelFactory(
    private val phrasesProvider: () -> NavPhrases = { EnglishNavPhrases },
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        NavViewModel(phrasesProvider = phrasesProvider) as T
}
```

> The reroute-failure/"Rerouting" strings stay English for now (internal status cues, out of scope for this pass).

- [ ] **Step 2: `RouteViewModel` takes a language provider**

In `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/RouteViewModel.kt`:

- Constructor: add `private val languageProvider: () -> String? = { null }`.
- In `planRoutes`, pass it: `repository.planRoutes(origin, destination, languageProvider())`.
- `RouteViewModelFactory` gains the provider and passes it to `RouteViewModel`.

This keeps every existing `routeViewModel.planRoutes(origin, destination)` call site (initial plan, search, reroute) unchanged.

- [ ] **Step 3: `CopilotViewModel` speaks the reply in the reply's language**

In `android/app/src/main/java/com/virtueson/copilotmaps/ui/copilot/CopilotViewModel.kt`:

- Import `com.virtueson.copilotmaps.data.AppLanguage` and `java.util.Locale`.
- On success, capture the language and pass its locale to `speak`:

```kotlin
                is CopilotResult.Success -> {
                    val replyLocale = AppLanguage.forTag(result.language)?.locale
                    _state.value = _state.value.copy(
                        messages = _state.value.messages + ChatMessage(Role.ASSISTANT, result.reply),
                        sending = false,
                    )
                    if (speakReply && _state.value.ttsEnabled) {
                        _state.value = _state.value.copy(speaking = true)
                        voiceOutput.speak(result.reply, locale = replyLocale) {
                            _state.value = _state.value.copy(speaking = false)
                        }
                    }
                }
```

(If `result.language` is null/unknown, `replyLocale` is null → `AndroidVoiceOutput` keeps its current language — the toggle language set for nav — a safe fallback.)

- [ ] **Step 4: Build `LanguageSettings` in `MapScreen` and thread providers**

In `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt`, near the other `remember`/`viewModel` setup (around lines 105–137):

- Create the setting and observe it:

```kotlin
    val languageSettings = remember { LanguageSettings(context) }
    val appLanguage by languageSettings.language.collectAsStateWithLifecycle()
```

- Voice components read the live language:

```kotlin
    val voiceInput = remember { AndroidVoiceInput(context) { languageSettings.language.value.locale } }
    val voiceOutput = remember { AndroidVoiceOutput(context) }
```

- `RouteViewModel` factory gets the language tag provider:

```kotlin
    val routeViewModel: RouteViewModel = viewModel(
        factory = RouteViewModelFactory(
            DefaultRoutesRepository(NetworkModule.routesApi),
            languageProvider = { languageSettings.language.value.tag },
        )
    )
```

- `NavViewModel` factory gets the phrases provider:

```kotlin
    val navViewModel: NavViewModel = viewModel(
        factory = NavViewModelFactory { navPhrases(context, languageSettings.language.value.locale) }
    )
```

- Nav cues speak in the current language — update the announcements collector (currently line ~144):

```kotlin
            if (navVoiceEnabled) voiceOutput.speak(line, flush = false, locale = appLanguage.locale)
```

Add imports: `com.virtueson.copilotmaps.data.LanguageSettings`, `com.virtueson.copilotmaps.data.AppLanguage`, `com.virtueson.copilotmaps.data.navPhrases`.

- [ ] **Step 5: Add the EN/ID toggle to the idle top controls**

In the `if (!navActive)` top `Column` in `MapScreen` (the one holding the search field, results, and the Gas/Food/Clear row), add a compact segmented toggle — e.g. a `Row` of two `FilterChip`s (Material3) bound to `appLanguage`:

```kotlin
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = appLanguage == AppLanguage.ENGLISH,
            onClick = { languageSettings.set(AppLanguage.ENGLISH) },
            label = { Text("EN") },
        )
        FilterChip(
            selected = appLanguage == AppLanguage.INDONESIAN,
            onClick = { languageSettings.set(AppLanguage.INDONESIAN) },
            label = { Text("ID") },
        )
    }
```

Place it at the top of that column (above the search field) so it does not collide with the existing bottom controls. Add imports for `FilterChip`, `Arrangement`, `Row`, `Text`, `dp` if not already present. Keep it inside the `!navActive` branch only (no toggle during active navigation, per the design).

- [ ] **Step 6: Build the app (compile + assemble debug)**

Run (with `JAVA_HOME` set):
```
.\gradlew.bat compileDebugKotlin --console=plain
.\gradlew.bat assembleDebug --console=plain
```
Expected: BUILD SUCCESSFUL. Fix any remaining call-site mismatches (all anticipated ones are handled by defaults).

- [ ] **Step 7: Run the full Android unit suite**

Run the Android test command. Expected: PASS (Tasks 3 & 4 tests + all pre-existing).

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/virtueson/copilotmaps/ui/map/NavViewModel.kt android/app/src/main/java/com/virtueson/copilotmaps/ui/map/RouteViewModel.kt android/app/src/main/java/com/virtueson/copilotmaps/ui/copilot/CopilotViewModel.kt android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt
git commit -m "feat(app): wire EN/ID language control into nav, routes, and copilot"
```

---

## Manual device verification (after Task 7)

Restart backend if needed: `cd backend; .\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8000` + `adb reverse tcp:8000 tcp:8000`. Ensure `ROUTE_PLANNER=google` and a real copilot provider in `.env` for a true end-to-end check.

1. Toggle **ID** → plan a route → turn instructions, wrapper words ("Dalam … meter"), and the voice are all Indonesian.
2. Tap the mic and speak Indonesian to the Copilot → it's understood → answer shown and spoken in Indonesian.
3. Keep the toggle on **ID** but *type* an English question → answer is English and spoken with an English voice.
4. Toggle **EN** → plan again → everything English.
5. Toggle is absent during active navigation.

---

## Self-Review

- **Spec coverage:** LanguageSettings/AppLanguage (Task 3) ✓; route `languageCode` (Tasks 1, 6, 7) ✓; localized `NavPhrases` + resources + pure `NavAnnouncer` (Task 4) ✓; STT language (Task 5, 7) ✓; per-utterance TTS locale (Tasks 5, 7) ✓; copilot persona rule + reply-language detector + `language` field (Task 2) ✓; copilot voice matches reply (Tasks 6, 7) ✓; EN/ID toggle UI (Task 7) ✓; out-of-scope items untouched ✓.
- **Type consistency:** `planRoutes(origin, destination, languageTag)` used consistently (Tasks 6, 7); `speak(text, flush, locale, onDone)` consistent (Tasks 5, 7, CopilotViewModel); `AppLanguage.forTag` returns nullable, handled with `?.locale` fallbacks; `nextAnnouncement(..., phrases)` and `formatDistance(meters, phrases)` consistent (Tasks 4, 7).
- **Placeholders:** none — every code step shows real code.
