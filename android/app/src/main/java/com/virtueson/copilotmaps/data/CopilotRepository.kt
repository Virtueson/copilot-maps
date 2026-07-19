package com.virtueson.copilotmaps.data

import com.virtueson.copilotmaps.network.ChatMessageDto
import com.virtueson.copilotmaps.network.CopilotApi
import com.virtueson.copilotmaps.network.CopilotAskRequestDto
import com.virtueson.copilotmaps.network.CopilotContextDto
import com.virtueson.copilotmaps.network.LatLngDto
import com.virtueson.copilotmaps.network.RouteSummaryDto

interface CopilotRepository {
    suspend fun ask(
        messages: List<ChatMessage>,
        context: TripContext,
        sessionId: String,
        turnIndex: Int,
    ): CopilotResult
}

class DefaultCopilotRepository(
    private val api: CopilotApi,
) : CopilotRepository {
    override suspend fun ask(
        messages: List<ChatMessage>,
        context: TripContext,
        sessionId: String,
        turnIndex: Int,
    ): CopilotResult {
        return try {
            val response = api.ask(
                CopilotAskRequestDto(
                    messages = messages.map { ChatMessageDto(it.role.name.lowercase(), it.content) },
                    context = CopilotContextDto(
                        origin = LatLngDto(context.origin.lat, context.origin.lng),
                        selectedRoutePolyline = context.selectedRoutePolyline,
                        routes = context.routes.map {
                            RouteSummaryDto(
                                summary = it.summary,
                                distanceMeters = it.distanceMeters,
                                durationSeconds = it.durationSeconds,
                                traffic = it.traffic,
                                selected = it.selected,
                            )
                        },
                    ),
                    sessionId = sessionId,
                    turnIndex = turnIndex,
                )
            )
            CopilotResult.Success(
                reply = response.reply,
                language = response.language,
                places = response.places.map { it.toPlace() },
                navigation = response.navigation?.toPlace(),
            )
        } catch (e: Exception) {
            CopilotResult.Failure("Can't reach the server — is the backend running and adb reverse set?")
        }
    }
}
