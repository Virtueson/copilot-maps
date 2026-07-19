package com.virtueson.copilotmaps.data

import com.virtueson.copilotmaps.network.LatLngDto
import com.virtueson.copilotmaps.network.PlaceDto
import com.virtueson.copilotmaps.network.PlacesApi
import com.virtueson.copilotmaps.network.PlacesSearchRequestDto

fun PlaceDto.toPlace(): Place = Place(
    id = id,
    name = name,
    location = GeoPoint(lat, lng),
    address = address,
    rating = rating,
    priceLevel = priceLevel,
    openNow = openNow,
)

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
            val places = response.places.map { it.toPlace() }
            PlacesResult.Success(places, response.mode)
        } catch (e: Exception) {
            PlacesResult.Failure("Can't reach the server — is the backend running and adb reverse set?")
        }
    }
}
