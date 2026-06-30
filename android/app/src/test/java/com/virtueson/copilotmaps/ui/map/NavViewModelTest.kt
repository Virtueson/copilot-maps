package com.virtueson.copilotmaps.ui.map

import com.virtueson.copilotmaps.data.GeoPoint
import com.virtueson.copilotmaps.data.Route
import com.virtueson.copilotmaps.data.RouteStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun step(name: String, maneuver: String, dist: Int, lat: Double, lng: Double) =
    RouteStep(name, maneuver, dist, GeoPoint(lat, lng))

private fun route(steps: List<RouteStep>) = Route(
    id = "r0", summary = "Test", distanceMeters = 600, durationSeconds = 600,
    points = emptyList(), polyline = "", trafficIntervals = emptyList(), steps = steps,
)

private val steps = listOf(
    step("Head", "DEPART", 100, 0.0, 0.000),
    step("Turn left", "TURN_LEFT", 200, 0.0, 0.010),
    step("Arrive", "ARRIVE", 0, 0.0, 0.020),
)

class NavViewModelTest {

    @Test
    fun `start emits Active with the first instruction`() {
        val vm = NavViewModel(nowEpochSeconds = { 1000L })
        vm.start(route(steps))

        val s = vm.state.value
        assertTrue(s is NavUiState.Active)
        s as NavUiState.Active
        assertEquals("Head", s.instruction)
    }

    @Test
    fun `start with no steps stays Inactive`() {
        val vm = NavViewModel(nowEpochSeconds = { 1000L })
        vm.start(route(emptyList()))
        assertTrue(vm.state.value is NavUiState.Inactive)
    }

    @Test
    fun `onLocation advances to the next instruction`() {
        val vm = NavViewModel(nowEpochSeconds = { 1000L })
        vm.start(route(steps))

        vm.onLocation(GeoPoint(0.0, 0.0000001)) // ~at step 0 -> advances to step 1

        val s = vm.state.value as NavUiState.Active
        assertEquals("Turn left", s.instruction)
    }

    @Test
    fun `end returns to Inactive`() {
        val vm = NavViewModel(nowEpochSeconds = { 1000L })
        vm.start(route(steps))
        vm.end()
        assertTrue(vm.state.value is NavUiState.Inactive)
    }
}
