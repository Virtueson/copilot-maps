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
    private var pending: Triple<String, Locale?, () -> Unit>? = null

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
                pending?.let { (text, locale, cb) ->
                    pending = null
                    speak(text, true, locale, cb)
                }
            }
        }
    }

    private fun fire(utteranceId: String?) {
        utteranceId?.let { callbacks.remove(it) }?.invoke()
    }

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

    override fun stop() {
        if (ready) tts.stop()
    }

    override fun shutdown() {
        tts.shutdown()
    }
}
