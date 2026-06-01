package com.virtueson.copilotmaps.data

import com.google.maps.android.PolyUtil
import com.virtueson.copilotmaps.network.LatLngDto
import com.virtueson.copilotmaps.network.RoutePlanRequestDto
import com.virtueson.copilotmaps.network.RoutesApi

interface RoutesRepository {
    suspend fun planRoutes(origin: GeoPoint, destination: GeoPoint): RoutesResult
}

class DefaultRoutesRepository(
    private val api: RoutesApi,
) : RoutesRepository {
    override suspend fun planRoutes(origin: GeoPoint, destination: GeoPoint): RoutesResult {
        return try {
            val response = api.planRoutes(
                RoutePlanRequestDto(
                    origin = LatLngDto(origin.lat, origin.lng),
                    destination = LatLngDto(destination.lat, destination.lng),
                )
            )
            val routes = response.routes.map { dto ->
                Route(
                    id = dto.id,
                    summary = dto.summary,
                    distanceMeters = dto.distanceMeters,
                    durationSeconds = dto.durationSeconds,
                    points = PolyUtil.decode(dto.polyline).map { GeoPoint(it.latitude, it.longitude) },
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
