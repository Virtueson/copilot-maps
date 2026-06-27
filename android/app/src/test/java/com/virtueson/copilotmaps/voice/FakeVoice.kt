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
