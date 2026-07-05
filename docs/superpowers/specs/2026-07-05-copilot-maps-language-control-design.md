# Copilot Maps — In-App Language Control (EN / ID) Design

**Date:** 2026-07-05
**Status:** Approved for planning

## Problem

Navigation is linguistically inconsistent and the Copilot can't understand spoken Indonesian:

1. **Map instructions are Indonesian, the voice is English.** The backend calls Google
   Routes with no `languageCode`, so Google localizes by region and returns
   `"Belok kanan…"`. Android TTS is set to `Locale.getDefault()` (the phone = English),
   so an English voice reads Indonesian text.
2. **My announcement wrapper is English.** `NavAnnouncer` wraps Google's instruction with
   `"In …"`, `", then "`, `"meters"/"kilometers"`, `"You have arrived."`. Each spoken cue is
   therefore English template + Indonesian street name, read by an English voice.
3. **Speech-to-text only hears the phone language.** `AndroidVoiceInput` passes
   `EXTRA_LANGUAGE = Locale.getDefault()` (English). Speaking Indonesian to the Copilot
   forces the words into English → nonsense.
4. **Copilot has no language rule.** `prompt.py`'s persona says nothing about language, so
   replies come back in an arbitrary language.

## Goal

Give the app a single, explicit language choice (English or Indonesian) that makes the
*navigation* experience fully consistent, lets the Copilot *hear* the chosen language, and
lets the Copilot *reply and speak back* in whatever language the user actually used.

## Key decisions (agreed during brainstorming)

- **Language source: an in-app EN / ID toggle**, not the phone locale. The app is
  Compose-only on a plain `ComponentActivity` with no AppCompat, so
  `AppCompatDelegate.setApplicationLocales` is deliberately avoided (it would pull in
  AppCompat + XML-theme friction). Instead a lightweight persisted setting is read directly
  by the components that need it. Works on every Android version, no activity recreation.
- **Navigation follows the toggle.** STT, route-instruction language, wrapper words, and
  voice all use the selected language.
- **Copilot mirrors the user's actual message, independent of the toggle.** A typed English
  question gets an English answer spoken in English, even when the toggle is on Indonesian.
- **Out of scope:** translating the app's own button labels ("Start", "Cancel", "Copilot").
  The toggle governs only what the user hears/speaks/routes in, plus Copilot's language.

## Architecture

### One source of truth: `LanguageSettings`

A small holder class backed by `SharedPreferences`, exposing the current choice as a
`StateFlow` so UI and view models react to changes without restarts.

```kotlin
enum class AppLanguage(val tag: String) {   // tag is BCP-47
    ENGLISH("en"),
    INDONESIAN("id");
    val locale: java.util.Locale get() = java.util.Locale.forLanguageTag(tag)
}

class LanguageSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("language", Context.MODE_PRIVATE)
    private val _language = MutableStateFlow(load())
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    fun set(lang: AppLanguage) { /* persist + emit */ }
    private fun load(): AppLanguage { /* read pref; default = device locale mapped to
                                         ID if Indonesian else EN */ }
}
```

A single instance is created in `MainActivity` and threaded to `MapScreen` /
view models / voice components (manual DI, consistent with the existing codebase — no Hilt).

### What the setting drives

| Concern | Mechanism |
|---|---|
| **Speech-to-text** | `AndroidVoiceInput.start(...)` reads the current tag and sets `EXTRA_LANGUAGE` to it (via an injected `() -> Locale` provider so runtime toggles take effect on the next listen). |
| **Route instructions** | The app sends the tag on each plan request; the backend passes it to Google Routes as `languageCode`. |
| **Nav wrapper words** | Localized string resources (`values/strings.xml` EN, `values-in/strings.xml` ID), resolved for the *selected* language via a locale-specific resource `Context`, packed into a `NavPhrases` bundle. `NavAnnouncer` stays pure. |
| **Nav voice** | `AndroidVoiceOutput` speaks nav cues in the selected language (`tts.language = selected.locale`). |

### Navigation language details

**Backend — Google Routes `languageCode`.**
- `RoutePlanRequest` (models.py) gains `language_code: str | None = None`.
- `GoogleRoutePlanner.plan(...)` accepts the code and, when present, adds
  `"languageCode": language_code` to the request body. The stub planner ignores it.
