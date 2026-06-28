package com.virtueson.copilotmaps.data

/** One turn-by-turn maneuver. `location` is where the maneuver happens (step start). */
data class RouteStep(
    val instruction: String,
    val maneuver: String,
    val distanceMeters: Int,
    val location: GeoPoint,
)

/** A drawable route: geometry already decoded into points. */
data class Route(
    val id: String,
    val summary: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val points: List<GeoPoint>,
    val polyline: String = "",
    val trafficIntervals: List<TrafficInterval> = emptyList(),
    val steps: List<RouteStep> = emptyList(),
)

sealed interface RoutesResult {
    data class Success(val routes: List<Route>) : RoutesResult
    data class Failure(val reason: String) : RoutesResult
}
