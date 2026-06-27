package com.virtueson.copilotmaps.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/** Single Retrofit instance for the app. Base URL works via `adb reverse`. */
object NetworkModule {
    private const val BASE_URL = "http://localhost:8000/"

    private val retrofit: Retrofit by lazy {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        // The /copilot/ask endpoint runs an agentic loop (LLM + tool calls), which can
        // take far longer than OkHttp's 10s default. Give reads/writes generous headroom.
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    val routesApi: RoutesApi by lazy { retrofit.create(RoutesApi::class.java) }
    val placesApi: PlacesApi by lazy { retrofit.create(PlacesApi::class.java) }
    val copilotApi: CopilotApi by lazy { retrofit.create(CopilotApi::class.java) }
}