- The `/routes/plan` router passes `request.language_code` through to the planner.

**App — send the tag.**
- `RoutePlanRequestDto` (network/Dtos.kt) gains
  `@Json(name = "language_code") val languageCode: String? = null`.
- `DefaultRoutesRepository.planRoutes(...)` includes the current tag. Because the repository
  is constructed without a language, it takes a `languageProvider: () -> String` (or the tag
  is passed per call from the view model). Chosen approach: pass the tag into
  `planRoutes(origin, destination, languageTag)` so the view model supplies the live value.

**App — `NavPhrases` + pure `NavAnnouncer`.**

`NavAnnouncer.nextAnnouncement(...)` and `formatDistance(...)` currently hardcode English.
They gain a `phrases: NavPhrases` parameter:

```kotlin
data class NavPhrases(
    val inPrefix: (String) -> String,   // e.g. {d -> "In $d, "}  / {d -> "Dalam $d, "}
    val thenJoiner: String,             // ", then "              / ", lalu "
    val metersUnit: String,             // "meters"               / "meter"
    val kilometersUnit: String,         // "kilometers"           / "kilometer"
    val arrived: String,                // "You have arrived."    / "Anda telah sampai di tujuan."
)
```

- Google's own step instruction text is already localized by the backend (decision above),
  so only these wrapper strings need translating.
- The strings live in resources; the UI layer builds `NavPhrases` from a `Context` whose
  configuration is overridden to the selected locale:
  `context.createConfigurationContext(Configuration(base).apply { setLocale(selected.locale) })`.
  This respects the in-app toggle even when it differs from the system locale.
- `NavAnnouncer` remains a pure function with no Android imports — existing unit tests keep
  working by passing an English `NavPhrases`; new tests cover an Indonesian `NavPhrases`.

Indonesian resource values:
- `nav_in_prefix` → `"Dalam %s, "`
- `nav_then_joiner` → `", lalu "`
- `nav_meters` → `"meter"`
- `nav_kilometers` → `"kilometer"`
- `nav_arrived` → `"Anda telah sampai di tujuan."`

English resource values keep the current wording.

### Copilot mirrors the user's language

**Prompt rule.** Append to `PERSONA` in `prompt.py`:
> "Reply in the same language the user used."

So an Indonesian message gets an Indonesian answer; a typed English message stays English.

**Spoken reply matches the reply, not the toggle.**
- Add a small, dependency-free `id` vs `en` detector in the backend
  (`app/copilot/language.py`): score the reply against a short list of high-frequency
  Indonesian marker words (`yang, tidak, ada, ke, di, dari, belok, kanan, kiri, lurus,
  menit, kilometer, jalan, sampai, tujuan, macet`). If enough markers hit, return `"id"`,
  else `"en"`. Deterministic and unit-testable.
- `CopilotAskResponse` gains `language: str` (`"en"` default). The router sets it from the
  detector on the reply text.
- `CopilotAskResponseDto` gains `val language: String? = null`.
- When the app speaks a Copilot reply, it uses `AppLanguage.forTag(language)?.locale`,
  falling back to the current toggle language if the field is null/unknown. This is a
  per-utterance TTS language override on `AndroidVoiceOutput.speak(...)`.

**Per-utterance TTS language.** `VoiceOutput.speak(...)` gains an optional
`locale: Locale? = null` parameter; `AndroidVoiceOutput` sets `tts.language = locale` before
speaking when provided, otherwise leaves the current (nav/toggle) language. Nav cues pass the
toggle locale; Copilot replies pass the detected-reply locale.

### UI

A compact **EN / ID segmented toggle** in the idle-screen top controls (the same
`if (!navActive)` column that holds search + Gas/Food/Clear). Selecting a language calls
`languageSettings.set(...)`. It is the only new visible element; it does not appear during
active navigation.

## Data flow

1. User taps **ID** → `LanguageSettings` persists `INDONESIAN`, emits on its `StateFlow`.
2. User plans a route → repository sends `language_code = "id"` → backend → Google returns
   Indonesian steps.
