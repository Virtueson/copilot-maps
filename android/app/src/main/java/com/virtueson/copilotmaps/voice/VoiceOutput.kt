package com.virtueson.copilotmaps.voice

import java.util.Locale

/** One job: speak a line of text aloud. Easy to fake in tests. */
interface VoiceOutput {
    fun speak(text: String, flush: Boolean = true, locale: Locale? = null, onDone: () -> Unit = {})
    fun stop()
    fun shutdown()
}
