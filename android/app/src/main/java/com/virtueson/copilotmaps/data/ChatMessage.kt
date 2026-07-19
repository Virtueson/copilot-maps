package com.virtueson.copilotmaps.data

enum class Role { USER, ASSISTANT }

data class ChatMessage(
    val role: Role,
    val content: String,
)

data class RouteSummary(
    val summary: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val traffic: String,
    val selected: Boolean,
)

data class TripContext(
    val origin: GeoPoint,
    val selectedRoutePolyline: String?,
    val routes: List<RouteSummary>,
)

sealed interface CopilotResult {
    data class Success(
        val reply: String,
        val language: String? = null,
        val places: List<Place> = emptyList(),
        val navigation: Place? = null,
    ) : CopilotResult
    data class Failure(val reason: String) : CopilotResult
}
