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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class CountingFakeRepo(private val result: PlacesResult) : PlacesRepository {
    var calls = 0
    override suspend fun searchPlaces(query: String, origin: GeoPoint, polyline: String?): PlacesResult {
        calls++
        return result
    }
}

private fun place(id: String) = Place(id, id, GeoPoint(1.0, 1.0), null, null)

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val origin = GeoPoint(1.0, 1.0)

    @Test
    fun `submit populates results on success`() = runTest {
        val vm = SearchViewModel(CountingFakeRepo(PlacesResult.Success(listOf(place("a"), place("b")), "nearby")))
        vm.onQueryChange("pizza")
        vm.submit(origin)
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals(2, s.results.size)
        assertFalse(s.loading)
        assertTrue(s.searched)
        assertNull(s.error)
    }

    @Test
    fun `submit sets error on failure`() = runTest {
        val vm = SearchViewModel(CountingFakeRepo(PlacesResult.Failure("boom")))
        vm.onQueryChange("pizza")
        vm.submit(origin)
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("boom", s.error)
        assertTrue(s.results.isEmpty())
        assertFalse(s.loading)
    }

    @Test
    fun `blank query does not call the repository`() = runTest {
        val repo = CountingFakeRepo(PlacesResult.Success(listOf(place("a")), "nearby"))
        val vm = SearchViewModel(repo)
        vm.onQueryChange("   ")
        vm.submit(origin)
        advanceUntilIdle()

        assertEquals(0, repo.calls)
        assertFalse(vm.state.value.searched)
    }

    @Test
    fun `clear resets state`() = runTest {
        val vm = SearchViewModel(CountingFakeRepo(PlacesResult.Success(listOf(place("a")), "nearby")))
        vm.onQueryChange("pizza")
        vm.submit(origin)
        advanceUntilIdle()
        vm.clear()

        val s = vm.state.value
        assertEquals("", s.query)
        assertTrue(s.results.isEmpty())
        assertFalse(s.searched)
    }

    @Test
    fun `clear during an in-flight search discards the stale result`() = runTest {
        val vm = SearchViewModel(CountingFakeRepo(PlacesResult.Success(listOf(place("a")), "nearby")))
        vm.onQueryChange("pizza")
        vm.submit(origin)   // launches; not yet run on the test dispatcher
        vm.clear()          // cancels the in-flight job
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("", s.query)
        assertTrue(s.results.isEmpty())
        assertFalse(s.searched)
    }

    @Test
    fun `price symbols map correctly`() {
        assertEquals("$", priceSymbol("PRICE_LEVEL_INEXPENSIVE"))
        assertEquals("$$", priceSymbol("PRICE_LEVEL_MODERATE"))
        assertEquals("$$$", priceSymbol("PRICE_LEVEL_EXPENSIVE"))
        assertEquals("$$$$", priceSymbol("PRICE_LEVEL_VERY_EXPENSIVE"))
        assertEquals("", priceSymbol("PRICE_LEVEL_FREE"))
        assertEquals("", priceSymbol(null))
    }
}
