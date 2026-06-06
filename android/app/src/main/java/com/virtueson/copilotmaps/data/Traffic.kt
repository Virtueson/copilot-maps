package com.virtueson.copilotmaps.data

enum class TrafficSpeed { NORMAL, SLOW, JAM, UNKNOWN }

data class TrafficInterval(
    val startIndex: Int,
    val endIndex: Int,
    val speed: TrafficSpeed,
)
