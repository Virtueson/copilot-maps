# Step 4b — Voice Copilot (STT + TTS) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the driver speak questions to the existing Copilot and hear answers spoken back, hands-light (push-to-talk).

**Architecture:** Two framework-free interfaces (`VoiceInput` wrapping `SpeechRecognizer`, `VoiceOutput` wrapping `TextToSpeech`) keep `CopilotViewModel` pure JVM and unit-testable. The ViewModel orchestrates: mic tap → listen → final transcript → existing `sendMessage(..., speakReply = true)` → reply spoken via TTS. Android wrapper classes are verified on device, not unit-tested (same as `LocationProvider`/`FusedLocationProvider`).

**Tech Stack:** Kotlin, Jetpack Compose, Android `SpeechRecognizer` + `TextToSpeech`, AndroidX Lifecycle ViewModel, JUnit + kotlinx-coroutines-test.

## Global Constraints

- Package root: `com.virtueson.copilotmaps`. New voice code under `…/voice/`.
- minSdk 24, targetSdk 36. No new Gradle dependencies (use text/emoji labels, not material-icons-extended).
- Gradle from CLI: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; cd "D:\Russell\Data Science Job\AI Maps\android"; .\gradlew.bat <task>`.
- Existing tests must stay green. TDD: failing test → implement → pass → commit.
- Spoken replies only for voice-initiated turns; typed input stays silent. Mute toggle `ttsEnabled` defaults `true`.
- No secrets touched; confirm `git status` excludes `backend/.env` and `local.properties` before each commit.

---

### Task 1: Voice interfaces + ViewModel/state extension (TDD)

**Files:**
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/voice/VoiceInput.kt`
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/voice/VoiceOutput.kt`
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/copilot/CopilotState.kt`
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/copilot/CopilotViewModel.kt`
- Create (test fakes): `android/app/src/test/java/com/virtueson/copilotmaps/voice/FakeVoice.kt`
- Create (test): `android/app/src/test/java/com/virtueson/copilotmaps/ui/copilot/CopilotVoiceViewModelTest.kt`
- Modify (test): `android/app/src/test/java/com/virtueson/copilotmaps/ui/copilot/CopilotViewModelTest.kt`

**Interfaces:**
- Produces:
  - `interface VoiceInput { fun start(onPartial: (String)->Unit, onFinal: (String)->Unit, onError: (String)->Unit); fun stop(); fun release() }`
  - `interface VoiceOutput { fun speak(text: String, onDone: () -> Unit = {}); fun stop(); fun shutdown() }`
  - `CopilotUiState` gains `listening: Boolean = false`, `speaking: Boolean = false`, `ttsEnabled: Boolean = true`, `partialTranscript: String? = null`.
  - `CopilotViewModel(repository, voiceInput, voiceOutput)`; methods `sendMessage(text, context, speakReply: Boolean = false)`, `onMicTapped(context: TripContext)`, `setTtsEnabled(enabled: Boolean)`, `onCleared()`.
  - `CopilotViewModelFactory(repository, voiceInput, voiceOutput)`.

- [ ] **Step 1: Create the two interfaces**

`voice/VoiceInput.kt`:
```kotlin
package com.virtueson.copilotmaps.voice

/** One job: capture a single spoken utterance. Easy to fake in tests. */
interface VoiceInput {
    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    )
    fun stop()
    fun release()
}
```

`voice/VoiceOutput.kt`:
```kotlin
package com.virtueson.copilotmaps.voice

/** One job: speak a line of text aloud. Easy to fake in tests. */
interface VoiceOutput {
    fun speak(text: String, onDone: () -> Unit = {})
    fun stop()
    fun shutdown()
}
```

- [ ] **Step 2: Extend `CopilotUiState`**

Replace the body of `CopilotState.kt` with:
```kotlin
package com.virtueson.copilotmaps.ui.copilot

import com.virtueson.copilotmaps.data.ChatMessage

