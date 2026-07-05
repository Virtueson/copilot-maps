package com.virtueson.copilotmaps.data

import com.google.maps.android.PolyUtil
import com.virtueson.copilotmaps.network.LatLngDto
import com.virtueson.copilotmaps.network.RoutePlanRequestDto
import com.virtueson.copilotmaps.network.RoutesApi

interface RoutesRepository {
    suspend fun planRoutes(origin: GeoPoint, destination: GeoPoint, languageTag: String? = null): RoutesResult
}

private fun String.toTrafficSpeed(): TrafficSpeed = when (this) {
    "NORMAL" -> TrafficSpeed.NORMAL
    "SLOW" -> TrafficSpeed.SLOW
    "TRAFFIC_JAM" -> TrafficSpeed.JAM
    else -> TrafficSpeed.UNKNOWN
}

class DefaultRoutesRepository(
    private val api: RoutesApi,
) : RoutesRepository {
    override suspend fun planRoutes(origin: GeoPoint, destination: GeoPoint, languageTag: String?): RoutesResult {
        return try {
            val response = api.planRoutes(
                RoutePlanRequestDto(
                    origin = LatLngDto(origin.lat, origin.lng),
                    destination = LatLngDto(destination.lat, destination.lng),
                    languageCode = languageTag,
                )
            )
            val routes = response.routes.map { dto ->
                Route(
                    id = dto.id,
                    summary = dto.summary,
                    distanceMeters = dto.distanceMeters,
                    durationSeconds = dto.durationSeconds,
                    points = PolyUtil.decode(dto.polyline).map { GeoPoint(it.latitude, it.longitude) },
                    polyline = dto.polyline,
                    trafficIntervals = dto.trafficIntervals.map {
                        TrafficInterval(it.startIndex, it.endIndex, it.speed.toTrafficSpeed())
                    },
                    steps = dto.steps.map {
                        RouteStep(
                            instruction = it.instruction,
                            maneuver = it.maneuver,
                            distanceMeters = it.distanceMeters,
                            location = GeoPoint(it.location.lat, it.location.lng),
                        )
                    },
                )
            }
            if (routes.isEmpty()) {
                RoutesResult.Failure("No route found for that destination.")
            } else {
                RoutesResult.Success(routes)
            }
        } catch (e: Exception) {
            RoutesResult.Failure("Can't reach the server — is the backend running and adb reverse set?")
        }
    }
}
