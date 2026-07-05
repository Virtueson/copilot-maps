package com.virtueson.copilotmaps.network

import com.squareup.moshi.Json

data class PlaceDto(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String? = null,
    val rating: Double? = null,
    @Json(name = "price_level") val priceLevel: String? = null,
    @Json(name = "open_now") val openNow: Boolean? = null,
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
