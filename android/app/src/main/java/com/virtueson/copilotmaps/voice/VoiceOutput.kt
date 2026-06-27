package com.virtueson.copilotmaps.voice

/** One job: speak a line of text aloud. Easy to fake in tests. */
interface VoiceOutput {
    fun speak(text: String, onDone: () -> Unit = {})
    fun stop()
    fun shutdown()
}
