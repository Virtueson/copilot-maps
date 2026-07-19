package com.virtueson.copilotmaps.data

import com.virtueson.copilotmaps.network.CopilotApi
import com.virtueson.copilotmaps.network.CopilotAskRequestDto
import com.virtueson.copilotmaps.network.CopilotAskResponseDto
import com.virtueson.copilotmaps.network.PlaceDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeCopilotApi(private val response: CopilotAskResponseDto) : CopilotApi {
    override suspend fun ask(body: CopilotAskRequestDto): CopilotAskResponseDto = response
}

class CopilotRepositoryTest {
    @Test
    fun `maps response places into the success result`() = runBlocking {
        val api = FakeCopilotApi(
            CopilotAskResponseDto(
                reply = "gas ahead",
                language = "en",
                places = listOf(
                    PlaceDto(id = "p1", name = "Shell Tendean", lat = -6.24, lng = 106.82, rating = 4.3),
                ),
                navigation = null,
            )
        )
        val repo = DefaultCopilotRepository(api)

        val result = repo.ask(
            messages = emptyList(),
            context = TripContext(GeoPoint(-6.24, 106.82), null, emptyList()),
            sessionId = "s",
            turnIndex = 0,
        )

        result as CopilotResult.Success
        assertEquals("gas ahead", result.reply)
        assertEquals(listOf("Shell Tendean"), result.places.map { it.name })
        assertEquals(GeoPoint(-6.24, 106.82), result.places.first().location)
        assertNull(result.navigation)
    }
}
