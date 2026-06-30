package com.virtueson.copilotmaps.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class NavProgress(
    val stepIndex: Int,
    val distanceToTurnMeters: Int,
    val remainingDistanceMeters: Int,
    val arrived: Boolean,
)

fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
    val r = 6_371_000.0
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}

/** Advance from `fromIndex` while within threshold of each upcoming maneuver point. */
fun navProgress(
    steps: List<RouteStep>,
    location: GeoPoint,
    fromIndex: Int,
    arriveThresholdMeters: Double = 30.0,
): NavProgress {
    if (steps.isEmpty()) return NavProgress(0, 0, 0, arrived = true)
    val last = steps.size - 1
    var index = fromIndex.coerceIn(0, last)
    while (index < last && haversineMeters(location, steps[index].location) < arriveThresholdMeters) {
        index++
    }
    val distanceToTurn = haversineMeters(location, steps[index].location)
    var remaining = distanceToTurn
    for (i in (index + 1)..last) remaining += steps[i].distanceMeters
    val arrived = index == last && distanceToTurn < arriveThresholdMeters
    return NavProgress(
        stepIndex = index,
        distanceToTurnMeters = distanceToTurn.toInt(),
        remainingDistanceMeters = remaining.toInt(),
        arrived = arrived,
    )
}
