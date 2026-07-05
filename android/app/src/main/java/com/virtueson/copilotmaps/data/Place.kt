package com.virtueson.copilotmaps.data

data class Place(
    val id: String,
    val name: String,
    val location: GeoPoint,
    val address: String?,
    val rating: Double?,
    val priceLevel: String? = null,
    val openNow: Boolean? = null,
)

sealed interface PlacesResult {
    data class Success(val places: List<Place>, val mode: String) : PlacesResult
    data class Failure(val reason: String) : PlacesResult
}