data class CopilotUiState(
    val messages: List<ChatMessage> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
    val listening: Boolean = false,
    val speaking: Boolean = false,
    val ttsEnabled: Boolean = true,
    val partialTranscript: String? = null,
)
```

- [ ] **Step 3: Create the test fakes**

`android/app/src/test/java/com/virtueson/copilotmaps/voice/FakeVoice.kt`:
```kotlin
package com.virtueson.copilotmaps.voice

class FakeVoiceInput : VoiceInput {
    var started = false
    var stopped = false
    var released = false
    private var onPartial: ((String) -> Unit)? = null
    private var onFinal: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    override fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        started = true
        this.onPartial = onPartial
        this.onFinal = onFinal
        this.onError = onError
    }

    override fun stop() { stopped = true }
    override fun release() { released = true }

    fun emitPartial(text: String) = onPartial?.invoke(text)
    fun emitFinal(text: String) = onFinal?.invoke(text)
    fun emitError(reason: String) = onError?.invoke(reason)
}

class FakeVoiceOutput : VoiceOutput {
    val spoken = mutableListOf<String>()
    var stopped = false
    var shutdownCalled = false

    override fun speak(text: String, onDone: () -> Unit) {
        spoken += text
        onDone()
    }

    override fun stop() { stopped = true }
    override fun shutdown() { shutdownCalled = true }
}
```

- [ ] **Step 4: Write the failing voice tests**

`android/app/src/test/java/com/virtueson/copilotmaps/ui/copilot/CopilotVoiceViewModelTest.kt`:
```kotlin
package com.virtueson.copilotmaps.ui.copilot

import com.virtueson.copilotmaps.MainDispatcherRule
import com.virtueson.copilotmaps.data.ChatMessage
import com.virtueson.copilotmaps.data.CopilotRepository
import com.virtueson.copilotmaps.data.CopilotResult
import com.virtueson.copilotmaps.data.GeoPoint
import com.virtueson.copilotmaps.data.TripContext
import com.virtueson.copilotmaps.voice.FakeVoiceInput
import com.virtueson.copilotmaps.voice.FakeVoiceOutput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeRepo(private val result: CopilotResult) : CopilotRepository {
    override suspend fun ask(messages: List<ChatMessage>, context: TripContext): CopilotResult = result
}

private val ctx = TripContext(GeoPoint(0.0, 0.0), null, emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
class CopilotVoiceViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun vm(
        result: CopilotResult = CopilotResult.Success("Shell is 1km ahead."),
        input: FakeVoiceInput = FakeVoiceInput(),
        output: FakeVoiceOutput = FakeVoiceOutput(),
    ) = CopilotViewModel(FakeRepo(result), input, output)

    @Test
    fun `mic tap when idle starts listening`() {
        val input = FakeVoiceInput()
        val model = vm(input = input)

        model.onMicTapped(ctx)

        assertTrue(input.started)
        assertTrue(model.state.value.listening)
    }

    @Test
    fun `final transcript sends and speaks the reply`() = runTest {
        val input = FakeVoiceInput()
        val output = FakeVoiceOutput()
        val model = vm(input = input, output = output)

        model.onMicTapped(ctx)
        input.emitFinal("any gas on my route?")
        advanceUntilIdle()

        val state = model.state.value
        assertEquals(2, state.messages.size)            // user + assistant
        assertEquals("Shell is 1km ahead.", state.messages[1].content)
        assertEquals(listOf("Shell is 1km ahead."), output.spoken)
        assertFalse(state.listening)
    }

    @Test
    fun `muted does not speak the reply`() = runTest {
        val output = FakeVoiceOutput()
        val input = FakeVoiceInput()
        val model = vm(input = input, output = output)
        model.setTtsEnabled(false)

        model.onMicTapped(ctx)
        input.emitFinal("any gas on my route?")
        advanceUntilIdle()

        assertTrue(output.spoken.isEmpty())
        assertEquals(2, model.state.value.messages.size) // reply still on screen
    }

    @Test
    fun `recognizer error surfaces and sends nothing`() = runTest {
        val input = FakeVoiceInput()
        val model = vm(input = input)

        model.onMicTapped(ctx)
        input.emitError("Didn't catch that — try again.")
        advanceUntilIdle()

        val state = model.state.value
        assertEquals("Didn't catch that — try again.", state.error)
        assertTrue(state.messages.isEmpty())
        assertFalse(state.listening)
    }

    @Test
    fun `tap while listening stops the recognizer`() {
        val input = FakeVoiceInput()
        val model = vm(input = input)

        model.onMicTapped(ctx)   // start
        model.onMicTapped(ctx)   // stop

        assertTrue(input.stopped)
        assertFalse(model.state.value.listening)
    }
}
```

- [ ] **Step 5: Run the voice tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.virtueson.copilotmaps.ui.copilot.CopilotVoiceViewModelTest"`
Expected: FAIL — `CopilotViewModel` constructor/`onMicTapped`/`setTtsEnabled` unresolved.

