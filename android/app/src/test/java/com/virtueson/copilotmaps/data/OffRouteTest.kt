package com.virtueson.copilotmaps.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun pt(lat: Double, lng: Double) = GeoPoint(lat, lng)

// Route heading east along the equator: (0,0) -> (0, 0.010) ~1113 m long.
private val routePts = listOf(pt(0.0, 0.000), pt(0.0, 0.010))

class OffRouteTest {

    @Test
    fun `on the route line is near zero`() {
        assertTrue(distanceToRouteMeters(pt(0.0, 0.005), routePts) < 1.0)
    }

    @Test
    fun `perpendicular offset equals the offset distance`() {
        // 0.0009 deg latitude north of the line ~ 100 m
        assertEquals(100.0, distanceToRouteMeters(pt(0.0009, 0.005), routePts), 5.0)
    }

    @Test
    fun `past the end uses the endpoint distance`() {
        // 0.001 deg lng east of the last vertex ~ 111 m
        assertEquals(111.3, distanceToRouteMeters(pt(0.0, 0.011), routePts), 5.0)
    }

    @Test
    fun `mid segment with sparse vertices stays small`() {
        // far from both vertices but still on the single long segment
        assertTrue(distanceToRouteMeters(pt(0.0, 0.009), routePts) < 1.0)
    }

    @Test
    fun `empty route is zero`() {
        assertEquals(0.0, distanceToRouteMeters(pt(1.0, 1.0), emptyList()), 0.0001)
    }
}
