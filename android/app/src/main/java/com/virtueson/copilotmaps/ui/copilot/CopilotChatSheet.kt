package com.virtueson.copilotmaps.ui.copilot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.virtueson.copilotmaps.data.ChatMessage
import com.virtueson.copilotmaps.data.Role

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopilotChatSheet(
    state: CopilotUiState,
    onSend: (String) -> Unit,
    onMic: () -> Unit,
    onToggleTts: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Copilot", modifier = Modifier.weight(1f))
                TextButton(onClick = { onToggleTts(!state.ttsEnabled) }) {
                    Text(if (state.ttsEnabled) "🔊 Voice on" else "🔇 Voice off")
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 360.dp)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.messages.isEmpty()) {
                    item {
                        Text(
                            "Ask me about your trip — \"which route is faster?\", " +
                                "\"any gas on my route?\"",
                            color = Color(0xFF5F6368),
                        )
                    }
                }
                items(state.messages) { message -> MessageBubble(message) }
            }

            state.error?.let {
                Text(it, color = Color(0xFFEA4335), modifier = Modifier.padding(bottom = 8.dp))
            }

            if (state.listening) {
                Text(
                    state.partialTranscript ?: "Listening…",
                    color = Color(0xFF1A73E8),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onMic,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when {
                            state.listening -> Color(0xFFEA4335)
                            state.speaking -> Color(0xFFFBBC04)
                            else -> MaterialTheme.colorScheme.primary
                        }
                    ),
                ) { Text(if (state.listening) "Stop" else "🎤") }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask the copilot…") },
                    singleLine = true,
                )
                if (state.sending) {
                    CircularProgressIndicator(modifier = Modifier.padding(start = 12.dp))
                } else {
                    Button(
                        onClick = {
                            val text = draft.trim()
                            if (text.isNotEmpty()) {
                                onSend(text)
                                draft = ""
                            }
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("Send") }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val fromUser = message.role == Role.USER
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = if (fromUser) Color(0xFFD2E3FC) else Color(0xFFF1F3F4),
            modifier = Modifier
                .align(if (fromUser) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(start = if (fromUser) 48.dp else 0.dp, end = if (fromUser) 0.dp else 48.dp),
        ) {
            Text(message.content, modifier = Modifier.padding(10.dp))
        }
    }
}
