package com.virtueson.copilotmaps.network

import retrofit2.http.Body
import retrofit2.http.POST

interface PlacesApi {
    @POST("places/search")
    suspend fun searchPlaces(@Body body: PlacesSearchRequestDto): PlacesSearchResponseDto
}
