package com.virtueson.copilotmaps.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** Real STT backed by Android's SpeechRecognizer. Main-thread API. */
class AndroidVoiceInput(
    context: Context,
    private val languageProvider: () -> Locale = { Locale.getDefault() },
) : VoiceInput {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    override fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val sr = createRecognizer()
        if (sr == null) {
            onError("Speech recognition isn't available on this device.")
            return
        }
        recognizer?.destroy()
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageProvider().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        sr.startListening(intent)
    }

    /**
     * Prefer Google's recognition service when present. The device default can be an
     * OEM service (e.g. Xiaomi MiBrain) that rejects third-party apps with
     * ERROR_INSUFFICIENT_PERMISSIONS even when RECORD_AUDIO is granted.
     */
    private fun createRecognizer(): SpeechRecognizer? {
        val services = appContext.packageManager
            .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        val google = services
            .map { it.serviceInfo }
            .firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
        return when {
            google != null -> SpeechRecognizer.createSpeechRecognizer(
                appContext, ComponentName(google.packageName, google.name),
            )
            SpeechRecognizer.isRecognitionAvailable(appContext) ->
                SpeechRecognizer.createSpeechRecognizer(appContext)
            else -> null
        }
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