- [ ] **Step 6: Implement the extended `CopilotViewModel`**

Replace `CopilotViewModel.kt` with:
```kotlin
package com.virtueson.copilotmaps.ui.copilot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.virtueson.copilotmaps.data.ChatMessage
import com.virtueson.copilotmaps.data.CopilotRepository
import com.virtueson.copilotmaps.data.CopilotResult
import com.virtueson.copilotmaps.data.Role
import com.virtueson.copilotmaps.data.TripContext
import com.virtueson.copilotmaps.voice.VoiceInput
import com.virtueson.copilotmaps.voice.VoiceOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val MAX_TURNS = 12

class CopilotViewModel(
    private val repository: CopilotRepository,
    private val voiceInput: VoiceInput,
    private val voiceOutput: VoiceOutput,
) : ViewModel() {

    private val _state = MutableStateFlow(CopilotUiState())
    val state: StateFlow<CopilotUiState> = _state.asStateFlow()

    fun sendMessage(text: String, context: TripContext, speakReply: Boolean = false) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.sending) return

        val withUser = _state.value.messages + ChatMessage(Role.USER, trimmed)
        _state.value = _state.value.copy(messages = withUser, sending = true, error = null)

        viewModelScope.launch {
            val history = withUser.takeLast(MAX_TURNS)
            when (val result = repository.ask(history, context)) {
                is CopilotResult.Success -> {
                    _state.value = _state.value.copy(
                        messages = _state.value.messages + ChatMessage(Role.ASSISTANT, result.reply),
                        sending = false,
                    )
                    if (speakReply && _state.value.ttsEnabled) {
                        _state.value = _state.value.copy(speaking = true)
                        voiceOutput.speak(result.reply) {
                            _state.value = _state.value.copy(speaking = false)
                        }
                    }
                }
                is CopilotResult.Failure ->
                    _state.value = _state.value.copy(sending = false, error = result.reason)
            }
        }
    }

    fun onMicTapped(context: TripContext) {
        val current = _state.value
        when {
            current.speaking -> {
                voiceOutput.stop()
                _state.value = current.copy(speaking = false)
                startListening(context)
            }
            current.listening -> {
                voiceInput.stop()
                _state.value = _state.value.copy(listening = false, partialTranscript = null)
            }
            else -> startListening(context)
        }
    }

    private fun startListening(context: TripContext) {
        _state.value = _state.value.copy(listening = true, error = null, partialTranscript = null)
        voiceInput.start(
            onPartial = { partial ->
                _state.value = _state.value.copy(partialTranscript = partial)
            },
            onFinal = { text ->
                _state.value = _state.value.copy(listening = false, partialTranscript = null)
                sendMessage(text, context, speakReply = true)
            },
            onError = { reason ->
                _state.value = _state.value.copy(
                    listening = false, partialTranscript = null, error = reason,
                )
            },
        )
    }

    fun setTtsEnabled(enabled: Boolean) {
        if (!enabled) voiceOutput.stop()
        _state.value = _state.value.copy(
            ttsEnabled = enabled,
            speaking = if (!enabled) false else _state.value.speaking,
        )
    }

    override fun onCleared() {
        voiceInput.release()
        voiceOutput.shutdown()
    }
}

class CopilotViewModelFactory(
    private val repository: CopilotRepository,
    private val voiceInput: VoiceInput,
    private val voiceOutput: VoiceOutput,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CopilotViewModel(repository, voiceInput, voiceOutput) as T
}
```

