package com.virtueson.copilotmaps.network

import retrofit2.http.Body
import retrofit2.http.POST

interface RoutesApi {
    @POST("routes/plan")
    suspend fun planRoutes(@Body body: RoutePlanRequestDto): RoutePlanResponseDto
}
