package com.virtueson.copilotmaps.data

data class ColoredSegment(
    val points: List<GeoPoint>,
    val speed: TrafficSpeed,
)

/**
 * Slices [points] into colored segments by [intervals], inclusive on both ends so
 * adjacent segments share a boundary point (continuous line). Indices are clamped;
 * single-point intervals are skipped. With no usable intervals, returns one UNKNOWN
 * segment spanning all points so the route still draws.
 */
fun buildTrafficSegments(
    points: List<GeoPoint>,
    intervals: List<TrafficInterval>,
): List<ColoredSegment> {
    if (points.size < 2) return emptyList()
    val lastIndex = points.lastIndex
    val segments = mutableListOf<ColoredSegment>()
    for (interval in intervals) {
        val start = interval.startIndex.coerceIn(0, lastIndex)
        val end = interval.endIndex.coerceIn(start, lastIndex)
        if (end == start) continue
        segments.add(ColoredSegment(points.subList(start, end + 1).toList(), interval.speed))
    }
    if (segments.isEmpty()) {
        return listOf(ColoredSegment(points, TrafficSpeed.UNKNOWN))
    }
    return segments
}
