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
