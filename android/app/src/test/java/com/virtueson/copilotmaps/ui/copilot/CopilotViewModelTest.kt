package com.virtueson.copilotmaps.ui.copilot

import com.virtueson.copilotmaps.MainDispatcherRule
import com.virtueson.copilotmaps.data.ChatMessage
import com.virtueson.copilotmaps.data.CopilotRepository
import com.virtueson.copilotmaps.data.CopilotResult
import com.virtueson.copilotmaps.data.GeoPoint
import com.virtueson.copilotmaps.data.Role
import com.virtueson.copilotmaps.data.TripContext
import com.virtueson.copilotmaps.voice.FakeVoiceInput
import com.virtueson.copilotmaps.voice.FakeVoiceOutput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

private class FakeCopilotRepository(private val result: CopilotResult) : CopilotRepository {
    override suspend fun ask(messages: List<ChatMessage>, context: TripContext): CopilotResult = result
}

private val ctx = TripContext(GeoPoint(0.0, 0.0), null, emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
class CopilotViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `success appends user then assistant message`() = runTest {
        val vm = CopilotViewModel(
            FakeCopilotRepository(CopilotResult.Success("Hi there")),
            FakeVoiceInput(), FakeVoiceOutput(),
        )

        vm.sendMessage("hello", ctx)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(2, state.messages.size)
        assertEquals(Role.USER, state.messages[0].role)
        assertEquals("hello", state.messages[0].content)
        assertEquals(Role.ASSISTANT, state.messages[1].role)
        assertEquals("Hi there", state.messages[1].content)
        assertFalse(state.sending)
        assertNull(state.error)
    }

    @Test
    fun `failure sets error and keeps only the user message`() = runTest {
        val vm = CopilotViewModel(
            FakeCopilotRepository(CopilotResult.Failure("boom")),
            FakeVoiceInput(), FakeVoiceOutput(),
        )

        vm.sendMessage("hello", ctx)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(1, state.messages.size)
        assertEquals(Role.USER, state.messages[0].role)
        assertEquals("boom", state.error)
        assertFalse(state.sending)
    }
}
