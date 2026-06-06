package com.virtueson.copilotmaps.network

data class PlaceDto(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String? = null,
    val rating: Double? = null,
)

data class PlacesSearchRequestDto(
    val query: String,
    val origin: LatLngDto,
    val polyline: String? = null,
)

data class PlacesSearchResponseDto(
    val mode: String,
    val places: List<PlaceDto>,
)