3. Navigation starts → `NavAnnouncer` produces cues using the Indonesian `NavPhrases`
   (resolved from `values-in/`), spoken by an Indonesian TTS voice.
4. User taps the mic and speaks Indonesian → STT listens in `id` → correct transcript →
   Copilot (persona rule) replies in Indonesian → backend tags `language = "id"` → app
   speaks the reply in an Indonesian voice.
5. With the toggle still on ID, the user *types* an English question → Copilot replies in
   English → backend tags `language = "en"` → app speaks it in an English voice.

## Error handling / edge cases

- **Detector unsure / null `language`:** fall back to the current toggle language for TTS.
- **TTS voice for a language not installed on device:** `tts.setLanguage` returns
  `LANG_MISSING_DATA`/`LANG_NOT_SUPPORTED`; on failure, fall back to the previous locale and
  still speak (garbled is better than silent). No crash.
- **Toggle changed mid-navigation:** already-fetched route steps keep their language; the
  wrapper words and voice for *subsequent* cues follow the new setting. Acceptable — a route
  re-plan (or the next trip) makes everything consistent again. The toggle is hidden during
  active navigation to avoid mid-trip confusion anyway.
- **STT language changed between listens:** the injected provider is read at `start()`, so
  the next mic press uses the new language.

## Testing

**Backend (pytest):**
- `plan(...)` includes `"languageCode"` in the Google body when `language_code` is set, and
  omits it when `None`.
- `/routes/plan` forwards `language_code` to the planner.
- Language detector: Indonesian sample → `"id"`; English sample → `"en"`;
  empty/short/ambiguous → `"en"` default.
- `/copilot/ask` response includes a `language` field matching the detector on the reply.

**App (JUnit + coroutines-test):**
- `NavAnnouncer` with an Indonesian `NavPhrases` produces `"Dalam 300 meter, …"`,
  `", lalu "` chaining, and `"Anda telah sampai di tujuan."`; existing English tests keep
  passing with an English `NavPhrases`.
- `formatDistance` uses the supplied unit strings.
- `LanguageSettings` persists and reloads the choice; default derives from device locale.
- `AppLanguage.forTag` maps `"id"`/`"en"` and returns null for unknown.

**Manual (device):**
- Toggle ID → plan a route → instructions, wrapper words, and voice all Indonesian.
- Speak Indonesian to the Copilot → understood and answered/spoken in Indonesian.
- Toggle ID but type an English question → English answer spoken in English.
- Toggle EN → everything English.

## Files touched (indicative)

**Backend**
- `app/models.py` — `RoutePlanRequest.language_code`, `CopilotAskResponse.language`.
- `app/planners/google.py` — accept + send `languageCode`.
- `app/planners/stub.py` — accept + ignore the arg (signature parity).
- `app/routers/routes.py` — pass `language_code` through.
- `app/routers/copilot.py` — set `language` from the detector.
- `app/copilot/prompt.py` — persona language rule.
- `app/copilot/language.py` — new detector.

**App**
- `data/LanguageSettings.kt` + `AppLanguage` — new.
- `voice/VoiceOutput.kt` / `AndroidVoiceOutput.kt` — per-utterance `locale`.
- `voice/VoiceInput.kt` / `AndroidVoiceInput.kt` — language provider for `EXTRA_LANGUAGE`.
- `data/NavAnnouncer.kt` — `NavPhrases` parameter (pure).
- `data/NavPhrases.kt` — new bundle + a resource-backed builder.
- `res/values/strings.xml` + `res/values-in/strings.xml` — nav wrapper strings.
- `network/Dtos.kt` — `RoutePlanRequestDto.languageCode`.
- `network/CopilotDtos.kt` — `CopilotAskResponseDto.language`.
- `data/RoutesRepository.kt` — send language tag.
- `ui/map/*ViewModel.kt` + `ui/map/MapScreen.kt` — thread settings, wire STT/TTS/route
  language, add the EN/ID toggle.
- `MainActivity.kt` — construct `LanguageSettings`.

## Non-goals

- Auto-detecting spoken language (Android STT can't do it reliably) — the toggle picks it.
- Full app-wide UI localization.
- Languages beyond English and Indonesian (the design generalizes, but only these two ship).
