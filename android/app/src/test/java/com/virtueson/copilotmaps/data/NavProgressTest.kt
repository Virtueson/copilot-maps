package com.virtueson.copilotmaps.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun step(name: String, maneuver: String, dist: Int, lat: Double, lng: Double) =
    RouteStep(instruction = name, maneuver = maneuver, distanceMeters = dist, location = GeoPoint(lat, lng))

// 4 steps roughly along the equator heading east. ~0.01 deg lng ~= 1.11 km.
private val steps = listOf(
    step("Head", "DEPART", 100, 0.0, 0.000),
    step("Turn left", "TURN_LEFT", 200, 0.0, 0.010),
    step("Turn right", "TURN_RIGHT", 300, 0.0, 0.020),
    step("Arrive", "ARRIVE", 0, 0.0, 0.030),
)

class NavProgressTest {

    @Test
    fun `stays on step when far from the upcoming turn`() {
        val p = navProgress(steps, GeoPoint(0.0, 0.000), fromIndex = 1)
        assertEquals(1, p.stepIndex)               // far from step 1 (~1.1 km), no advance
        assertTrue(p.distanceToTurnMeters > 900)
        assertFalse(p.arrived)
    }

    @Test
    fun `advances when within threshold of the upcoming turn`() {
        val p = navProgress(steps, GeoPoint(0.0, 0.0100001), fromIndex = 1) // ~1 m from step 1
        assertEquals(2, p.stepIndex)
    }

    @Test
    fun `remaining is distance to turn plus later step distances`() {
        val p = navProgress(steps, GeoPoint(0.0, 0.000), fromIndex = 1)
        // later steps after index 1: 300 (idx2) + 0 (idx3) = 300
        assertEquals(300, p.remainingDistanceMeters - p.distanceToTurnMeters)
    }

    @Test
    fun `arrived when within threshold of the final step`() {
        val p = navProgress(steps, GeoPoint(0.0, 0.0300001), fromIndex = 3) // ~1 m from arrive
        assertEquals(3, p.stepIndex)
        assertTrue(p.arrived)
    }
}
