package com.virtueson.copilotmaps.ui.map

import com.virtueson.copilotmaps.MainDispatcherRule
import com.virtueson.copilotmaps.data.GeoPoint
import com.virtueson.copilotmaps.data.Route
import com.virtueson.copilotmaps.data.RoutesRepository
import com.virtueson.copilotmaps.data.RoutesResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeRoutesRepository(private val result: RoutesResult) : RoutesRepository {
    override suspend fun planRoutes(origin: GeoPoint, destination: GeoPoint, languageTag: String?): RoutesResult = result
}

private fun sampleRoute(id: String) = Route(
    id = id,
    summary = "Test",
    distanceMeters = 1000,
    durationSeconds = 120,
    points = listOf(GeoPoint(0.0, 0.0), GeoPoint(1.0, 1.0)),
)

@OptIn(ExperimentalCoroutinesApi::class)
class RouteViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val origin = GeoPoint(1.0, 1.0)
    private val dest = GeoPoint(2.0, 2.0)

    @Test
    fun `success emits Loaded with first route selected`() = runTest {
        val routes = listOf(sampleRoute("route-0"), sampleRoute("route-1"))
        val vm = RouteViewModel(FakeRoutesRepository(RoutesResult.Success(routes)))

        vm.planRoutes(origin, dest)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is RoutesState.Loaded)
        state as RoutesState.Loaded
        assertEquals(2, state.routes.size)
        assertEquals("route-0", state.selectedId)
    }

    @Test
    fun `failure emits Error`() = runTest {
        val vm = RouteViewModel(FakeRoutesRepository(RoutesResult.Failure("boom")))

        vm.planRoutes(origin, dest)
        advanceUntilIdle()

        assertTrue(vm.state.value is RoutesState.Error)
    }

    @Test
    fun `selectRoute updates selectedId when loaded`() = runTest {
        val routes = listOf(sampleRoute("route-0"), sampleRoute("route-1"))
        val vm = RouteViewModel(FakeRoutesRepository(RoutesResult.Success(routes)))
        vm.planRoutes(origin, dest)
        advanceUntilIdle()

        vm.selectRoute("route-1")

        assertEquals("route-1", (vm.state.value as RoutesState.Loaded).selectedId)
    }
}
