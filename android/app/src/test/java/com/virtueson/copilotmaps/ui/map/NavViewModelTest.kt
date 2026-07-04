package com.virtueson.copilotmaps.ui.map

import com.virtueson.copilotmaps.data.GeoPoint
import com.virtueson.copilotmaps.data.Route
import com.virtueson.copilotmaps.data.RouteStep
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

// A route whose polyline runs east along the equator, matching `steps` (lng 0.000..0.020).
private val linePts = listOf(GeoPoint(0.0, 0.000), GeoPoint(0.0, 0.020))
private fun routeP(steps: List<RouteStep>, points: List<GeoPoint>) = Route(
    id = "r0", summary = "Test", distanceMeters = 600, durationSeconds = 600,
    points = points, polyline = "", trafficIntervals = emptyList(), steps = steps,
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `start emits the departure announcement`() = runTest {
        val vm = NavViewModel(nowEpochSeconds = { 1000L })
        val lines = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.announcements.collect { lines.add(it) }
        }

        vm.start(route(steps))

        assertEquals("Head", lines.first())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `onLocation emits a now cue when reaching the turn`() = runTest {
        val vm = NavViewModel(nowEpochSeconds = { 1000L })
        val lines = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.announcements.collect { lines.add(it) }
        }

        vm.start(route(steps))                    // "Head"; heading to step 1
        vm.onLocation(GeoPoint(0.0, 0.0000001))   // ~at step 0 -> advance to step 1, far from it (no cue)
        vm.onLocation(GeoPoint(0.0, 0.0096900))   // ~34 m before step 1 (30 m < d < 40 m) -> now cue

        assertEquals("Turn left", lines.last())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `sustained off-route emits one reroute request and Rerouting`() = runTest {
        val vm = NavViewModel(nowEpochSeconds = { 1000L })
        val lines = mutableListOf<String>()
        val reqs = mutableListOf<GeoPoint>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.announcements.collect { lines.add(it) } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.rerouteRequest.collect { reqs.add(it) } }

        vm.start(routeP(steps, linePts))
        val off = GeoPoint(0.0018, 0.005) // ~200 m north of the line
        vm.onLocation(off); vm.onLocation(off); vm.onLocation(off)

        assertEquals(1, reqs.size)
        assertEquals(GeoPoint(0.0, 0.020), reqs.first()) // destination = last step location
        assertTrue(lines.contains("Rerouting"))
        assertTrue((vm.state.value as NavUiState.Active).rerouting)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a single off-route fix does not trigger a reroute`() = runTest {
        val vm = NavViewModel(nowEpochSeconds = { 1000L })
        val reqs = mutableListOf<GeoPoint>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.rerouteRequest.collect { reqs.add(it) } }

        vm.start(routeP(steps, linePts))
        vm.onLocation(GeoPoint(0.0018, 0.005)) // off (1)
        vm.onLocation(GeoPoint(0.0, 0.005))    // back on route → counter resets
        vm.onLocation(GeoPoint(0.0018, 0.005)) // off (1 again)

        assertTrue(reqs.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `onRerouteResult adopts the new route without a departure cue`() = runTest {
        val vm = NavViewModel(nowEpochSeconds = { 1000L })
        val lines = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.announcements.collect { lines.add(it) } }

        vm.start(routeP(steps, linePts)) // emits "Head"
        lines.clear()
        val newSteps = listOf(
            step("Continue onto Elm", "DEPART", 100, 0.0, 0.000),
            step("Turn right", "TURN_RIGHT", 100, 0.0, 0.010),
            step("Arrive", "ARRIVE", 0, 0.0, 0.020),
        )
        vm.onRerouteResult(routeP(newSteps, linePts))

        assertFalse(lines.contains("Continue onto Elm")) // no departure cue on reroute
        val s = vm.state.value as NavUiState.Active
        assertEquals("Continue onto Elm", s.instruction)  // HUD shows the new route
        assertFalse(s.rerouting)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `onRerouteResult null announces failure, keeps navigating, and backs off`() = runTest {
        val vm = NavViewModel(nowEpochSeconds = { 1000L })
        val lines = mutableListOf<String>()
        val reqs = mutableListOf<GeoPoint>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.announcements.collect { lines.add(it) } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.rerouteRequest.collect { reqs.add(it) } }

        vm.start(routeP(steps, linePts))
        val off = GeoPoint(0.0018, 0.005)
        vm.onLocation(off); vm.onLocation(off); vm.onLocation(off) // first reroute attempt
        assertEquals(1, reqs.size)

        vm.onRerouteResult(null)
        assertTrue(lines.contains("Rerouting failed"))
        assertFalse((vm.state.value as NavUiState.Active).rerouting)

        // still off-route, but within the 10 s backoff (nowEpochSeconds fixed at 1000) → no new request
        vm.onLocation(off); vm.onLocation(off); vm.onLocation(off)
        assertEquals(1, reqs.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `onRerouteResult is a no-op when navigation is inactive`() = runTest {
        val vm = NavViewModel(nowEpochSeconds = { 1000L })
        val lines = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.announcements.collect { lines.add(it) } }

        vm.start(routeP(steps, linePts))
        vm.end()                                    // navigation cancelled → Inactive
        lines.clear()
        vm.onRerouteResult(routeP(steps, linePts))  // a stale reroute result arrives late

        assertTrue(vm.state.value is NavUiState.Inactive) // stays cancelled, not resurrected
        assertTrue(lines.isEmpty())                       // no announcement
    }
}
