package com.virtueson.copilotmaps.data

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Shortest distance in metres from [location] to the route polyline [points],
 * measured against segments (not just vertices) so long straight legs with
 * sparse vertices don't read as off-route. Empty list → 0.0 (treated as
 * on-route); single point → straight-line distance to it. Pure — no Android.
 */
fun distanceToRouteMeters(location: GeoPoint, points: List<GeoPoint>): Double {
    if (points.isEmpty()) return 0.0
    if (points.size == 1) return haversineMeters(location, points[0])

    // Project to a local metre plane centred on `location` (so location = origin).
    val mPerDegLat = 111_320.0
    val mPerDegLng = 111_320.0 * cos(Math.toRadians(location.lat))
    fun px(p: GeoPoint) = (p.lng - location.lng) * mPerDegLng
    fun py(p: GeoPoint) = (p.lat - location.lat) * mPerDegLat

    var min = Double.MAX_VALUE
    for (i in 0 until points.size - 1) {
        val ax = px(points[i]); val ay = py(points[i])
        val bx = px(points[i + 1]); val by = py(points[i + 1])
        val dx = bx - ax; val dy = by - ay
        val segLen2 = dx * dx + dy * dy
        val t = if (segLen2 == 0.0) 0.0 else ((-ax * dx + -ay * dy) / segLen2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        val d = sqrt(cx * cx + cy * cy) // distance from origin (location) to closest point
        if (d < min) min = d
    }
    return min
}