- [ ] **Step 7: Update the existing `CopilotViewModelTest` to the new constructor**

In `CopilotViewModelTest.kt`, add imports and pass fakes. Change the two constructions
`CopilotViewModel(FakeCopilotRepository(...))` to include the fakes.

Add imports near the top:
```kotlin
import com.virtueson.copilotmaps.voice.FakeVoiceInput
import com.virtueson.copilotmaps.voice.FakeVoiceOutput
```
Replace:
```kotlin
        val vm = CopilotViewModel(FakeCopilotRepository(CopilotResult.Success("Hi there")))
```
with:
```kotlin
        val vm = CopilotViewModel(
            FakeCopilotRepository(CopilotResult.Success("Hi there")),
            FakeVoiceInput(), FakeVoiceOutput(),
        )
```
and replace:
```kotlin
        val vm = CopilotViewModel(FakeCopilotRepository(CopilotResult.Failure("boom")))
```
with:
```kotlin
        val vm = CopilotViewModel(
            FakeCopilotRepository(CopilotResult.Failure("boom")),
            FakeVoiceInput(), FakeVoiceOutput(),
        )
```

- [ ] **Step 8: Run all app unit tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: PASS (the 5 new voice tests + existing CopilotViewModel tests + any others).

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/java/com/virtueson/copilotmaps/voice/VoiceInput.kt android/app/src/main/java/com/virtueson/copilotmaps/voice/VoiceOutput.kt android/app/src/main/java/com/virtueson/copilotmaps/ui/copilot/CopilotState.kt android/app/src/main/java/com/virtueson/copilotmaps/ui/copilot/CopilotViewModel.kt android/app/src/test/java/com/virtueson/copilotmaps/voice/FakeVoice.kt android/app/src/test/java/com/virtueson/copilotmaps/ui/copilot/CopilotVoiceViewModelTest.kt android/app/src/test/java/com/virtueson/copilotmaps/ui/copilot/CopilotViewModelTest.kt
git commit -m "feat(app): voice interfaces + CopilotViewModel STT/TTS orchestration"
```

---

### Task 2: Android `SpeechRecognizer` / `TextToSpeech` implementations

**Files:**
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/voice/AndroidVoiceInput.kt`
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/voice/AndroidVoiceOutput.kt`

**Interfaces:**
- Consumes: `VoiceInput`, `VoiceOutput` (Task 1).
- Produces: `class AndroidVoiceInput(context: Context) : VoiceInput`, `class AndroidVoiceOutput(context: Context) : VoiceOutput`.

> Pure Android framework glue — verified on device in Task 3, not unit-tested (same policy as `FusedLocationProvider`). This task's deliverable is "compiles cleanly".

- [ ] **Step 1: Write `AndroidVoiceInput`**

`voice/AndroidVoiceInput.kt`:
```kotlin
package com.virtueson.copilotmaps.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** Real STT backed by Android's on-device/Google SpeechRecognizer. Main-thread API. */
class AndroidVoiceInput(context: Context) : VoiceInput {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    override fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onError("Speech recognition isn't available on this device.")
            return
        }
        recognizer?.destroy()
        val sr = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val text = results
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                onFinal(text)
            }

            override fun onPartialResults(partialResults: Bundle) {
                val text = partialResults
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotEmpty()) onPartial(text)
            }

            override fun onError(error: Int) = onError(mapError(error))
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        sr.startListening(intent)
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun mapError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that — try again."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't hear anything — try again."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error during speech recognition."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy — try again."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed for voice."
        else -> "Speech recognition error."
    }
}
```

- [ ] **Step 2: Write `AndroidVoiceOutput`**

`voice/AndroidVoiceOutput.kt`:
```kotlin
package com.virtueson.copilotmaps.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Real TTS backed by Android's TextToSpeech engine. */
class AndroidVoiceOutput(context: Context) : VoiceOutput {
    private val tts: TextToSpeech
    private var ready = false
    private var counter = 0
    private val callbacks = mutableMapOf<String, () -> Unit>()
    private var pending: Pair<String, () -> Unit>? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts.language = Locale.getDefault()
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = fire(utteranceId)
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = fire(utteranceId)
                    override fun onError(utteranceId: String?, errorCode: Int) = fire(utteranceId)
                })
                pending?.let { (text, cb) ->
                    pending = null
                    speak(text, cb)
                }
            }
        }
    }

    private fun fire(utteranceId: String?) {
        utteranceId?.let { callbacks.remove(it) }?.invoke()
    }

    override fun speak(text: String, onDone: () -> Unit) {
        if (!ready) {
            pending = text to onDone
            return
        }
        val id = "u${counter++}"
        callbacks[id] = onDone
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    override fun stop() {
        if (ready) tts.stop()
    }

    override fun shutdown() {
        tts.shutdown()
    }
}
```

- [ ] **Step 3: Compile**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/virtueson/copilotmaps/voice/AndroidVoiceInput.kt android/app/src/main/java/com/virtueson/copilotmaps/voice/AndroidVoiceOutput.kt
git commit -m "feat(app): Android SpeechRecognizer + TextToSpeech voice implementations"
```

