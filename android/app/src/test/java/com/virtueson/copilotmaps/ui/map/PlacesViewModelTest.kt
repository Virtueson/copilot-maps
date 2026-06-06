package com.virtueson.copilotmaps.ui.map

import com.virtueson.copilotmaps.MainDispatcherRule
import com.virtueson.copilotmaps.data.GeoPoint
import com.virtueson.copilotmaps.data.Place
import com.virtueson.copilotmaps.data.PlacesRepository
import com.virtueson.copilotmaps.data.PlacesResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakePlacesRepository(private val result: PlacesResult) : PlacesRepository {
    override suspend fun searchPlaces(query: String, origin: GeoPoint, polyline: String?): PlacesResult = result
}

private fun place(id: String) = Place(id, id, GeoPoint(1.0, 1.0), null, null)

@OptIn(ExperimentalCoroutinesApi::class)
class PlacesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val origin = GeoPoint(1.0, 1.0)

    @Test
    fun `category query strings are correct`() {
        assertEquals("gas station", PlaceCategory.GAS.query)
        assertEquals("restaurant", PlaceCategory.FOOD.query)
    }

    @Test
    fun `polyline sent but nearby mode sets fellBackToNearby true`() = runTest {
        val vm = PlacesViewModel(FakePlacesRepository(PlacesResult.Success(listOf(place("p")), "nearby")))

        vm.search(PlaceCategory.GAS, origin, routePolyline = "abc")
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is PlacesState.Loaded)
        assertTrue((state as PlacesState.Loaded).fellBackToNearby)
    }

    @Test
    fun `along_route mode does not set fellBackToNearby`() = runTest {
        val vm = PlacesViewModel(FakePlacesRepository(PlacesResult.Success(listOf(place("p")), "along_route")))

        vm.search(PlaceCategory.GAS, origin, routePolyline = "abc")
        advanceUntilIdle()

        assertFalse((vm.state.value as PlacesState.Loaded).fellBackToNearby)
    }

    @Test
    fun `no polyline never sets fellBackToNearby`() = runTest {
        val vm = PlacesViewModel(FakePlacesRepository(PlacesResult.Success(listOf(place("p")), "nearby")))

        vm.search(PlaceCategory.FOOD, origin, routePolyline = null)
        advanceUntilIdle()

        assertFalse((vm.state.value as PlacesState.Loaded).fellBackToNearby)
    }

    @Test
    fun `failure emits Error`() = runTest {
        val vm = PlacesViewModel(FakePlacesRepository(PlacesResult.Failure("boom")))

        vm.search(PlaceCategory.GAS, origin, routePolyline = null)
        advanceUntilIdle()

        assertTrue(vm.state.value is PlacesState.Error)
    }

    @Test
    fun `clear resets to Idle`() = runTest {
        val vm = PlacesViewModel(FakePlacesRepository(PlacesResult.Success(listOf(place("p")), "nearby")))
        vm.search(PlaceCategory.GAS, origin, routePolyline = null)
        advanceUntilIdle()

        vm.clear()

        assertTrue(vm.state.value is PlacesState.Idle)
    }
}
