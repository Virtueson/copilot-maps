package com.virtueson.copilotmaps.data

/** A drawable route: geometry already decoded into points. */
data class Route(
    val id: String,
    val summary: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val points: List<GeoPoint>,
    val trafficIntervals: List<TrafficInterval> = emptyList(),
)

sealed interface RoutesResult {
    data class Success(val routes: List<Route>) : RoutesResult
    data class Failure(val reason: String) : RoutesResult
}
