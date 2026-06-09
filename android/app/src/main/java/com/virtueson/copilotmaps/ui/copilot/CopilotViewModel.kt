package com.virtueson.copilotmaps.ui.copilot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.virtueson.copilotmaps.data.ChatMessage
import com.virtueson.copilotmaps.data.CopilotRepository
import com.virtueson.copilotmaps.data.CopilotResult
import com.virtueson.copilotmaps.data.Role
import com.virtueson.copilotmaps.data.TripContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val MAX_TURNS = 12

class CopilotViewModel(
    private val repository: CopilotRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CopilotUiState())
    val state: StateFlow<CopilotUiState> = _state.asStateFlow()

    fun sendMessage(text: String, context: TripContext) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.sending) return

        val withUser = _state.value.messages + ChatMessage(Role.USER, trimmed)
        _state.value = _state.value.copy(messages = withUser, sending = true, error = null)

        viewModelScope.launch {
            val history = withUser.takeLast(MAX_TURNS)
            when (val result = repository.ask(history, context)) {
                is CopilotResult.Success ->
                    _state.value = _state.value.copy(
                        messages = _state.value.messages + ChatMessage(Role.ASSISTANT, result.reply),
                        sending = false,
                    )
                is CopilotResult.Failure ->
                    _state.value = _state.value.copy(sending = false, error = result.reason)
            }
        }
    }
}

class CopilotViewModelFactory(
    private val repository: CopilotRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CopilotViewModel(repository) as T
}