---

### Task 3: Manifest permission + chat sheet mic UI + MapScreen wiring + on-device verify

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/copilot/CopilotChatSheet.kt`
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt`

**Interfaces:**
- Consumes: `CopilotViewModel.onMicTapped(context)`, `CopilotViewModel.setTtsEnabled(enabled)`, `CopilotUiState` voice fields (Task 1); `AndroidVoiceInput`, `AndroidVoiceOutput` (Task 2).
- Produces: `CopilotChatSheet(state, onSend, onMic, onToggleTts, onDismiss)` — new `onMic: () -> Unit` and `onToggleTts: (Boolean) -> Unit` params.

- [ ] **Step 1: Add the `RECORD_AUDIO` permission**

In `AndroidManifest.xml`, add alongside the existing `<uses-permission>` lines:
```xml
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
```

- [ ] **Step 2: Add mic + mute to `CopilotChatSheet`**

In `CopilotChatSheet.kt`, add these imports:
```kotlin
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
```
Replace the title line:
```kotlin
            Text("Copilot", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
```
with a title row that includes the mute toggle:
```kotlin
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Copilot", modifier = Modifier.weight(1f))
                TextButton(onClick = { onToggleTts(!state.ttsEnabled) }) {
                    Text(if (state.ttsEnabled) "🔊 Voice on" else "🔇 Voice off")
                }
            }
```
Update the function signature:
```kotlin
fun CopilotChatSheet(
    state: CopilotUiState,
    onSend: (String) -> Unit,
    onMic: () -> Unit,
    onToggleTts: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
```
Replace the input `Row(verticalAlignment = Alignment.CenterVertically) { ... }` block with one that adds the mic button and a listening hint:
```kotlin
            if (state.listening) {
                Text(
                    state.partialTranscript ?: "Listening…",
                    color = Color(0xFF1A73E8),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onMic,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when {
                            state.listening -> Color(0xFFEA4335)
                            state.speaking -> Color(0xFFFBBC04)
                            else -> MaterialTheme.colorScheme.primary
                        }
                    ),
                ) { Text(if (state.listening) "Stop" else "🎤") }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask the copilot…") },
                    singleLine = true,
                )
                if (state.sending) {
                    CircularProgressIndicator(modifier = Modifier.padding(start = 12.dp))
                } else {
                    Button(
                        onClick = {
                            val text = draft.trim()
                            if (text.isNotEmpty()) {
                                onSend(text)
                                draft = ""
                            }
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("Send") }
                }
            }
```

- [ ] **Step 3: Wire voice into `MapScreen` — imports**

In `MapScreen.kt`, add:
```kotlin
import com.virtueson.copilotmaps.voice.AndroidVoiceInput
import com.virtueson.copilotmaps.voice.AndroidVoiceOutput
```

- [ ] **Step 4: Build the ViewModel with voice + add the mic permission handler**

