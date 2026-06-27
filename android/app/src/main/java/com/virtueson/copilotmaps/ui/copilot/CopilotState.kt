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
