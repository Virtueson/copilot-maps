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
