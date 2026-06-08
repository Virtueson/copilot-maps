package com.virtueson.copilotmaps.network

import retrofit2.http.Body
import retrofit2.http.POST

interface CopilotApi {
    @POST("copilot/ask")
    suspend fun ask(@Body body: CopilotAskRequestDto): CopilotAskResponseDto
}
