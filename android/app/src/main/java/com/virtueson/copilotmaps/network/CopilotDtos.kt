package com.virtueson.copilotmaps.network

import com.squareup.moshi.Json

data class ChatMessageDto(
    val role: String,
    val content: String,
)

data class RouteSummaryDto(
    val summary: String,
    @Json(name = "distance_meters") val distanceMeters: Int,
    @Json(name = "duration_seconds") val durationSeconds: Int,
    val traffic: String,
    val selected: Boolean,
)

data class CopilotContextDto(
    val origin: LatLngDto,
    @Json(name = "selected_route_polyline") val selectedRoutePolyline: String? = null,
    val routes: List<RouteSummaryDto> = emptyList(),
)

data class CopilotAskRequestDto(
    val messages: List<ChatMessageDto>,
    val context: CopilotContextDto,
)

data class CopilotAskResponseDto(
    val reply: String,
    val language: String? = null,
)
