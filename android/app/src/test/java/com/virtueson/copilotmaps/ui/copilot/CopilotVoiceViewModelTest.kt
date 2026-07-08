package com.virtueson.copilotmaps.ui.copilot

import com.virtueson.copilotmaps.MainDispatcherRule
import com.virtueson.copilotmaps.data.ChatMessage
import com.virtueson.copilotmaps.data.CopilotRepository
import com.virtueson.copilotmaps.data.CopilotResult
import com.virtueson.copilotmaps.data.GeoPoint
import com.virtueson.copilotmaps.data.TripContext
import com.virtueson.copilotmaps.voice.FakeVoiceInput
import com.virtueson.copilotmaps.voice.FakeVoiceOutput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeRepo(private val result: CopilotResult) : CopilotRepository {
    override suspend fun ask(
        messages: List<ChatMessage>,
        context: TripContext,
        sessionId: String,
        turnIndex: Int,
    ): CopilotResult = result
}

private val ctx = TripContext(GeoPoint(0.0, 0.0), null, emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
class CopilotVoiceViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun vm(
        result: CopilotResult = CopilotResult.Success("Shell is 1km ahead."),
        input: FakeVoiceInput = FakeVoiceInput(),
        output: FakeVoiceOutput = FakeVoiceOutput(),
    ) = CopilotViewModel(FakeRepo(result), input, output)

    @Test
    fun `mic tap when idle starts listening`() {
        val input = FakeVoiceInput()
        val model = vm(input = input)

        model.onMicTapped(ctx)

        assertTrue(input.started)
        assertTrue(model.state.value.listening)
    }

    @Test
    fun `final transcript sends and speaks the reply`() = runTest {
        val input = FakeVoiceInput()
        val output = FakeVoiceOutput()
        val model = vm(input = input, output = output)

        model.onMicTapped(ctx)
        input.emitFinal("any gas on my route?")
        advanceUntilIdle()

        val state = model.state.value
        assertEquals(2, state.messages.size)            // user + assistant
        assertEquals("Shell is 1km ahead.", state.messages[1].content)
        assertEquals(listOf("Shell is 1km ahead."), output.spoken)
        assertFalse(state.listening)
    }

    @Test
    fun `muted does not speak the reply`() = runTest {
        val output = FakeVoiceOutput()
        val input = FakeVoiceInput()
        val model = vm(input = input, output = output)
        model.setTtsEnabled(false)

        model.onMicTapped(ctx)
        input.emitFinal("any gas on my route?")
        advanceUntilIdle()

        assertTrue(output.spoken.isEmpty())
        assertEquals(2, model.state.value.messages.size) // reply still on screen
    }

    @Test
    fun `recognizer error surfaces and sends nothing`() = runTest {
        val input = FakeVoiceInput()
        val model = vm(input = input)

        model.onMicTapped(ctx)
        input.emitError("Didn't catch that — try again.")
        advanceUntilIdle()

        val state = model.state.value
        assertEquals("Didn't catch that — try again.", state.error)
        assertTrue(state.messages.isEmpty())
        assertFalse(state.listening)
    }

    @Test
    fun `tap while listening stops the recognizer`() {
        val input = FakeVoiceInput()
        val model = vm(input = input)

        model.onMicTapped(ctx)   // start
        model.onMicTapped(ctx)   // stop

        assertTrue(input.stopped)
        assertFalse(model.state.value.listening)
    }
}