In `MapScreen`, change the copilot ViewModel construction to supply the voice
implementations. `remember` the voice objects so a new `TextToSpeech`/`SpeechRecognizer`
isn't created on every recomposition:
```kotlin
    val voiceInput = remember { AndroidVoiceInput(context) }
    val voiceOutput = remember { AndroidVoiceOutput(context) }
    val copilotViewModel: CopilotViewModel = viewModel(
        factory = CopilotViewModelFactory(
            DefaultCopilotRepository(NetworkModule.copilotApi),
            voiceInput,
            voiceOutput,
        )
    )
```
Immediately after `val copilotState by copilotViewModel.state.collectAsStateWithLifecycle()`, add the audio-permission launcher and a mic handler (named `handleMic` to avoid clashing with the `RoutingMap` parameter `onMicCopilot`):
```kotlin
    var pendingMicContext by remember { mutableStateOf<TripContext?>(null) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val tripContext = pendingMicContext
        pendingMicContext = null
        if (granted && tripContext != null) copilotViewModel.onMicTapped(tripContext)
    }
    val handleMic: (TripContext) -> Unit = { tripContext ->
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            copilotViewModel.onMicTapped(tripContext)
        } else {
            pendingMicContext = tripContext
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
```

- [ ] **Step 5: Pass mic + mute into `RoutingMap` (the `MapUiState.Located` branch)**

In the `is MapUiState.Located ->` branch, add two arguments to the `RoutingMap(...)` call (after `onSendCopilot = { ... }`):
```kotlin
                onMicCopilot = { handleMic(buildTripContext(origin, routesState)) },
                onToggleTts = copilotViewModel::setTtsEnabled,
```

- [ ] **Step 6: Update `RoutingMap` signature + the `CopilotChatSheet` call**

Add to the `RoutingMap` signature (after `onSendCopilot: (String) -> Unit,`):
```kotlin
    onMicCopilot: () -> Unit,
    onToggleTts: (Boolean) -> Unit,
```
Update the `CopilotChatSheet(...)` call inside `RoutingMap` to:
```kotlin
            CopilotChatSheet(
                state = copilotState,
                onSend = onSendCopilot,
                onMic = onMicCopilot,
                onToggleTts = onToggleTts,
                onDismiss = { showChat = false },
            )
```

- [ ] **Step 7: Compile**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: On-device verification (Xiaomi 14)**

Ensure backend + tunnel are up (real SumoPod copilot):
```powershell
# terminal A
cd "D:\Russell\Data Science Job\AI Maps\backend"; .\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8000
# terminal B
& "C:\Users\Russell\AppData\Local\Android\Sdk\platform-tools\adb.exe" reverse tcp:8000 tcp:8000
```
Run the app (▶). Plan a route, open **Copilot**, then:
1. Tap **🎤**, grant the microphone permission, say *"which route is faster?"* → it transcribes, sends, and the reply is **spoken aloud**.
2. Tap **🎤** again, say *"any gas on my route?"* → DeepSeek calls `search_places`; reply spoken.
3. Tap **🔊 Voice on** to mute → ask again by voice → reply appears on screen but is **not** spoken.
4. While a reply is speaking, tap **🎤** → speech stops and listening starts (barge-in).
5. Deny the mic permission once → confirm the app shows no crash and typed input still works.

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/AndroidManifest.xml android/app/src/main/java/com/virtueson/copilotmaps/ui/copilot/CopilotChatSheet.kt android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt
git commit -m "feat(app): voice mic + mute UI wired into chat (on-device verified)"
```

---

## Definition of Done (verify all)

- Speaking a question transcribes, sends, and the reply is spoken aloud on the Xiaomi 14.
- Mute toggle silences spoken replies; typed input stays silent (no speech).
- `RECORD_AUDIO` permission deny/grant handled; recognizer/TTS failures degrade to text-only without crashing; barge-in works.
- `CopilotVoiceViewModelTest` (5 cases) + existing app unit tests pass.
- `git status` clean; `backend/.env` / `local.properties` never committed.

## Notes for later (parked)

- Auto-listen conversation loop / wake word.
- Cloud STT (Whisper via SumoPod) behind the same `VoiceInput` interface.
- Actionable copilot "take me there" (Step 4c) — needs a backend response-contract change.
- Turn-by-turn spoken guidance — Step 5 (live navigation).
