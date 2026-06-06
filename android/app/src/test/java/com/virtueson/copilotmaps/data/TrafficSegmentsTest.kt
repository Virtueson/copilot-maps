package com.virtueson.copilotmaps.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficSegmentsTest {

    private fun points(n: Int): List<GeoPoint> =
        (0 until n).map { GeoPoint(it.toDouble(), it.toDouble()) }

    @Test
    fun `splits into inclusive segments that share boundary points`() {
        val pts = points(7) // indices 0..6
        val intervals = listOf(
            TrafficInterval(0, 2, TrafficSpeed.NORMAL),
            TrafficInterval(2, 4, TrafficSpeed.SLOW),
            TrafficInterval(4, 6, TrafficSpeed.JAM),
        )

        val segments = buildTrafficSegments(pts, intervals)

        assertEquals(3, segments.size)
        assertEquals(listOf(pts[0], pts[1], pts[2]), segments[0].points)
        assertEquals(TrafficSpeed.NORMAL, segments[0].speed)
        assertEquals(listOf(pts[2], pts[3], pts[4]), segments[1].points)
        assertEquals(TrafficSpeed.SLOW, segments[1].speed)
        assertEquals(listOf(pts[4], pts[5], pts[6]), segments[2].points)
        assertEquals(TrafficSpeed.JAM, segments[2].speed)
        // boundary points are shared so the line is continuous
        assertEquals(segments[0].points.last(), segments[1].points.first())
    }

    @Test
    fun `no intervals yields one UNKNOWN segment spanning all points`() {
        val pts = points(4)

        val segments = buildTrafficSegments(pts, emptyList())

        assertEquals(1, segments.size)
        assertEquals(pts, segments[0].points)
        assertEquals(TrafficSpeed.UNKNOWN, segments[0].speed)
    }

    @Test
    fun `out-of-range indices are clamped and degenerate intervals skipped`() {
        val pts = points(4) // indices 0..3
        val intervals = listOf(
            TrafficInterval(-5, 2, TrafficSpeed.NORMAL), // start clamps to 0
            TrafficInterval(2, 99, TrafficSpeed.JAM),    // end clamps to 3
            TrafficInterval(3, 3, TrafficSpeed.SLOW),    // degenerate -> skipped
        )

        val segments = buildTrafficSegments(pts, intervals)

        assertEquals(2, segments.size)
        assertEquals(listOf(pts[0], pts[1], pts[2]), segments[0].points)
        assertEquals(listOf(pts[2], pts[3]), segments[1].points)
    }
}
