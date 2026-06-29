package com.virtueson.copilotmaps.ui.map

import com.virtueson.copilotmaps.MainDispatcherRule
import com.virtueson.copilotmaps.location.LocationProvider
import com.virtueson.copilotmaps.location.LocationResult
import com.virtueson.copilotmaps.location.LocationSample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeLocationProvider(
    private val result: LocationResult,
    private val updates: Flow<LocationSample> = emptyFlow(),
) : LocationProvider {
    override suspend fun getCurrentLocation(): LocationResult = result
    override fun locationUpdates(): Flow<LocationSample> = updates
}

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `granted with success emits Located with coordinates`() = runTest {
        val vm = MapViewModel(FakeLocationProvider(LocationResult.Success(1.5, 2.5)))

        vm.onPermissionGranted()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is MapUiState.Located)
        state as MapUiState.Located
        assertEquals(1.5, state.latitude, 0.0)
        assertEquals(2.5, state.longitude, 0.0)
    }

    @Test
    fun `granted with failure emits Error`() = runTest {
        val vm = MapViewModel(FakeLocationProvider(LocationResult.Failure("no fix")))

        vm.onPermissionGranted()
        advanceUntilIdle()

        assertTrue(vm.uiState.value is MapUiState.Error)
    }

    @Test
    fun `denied emits PermissionNeeded`() {
        val vm = MapViewModel(FakeLocationProvider(LocationResult.Success(1.0, 1.0)))

        vm.onPermissionDenied()

        assertTrue(vm.uiState.value is MapUiState.PermissionNeeded)
    }

    @Test
    fun `a stream fix recovers Located even when the one-shot fails`() = runTest {
        val s = LocationSample(3.0, 4.0, null, null)
        val vm = MapViewModel(
            FakeLocationProvider(LocationResult.Failure("no fix"), flowOf(s))
        )

        vm.onPermissionGranted()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is MapUiState.Located)
        state as MapUiState.Located
        assertEquals(3.0, state.latitude, 0.0)
        assertEquals(4.0, state.longitude, 0.0)
    }

    @Test
    fun `location stream updates the location state`() = runTest {
        val s1 = LocationSample(1.0, 2.0, 90f, 5f)
        val s2 = LocationSample(1.1, 2.1, 80f, 6f)
        val vm = MapViewModel(
            FakeLocationProvider(LocationResult.Success(1.0, 2.0), flowOf(s1, s2))
        )

        assertNull(vm.location.value)
        vm.onPermissionGranted()
        advanceUntilIdle()

        assertEquals(s2, vm.location.value)
    }
}
