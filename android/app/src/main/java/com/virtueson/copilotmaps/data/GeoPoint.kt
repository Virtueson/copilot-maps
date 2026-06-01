package com.virtueson.copilotmaps.data

/** Framework-free coordinate so domain/ViewModel logic stays JVM-unit-testable. */
data class GeoPoint(
    val lat: Double,
    val lng: Double,
)
