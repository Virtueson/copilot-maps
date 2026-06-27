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
