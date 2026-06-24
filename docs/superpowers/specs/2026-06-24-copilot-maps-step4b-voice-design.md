# Step 4b — Voice Copilot (STT + TTS) — Design

**Date:** 2026-06-24
**Status:** Approved (brainstorm complete; ready for implementation plan)
**Depends on:** Step 4a (text copilot) — `CopilotViewModel`, `CopilotChatSheet`, `/copilot/ask`.

## Goal

Let the driver **talk** to the existing Copilot and **hear** its answers, hands-light.
This wraps the Step 4a text chat with speech; it does **not** change the backend or add
new copilot tools.

## Interaction model (decided)

- **Full hands-light:** tap mic → auto-transcribe → auto-send → reply spoken aloud.
- **Push-to-talk, one turn at a time:** after a spoken reply, the app waits; the user taps
  the mic again for the next question (no auto-listen loop — avoids false triggers).
- **Typed input stays silent;** only voice-initiated turns speak the reply.
- **Barge-in:** tapping the mic while the copilot is speaking stops TTS and starts listening.

## Approach (decided)

- **STT:** Android `SpeechRecognizer` driven directly (Approach A) — in-app mic UI, no system
  dialog. Free, offline-capable. Chosen over `RecognizerIntent` (system pop-up breaks the
  in-app feel) and cloud STT (latency/cost/overkill for now).
- **TTS:** Android `TextToSpeech` (built-in, free, offline).
- Both hidden behind framework-free interfaces so `CopilotViewModel` stays JVM-unit-testable
  (same pattern as `FusedLocationProvider`). A future swap to cloud STT (e.g. Whisper via
  SumoPod) would reuse the same `VoiceInput` interface.

## Components

### New domain interfaces (framework-free)

```kotlin
interface VoiceInput {                       // wraps SpeechRecognizer
    fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit)
    fun stop()
    fun release()
}

interface VoiceOutput {                      // wraps TextToSpeech
    fun speak(text: String)
    fun stop()
    fun shutdown()
}
```

### New Android implementations (verified on device, not unit-tested)

- `AndroidVoiceInput(context)` — drives `SpeechRecognizer`; emits partial + final transcripts;
  maps recognizer error codes to friendly strings; runs on the main thread per the API
  contract.
- `AndroidVoiceOutput(context)` — initializes `TextToSpeech` once (async init guard), default
  locale, `speak(text, QUEUE_FLUSH, ...)`.

### Extended `CopilotViewModel`

- New constructor deps: `voiceInput: VoiceInput`, `voiceOutput: VoiceOutput` (fakes in tests).
- `CopilotUiState` gains: `listening: Boolean`, `speaking: Boolean`, `ttsEnabled: Boolean`
  (mute toggle, default `true`), and `partialTranscript: String?` (live dictation preview).
- `onMicTapped(context: TripContext)`:
  - if `speaking` → stop TTS, then start listening (barge-in);
  - else if `listening` → stop (cancel);
  - else → start listening.
- On **final transcript** → `sendMessage(transcript, context, speakReply = true)`.
- `sendMessage(text, context, speakReply: Boolean = false)` — existing logic; on `Success`,
  if `speakReply && ttsEnabled`, call `voiceOutput.speak(reply)` and track `speaking`.
- `setTtsEnabled(Boolean)` — mute toggle; disabling stops any current utterance.
- `onCleared()` → `voiceInput.release()` + `voiceOutput.shutdown()`.

### UI — `CopilotChatSheet`

- **Mic button** beside the text field: states idle → listening (animated/red) → speaking.
- **Live partial transcript** shown under the input while listening.
- **Speaker mute toggle** for spoken replies.
- Typed **Send** button unchanged; typing remains silent.

## Data flow (one voice turn)

```
tap mic
  → ensure RECORD_AUDIO permission (request on first use)
  → voiceInput.start(...)                     [state: listening]
  → partials → partialTranscript (live preview)
  → final transcript
  → CopilotViewModel.sendMessage(transcript, context, speakReply = true)
  → POST /copilot/ask → reply
  → append assistant bubble (on screen)
  → if ttsEnabled: voiceOutput.speak(reply)   [state: speaking]
  → TTS done                                  [state: idle]
```

## Permission — `RECORD_AUDIO`

- Add `<uses-permission android:name="android.permission.RECORD_AUDIO" />` to the manifest.
- Reuse the `rememberLauncherForActivityResult` pattern already in `MapScreen` (used for
  `ACCESS_FINE_LOCATION`). First mic tap: if not granted, request; if granted, start; if
  denied, show a short "Microphone permission needed for voice" note and keep the mic disabled.
  Text input always works regardless.
- Verify on device whether a `<queries>` entry for `RecognitionService` is needed on the
  Xiaomi 14 / HyperOS (handle if the recognizer reports unavailable).

## Error handling (degrade gracefully — never crash, never block typing)

- Permission denied → note in sheet, mic disabled; text input unaffected.
- Recognizer errors (`ERROR_NO_MATCH`, `ERROR_NETWORK`, `ERROR_RECOGNIZER_BUSY`, …) → mapped
  to friendly text, state returns to idle.
- Empty/blank transcript → ignored, no send.
- TTS init fails / language missing → silently fall back to text-only (reply already on
  screen); `speak` no-ops. Same no-op path used when muted.
- Lifecycle → release `SpeechRecognizer` and `TextToSpeech` in `onCleared()`.

## Testing

**Unit (JVM, no device)** — `FakeVoiceInput` + `FakeVoiceOutput` drive `CopilotViewModel`:

1. mic tap when idle → `start` called, state `listening`.
2. final transcript → message sent → on success, reply passed to `voiceOutput.speak` when
   `ttsEnabled`.
3. muted (`ttsEnabled = false`) → reply **not** spoken.
4. recognizer error → error surfaced, no send.
5. tap-while-listening → `stop` called.

**On-device (Xiaomi 14)** — real `AndroidVoiceInput` / `AndroidVoiceOutput`: speak "any gas on
my route?", confirm transcription → send → spoken reply; test mute; test permission
deny/grant; test barge-in.

The Android wrapper classes are pure framework glue — not unit-tested, verified on device
(consistent with `FusedLocationProvider`).

## Out of scope (parked)

- Auto-listen conversation loop / wake word.
- Cloud STT (Whisper) — same interface, swap later if accuracy needs it.
- Actionable copilot ("take me there" — set destination/route from a reply) — separate
  sub-project (4c), needs a backend response-contract change.
- Turn-by-turn spoken guidance — Step 5 (live navigation).

## Definition of Done

- Speaking a question transcribes, sends, and the reply is spoken aloud on the Xiaomi 14.
- Mute toggle silences spoken replies; typed input stays silent.
- Permission deny/grant handled; recognizer/TTS failures degrade to text-only without crashing.
- `CopilotViewModel` voice unit tests pass (5 cases above); existing tests stay green.
- No secrets touched; `git status` clean after each commit.
```
