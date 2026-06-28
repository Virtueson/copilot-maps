package com.virtueson.copilotmaps.network

import com.squareup.moshi.Json

data class LatLngDto(
    val lat: Double,
    val lng: Double,
)

data class RoutePlanRequestDto(
    val origin: LatLngDto,
    val destination: LatLngDto,
)

data class TrafficIntervalDto(
    @Json(name = "start_index") val startIndex: Int,
    @Json(name = "end_index") val endIndex: Int,
    val speed: String,
)

data class RouteStepDto(
    val instruction: String,
    val maneuver: String,
    @Json(name = "distance_meters") val distanceMeters: Int,
    val location: LatLngDto,
)

data class RouteDto(
    val id: String,
    val summary: String,
    @Json(name = "distance_meters") val distanceMeters: Int,
    @Json(name = "duration_seconds") val durationSeconds: Int,
    val polyline: String,
    @Json(name = "traffic_intervals") val trafficIntervals: List<TrafficIntervalDto> = emptyList(),
    @Json(name = "steps") val steps: List<RouteStepDto> = emptyList(),
)

data class RoutePlanResponseDto(
    val routes: List<RouteDto>,
)
