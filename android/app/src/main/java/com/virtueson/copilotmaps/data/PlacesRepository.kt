package com.virtueson.copilotmaps.data

import com.virtueson.copilotmaps.network.LatLngDto
import com.virtueson.copilotmaps.network.PlacesApi
import com.virtueson.copilotmaps.network.PlacesSearchRequestDto

interface PlacesRepository {
    suspend fun searchPlaces(query: String, origin: GeoPoint, polyline: String?): PlacesResult
}

class DefaultPlacesRepository(
    private val api: PlacesApi,
) : PlacesRepository {
    override suspend fun searchPlaces(query: String, origin: GeoPoint, polyline: String?): PlacesResult {
        return try {
            val response = api.searchPlaces(
                PlacesSearchRequestDto(
                    query = query,
                    origin = LatLngDto(origin.lat, origin.lng),
                    polyline = polyline,
                )
            )
            val places = response.places.map { dto ->
                Place(
                    id = dto.id,
                    name = dto.name,
                    location = GeoPoint(dto.lat, dto.lng),
                    address = dto.address,
                    rating = dto.rating,
                )
            }
            PlacesResult.Success(places, response.mode)
        } catch (e: Exception) {
            PlacesResult.Failure("Can't reach the server — is the backend running and adb reverse set?")
        }
    }
}
