package com.virtueson.copilotmaps.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

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
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    val routesApi: RoutesApi by lazy { retrofit.create(RoutesApi::class.java) }
    val placesApi: PlacesApi by lazy { retrofit.create(PlacesApi::class.java) }
}
