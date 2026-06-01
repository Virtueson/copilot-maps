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

data class RouteDto(
    val id: String,
    val summary: String,
    @Json(name = "distance_meters") val distanceMeters: Int,
    @Json(name = "duration_seconds") val durationSeconds: Int,
    val polyline: String,
)

data class RoutePlanResponseDto(
    val routes: List<RouteDto>,
)
