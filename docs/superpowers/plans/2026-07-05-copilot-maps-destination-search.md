# Destination Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user type a place name, see a results list, and tap one to set it as the destination and plan a route — reusing Google `searchText` (already behind `/places/search`).

**Architecture:** A tiny backend addition enriches each place with `price_level` + `open_now`. A new `SearchViewModel` (separate from the gas/food `PlacesViewModel`) runs `PlacesRepository.searchPlaces(query, origin, polyline=null)`. `MapScreen` gains a top search bar + results panel; tapping a result sets the destination, plans the route via the existing flow, clears search, and animates the camera to fit origin+destination.

**Tech Stack:** Python/FastAPI + pytest (backend); Kotlin, Jetpack Compose, AndroidX Lifecycle ViewModel, kotlinx-coroutines, Moshi/Retrofit, maps-compose; JUnit + kotlinx-coroutines-test.

## Global Constraints

- Reuse the existing `/places/search` (Google `searchText`) — **no new endpoint**; destination search sends `query` + `origin` and **no polyline**.
- `SearchViewModel` is separate from `PlacesViewModel`; search UI is **hidden while navigation is active**.
- Price enum → symbol: `PRICE_LEVEL_INEXPENSIVE→"$"`, `PRICE_LEVEL_MODERATE→"$$"`, `PRICE_LEVEL_EXPENSIVE→"$$$"`, `PRICE_LEVEL_VERY_EXPENSIVE→"$$$$"`; anything else / null → `""`.
- Result row: name · ★rating · price · Open now/Closed · distance · address; list order = Google relevance (as returned).
- Android package `com.virtueson.copilotmaps`; minSdk 24. Gradle from CLI needs `JAVA_HOME` set. Backend uses the repo `.venv`.
- Do NOT commit `local.properties`, `backend/.env`.

**Backend test command:**
```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"
.\.venv\Scripts\python.exe -m pytest tests/test_places.py tests/test_stub_places.py -q
```
**Android test command:**
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat testDebugUnitTest --console=plain
```

---

## File Structure

- **Modify** `backend/app/models.py` — `Place` gains `price_level`, `open_now`.
- **Modify** `backend/app/places/google.py` — field mask + `_to_place` mapping.
- **Modify** `backend/app/places/stub.py` — emit sample values.
- **Modify** `backend/tests/test_places.py`, `backend/tests/test_stub_places.py` — coverage.
- **Create** `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/SearchViewModel.kt` — `SearchUiState`, `SearchViewModel`(+factory), `priceSymbol`.
- **Create** `android/app/src/test/java/com/virtueson/copilotmaps/ui/map/SearchViewModelTest.kt`.
- **Modify** `android/app/src/main/java/com/virtueson/copilotmaps/data/Place.kt` — new fields.
- **Modify** `android/app/src/main/java/com/virtueson/copilotmaps/network/PlacesDtos.kt` — DTO fields.
- **Modify** `android/app/src/main/java/com/virtueson/copilotmaps/data/PlacesRepository.kt` — map new fields.
- **Modify** `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt` — search bar + results panel + wiring.

---

## Task 1: Backend — `price_level` + `open_now` on places

**Files:**
- Modify: `backend/app/models.py`, `backend/app/places/google.py`, `backend/app/places/stub.py`
- Test: `backend/tests/test_places.py`, `backend/tests/test_stub_places.py`

**Interfaces:**
- Produces: `Place` model with `price_level: str | None`, `open_now: bool | None`; `/places/search` returns them; `_to_place(raw: dict) -> Place` maps `priceLevel` and `currentOpeningHours.openNow`.

- [ ] **Step 1: Write the failing tests**

Add to `backend/tests/test_places.py` (end of file):

```python
def test_place_carries_price_and_open_now():
    p = Place(id="x", name="X", lat=1.0, lng=2.0,
              price_level="PRICE_LEVEL_MODERATE", open_now=True)
    client = _client(along=[], near=[p])
    resp = client.post("/places/search",
                       json={"query": "q", "origin": {"lat": 1.0, "lng": 2.0}})
    place = resp.json()["places"][0]
    assert place["price_level"] == "PRICE_LEVEL_MODERATE"
    assert place["open_now"] is True


def test_google_to_place_maps_price_and_open_now():
    from app.places.google import _to_place
    raw = {
        "id": "g1", "displayName": {"text": "Cafe"},
        "location": {"latitude": 1.0, "longitude": 2.0},
        "formattedAddress": "1 St", "rating": 4.5,
        "priceLevel": "PRICE_LEVEL_INEXPENSIVE",
        "currentOpeningHours": {"openNow": False},
    }
    p = _to_place(raw)
    assert p.price_level == "PRICE_LEVEL_INEXPENSIVE"
    assert p.open_now is False


def test_google_to_place_tolerates_missing_price_and_hours():
    from app.places.google import _to_place
    p = _to_place({"id": "g2", "displayName": {"text": "X"},
                   "location": {"latitude": 0.0, "longitude": 0.0}})
    assert p.price_level is None
    assert p.open_now is None
```

Add to `backend/tests/test_stub_places.py` (end of file):

```python
import asyncio

from app.models import LatLng
from app.places.stub import StubPlacesProvider


def test_stub_places_include_price_and_open_now():
    provider = StubPlacesProvider()
    places = asyncio.run(provider.nearby("coffee", LatLng(lat=1.0, lng=2.0)))
    assert places, "stub should return places"
    assert places[0].price_level is not None
    assert places[0].open_now in (True, False)
```

- [ ] **Step 2: Run to verify they FAIL**

Run the backend test command.
Expected: FAIL — `Place` has no `price_level`/`open_now`; `_to_place` doesn't set them; stub doesn't emit them.

- [ ] **Step 3: Implement**

In `backend/app/models.py`, extend `Place`:
```python
class Place(BaseModel):
    id: str
    name: str
    lat: float
    lng: float
    address: str | None = None
    rating: float | None = None
    price_level: str | None = None
    open_now: bool | None = None
```

In `backend/app/places/google.py`, extend the field mask and `_to_place`:
```python
_FIELD_MASK = (
    "places.id,places.displayName,places.location,"
    "places.formattedAddress,places.rating,"
    "places.priceLevel,places.currentOpeningHours.openNow"
)


def _to_place(p: dict) -> Place:
    location = p.get("location", {})
    return Place(
        id=p.get("id", ""),
        name=p.get("displayName", {}).get("text", "Unknown"),
        lat=float(location.get("latitude", 0.0)),
        lng=float(location.get("longitude", 0.0)),
        address=p.get("formattedAddress"),
        rating=p.get("rating"),
        price_level=p.get("priceLevel"),
        open_now=p.get("currentOpeningHours", {}).get("openNow"),
    )
```

In `backend/app/places/stub.py`, emit sample values in `_fake`:
```python
def _fake(query: str, lat: float, lng: float) -> list[Place]:
    price_levels = ["PRICE_LEVEL_INEXPENSIVE", "PRICE_LEVEL_MODERATE", "PRICE_LEVEL_EXPENSIVE"]
    places: list[Place] = []
    for i, (d_lat, d_lng) in enumerate(_OFFSETS):
        places.append(
            Place(
                id=f"stub-{query}-{i}".replace(" ", "-"),
                name=f"Stub {query} {i + 1}",
                lat=lat + d_lat,
                lng=lng + d_lng,
                address=f"{i + 1} Stub Street",
                rating=4.0 + i * 0.2,
                price_level=price_levels[i % len(price_levels)],
                open_now=(i % 2 == 0),
            )
        )
    return places
```

- [ ] **Step 4: Run to verify they PASS**

Run the backend test command. Expected: PASS (the new tests + all existing places tests).

- [ ] **Step 5: Commit**

```bash
git add backend/app/models.py backend/app/places/google.py backend/app/places/stub.py backend/tests/test_places.py backend/tests/test_stub_places.py
git commit -m "feat(backend): add price_level + open_now to places (destination search)"
```

---

## Task 2: App — `SearchViewModel` + `priceSymbol`

**Files:**
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/SearchViewModel.kt`
- Test: `android/app/src/test/java/com/virtueson/copilotmaps/ui/map/SearchViewModelTest.kt`

**Interfaces:**
- Consumes: existing `PlacesRepository.searchPlaces(query, origin, polyline)`, `PlacesResult`, `Place`, `GeoPoint`.
- Produces:
  - `data class SearchUiState(query, results, loading, error, searched)`.
  - `class SearchViewModel(repository)` with `state: StateFlow<SearchUiState>`, `onQueryChange(text)`, `submit(origin: GeoPoint)`, `clear()`; + `SearchViewModelFactory(repository)`.
  - `fun priceSymbol(priceLevel: String?): String`.

- [ ] **Step 1: Write the failing tests**

Create `android/app/src/test/java/com/virtueson/copilotmaps/ui/map/SearchViewModelTest.kt`:

```kotlin
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
    fun `price symbols map correctly`() {
        assertEquals("$", priceSymbol("PRICE_LEVEL_INEXPENSIVE"))
        assertEquals("$$", priceSymbol("PRICE_LEVEL_MODERATE"))
        assertEquals("$$$", priceSymbol("PRICE_LEVEL_EXPENSIVE"))
        assertEquals("$$$$", priceSymbol("PRICE_LEVEL_VERY_EXPENSIVE"))
        assertEquals("", priceSymbol("PRICE_LEVEL_FREE"))
        assertEquals("", priceSymbol(null))
    }
}
```

- [ ] **Step 2: Run to verify they FAIL**

Run the Android test command with `--tests "com.virtueson.copilotmaps.ui.map.SearchViewModelTest"`.
Expected: FAIL — `SearchViewModel` / `priceSymbol` unresolved.

- [ ] **Step 3: Implement**

Create `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/SearchViewModel.kt`:

```kotlin
package com.virtueson.copilotmaps.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.virtueson.copilotmaps.data.GeoPoint
import com.virtueson.copilotmaps.data.Place
import com.virtueson.copilotmaps.data.PlacesRepository
import com.virtueson.copilotmaps.data.PlacesResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val results: List<Place> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val searched: Boolean = false,
)

class SearchViewModel(
    private val repository: PlacesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    fun onQueryChange(text: String) {
        _state.value = _state.value.copy(query = text)
    }

    fun submit(origin: GeoPoint) {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            _state.value = when (val r = repository.searchPlaces(q, origin, null)) {
                is PlacesResult.Success ->
                    _state.value.copy(results = r.places, loading = false, searched = true, error = null)
                is PlacesResult.Failure ->
                    _state.value.copy(results = emptyList(), loading = false, searched = true, error = r.reason)
            }
        }
    }

    fun clear() {
        _state.value = SearchUiState()
    }
}

/** Google price-level enum → "$".."$$$$" ("" when free/unknown/null). */
fun priceSymbol(priceLevel: String?): String = when (priceLevel) {
    "PRICE_LEVEL_INEXPENSIVE" -> "$"
    "PRICE_LEVEL_MODERATE" -> "$$"
    "PRICE_LEVEL_EXPENSIVE" -> "$$$"
    "PRICE_LEVEL_VERY_EXPENSIVE" -> "$$$$"
    else -> ""
}

class SearchViewModelFactory(
    private val repository: PlacesRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SearchViewModel(repository) as T
}
```

- [ ] **Step 4: Run to verify they PASS**

Run the Android test command with `--tests "com.virtueson.copilotmaps.ui.map.SearchViewModelTest"`.
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add "android/app/src/main/java/com/virtueson/copilotmaps/ui/map/SearchViewModel.kt" "android/app/src/test/java/com/virtueson/copilotmaps/ui/map/SearchViewModelTest.kt"
git commit -m "feat(app): SearchViewModel + priceSymbol for destination search"
```

---

## Task 3: App — new Place fields + MapScreen search UI

**Files:**
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/data/Place.kt`
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/network/PlacesDtos.kt`
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/data/PlacesRepository.kt`
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt`

**Interfaces:**
- Consumes: `SearchViewModel`, `SearchUiState`, `priceSymbol`, `SearchViewModelFactory` (Task 2); `Place.priceLevel/openNow`; existing `haversineMeters`, `formatDistance` (private in MapScreen), `RouteViewModel.planRoutes`, `NetworkModule.placesApi`, `DefaultPlacesRepository`.
- Produces: no new public interface — UI wiring. Verified on device.

> This task has no unit test for the Compose UI. It is gated by compile + existing tests + the manual device check.

- [ ] **Step 1: Add the new fields to `Place`, DTO, and repository mapping**

In `data/Place.kt`:
```kotlin
data class Place(
    val id: String,
    val name: String,
    val location: GeoPoint,
    val address: String?,
    val rating: Double?,
    val priceLevel: String? = null,
    val openNow: Boolean? = null,
)
```

In `network/PlacesDtos.kt` (add the moshi import at the top and the two fields):
```kotlin
import com.squareup.moshi.Json

data class PlaceDto(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String? = null,
    val rating: Double? = null,
    @Json(name = "price_level") val priceLevel: String? = null,
    @Json(name = "open_now") val openNow: Boolean? = null,
)
```

In `data/PlacesRepository.kt`, add the two fields to the `Place(...)` mapping:
```kotlin
                Place(
                    id = dto.id,
                    name = dto.name,
                    location = GeoPoint(dto.lat, dto.lng),
                    address = dto.address,
                    rating = dto.rating,
                    priceLevel = dto.priceLevel,
                    openNow = dto.openNow,
                )
```

- [ ] **Step 2: Create the SearchViewModel at MapScreen top level and thread it into `RoutingMap`**

In `MapScreen.kt`, near the other top-level view-model declarations (where `placesViewModel` is created, ~line 97-99), add:
```kotlin
    val searchViewModel: SearchViewModel = viewModel(
        factory = SearchViewModelFactory(DefaultPlacesRepository(NetworkModule.placesApi))
    )
    val searchState by searchViewModel.state.collectAsStateWithLifecycle()
```

In the `RoutingMap(...)` call inside the `MapUiState.Located` branch (where `onPlan`, `onStartNav`, etc. are passed), add:
```kotlin
                searchState = searchState,
                onSearchQueryChange = searchViewModel::onQueryChange,
                onSearchSubmit = { o -> searchViewModel.submit(o) },
                onClearSearch = searchViewModel::clear,
```

Add the matching parameters to the `RoutingMap` composable signature (after `onEndNav` / the last existing param):
```kotlin
    searchState: SearchUiState,
    onSearchQueryChange: (String) -> Unit,
    onSearchSubmit: (GeoPoint) -> Unit,
    onClearSearch: () -> Unit,
```

- [ ] **Step 3: Render the search bar + results panel in `RoutingMap`**

Inside `RoutingMap`, add a coroutine scope near the top of the composable body (next to the other `remember`s / state):
```kotlin
    val searchScope = rememberCoroutineScope()
```

Inside the root `Box(...)` of `RoutingMap` (the same `Box` that holds the map and the FABs), add this block — place it so it renders on top of the map (after the `GoogleMap {}` and existing overlays). `navActive` (a `val navActive = navState is NavUiState.Active`) already exists in this scope:
```kotlin
        if (!navActive) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .fillMaxWidth()
                    .padding(12.dp),
            ) {
                OutlinedTextField(
                    value = searchState.query,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search a place") },
                    leadingIcon = { Text("🔍") },
                    trailingIcon = {
                        if (searchState.query.isNotEmpty()) {
                            TextButton(onClick = onClearSearch) { Text("✕") }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearchSubmit(origin) }),
                )

                if (searchState.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }

                when {
                    searchState.error != null -> Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(searchState.error)
                            TextButton(onClick = { onSearchSubmit(origin) }) { Text("Retry") }
                        }
                    }
                    searchState.searched && searchState.results.isEmpty() && !searchState.loading ->
                        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text("No places found", Modifier.padding(12.dp))
                        }
                    searchState.results.isNotEmpty() -> Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            items(searchState.results, key = { it.id }) { place ->
                                SearchResultRow(
                                    place = place,
                                    distanceMeters = haversineMeters(origin, place.location).toInt(),
                                    onClick = {
                                        following = false
                                        val dest = LatLng(place.location.lat, place.location.lng)
                                        destination = dest
                                        onPlan(place.location)
                                        onClearSearch()
                                        searchScope.launch {
                                            val bounds = LatLngBounds.builder()
                                                .include(LatLng(origin.lat, origin.lng))
                                                .include(dest)
                                                .build()
                                            try {
                                                cameraPositionState.animate(
                                                    CameraUpdateFactory.newLatLngBounds(bounds, 120), 1000,
                                                )
                                            } catch (e: Exception) {
                                                // map not ready; ignore
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
```

Add the `SearchResultRow` composable near the other private composables (e.g. after `NavBottomBar`):
```kotlin
@Composable
private fun SearchResultRow(place: Place, distanceMeters: Int, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(place.name, fontWeight = FontWeight.Bold)
        val bits = buildList {
            place.rating?.let { add("★$it") }
            priceSymbol(place.priceLevel).takeIf { it.isNotEmpty() }?.let { add(it) }
            place.openNow?.let { add(if (it) "Open now" else "Closed") }
            add(formatDistance(distanceMeters))
        }
        Text(bits.joinToString(" · "))
        place.address?.let { Text(it, fontSize = 12.sp) }
    }
}
```

Ensure these imports exist in `MapScreen.kt` (add any missing — several are already present from prior steps):
```kotlin
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.LatLngBounds
import com.virtueson.copilotmaps.data.Place
import com.virtueson.copilotmaps.data.haversineMeters
import kotlinx.coroutines.launch
```

- [ ] **Step 4: Compile + run tests**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat compileDebugKotlin --console=plain
.\gradlew.bat testDebugUnitTest --console=plain
```
Expected: both BUILD SUCCESSFUL. Fix any unresolved reference (usually a missing import above) or duplicate import (some may already be present — do not add a second copy).

- [ ] **Step 5: Device verification (manual)**

Build/run on the Pixel-class device (`de92c98f`) with the backend up and `adb reverse tcp:8000 tcp:8000`. Then:
1. Type a place in the search bar (e.g. "starbucks tangerang") and press the keyboard Search action.
2. Confirm a results list appears with rows like **name · ★rating · $$ · Open now · distance · address**.
3. Tap a result → confirm the destination marker drops, routes are planned (RouteCards + **Start** appear), the search list clears, and the **camera zooms to show both you and the destination**.
4. Confirm the search bar is **gone while navigating** (after tapping Start).
5. Try a nonsense query → "No places found"; stop the backend and search → error card + Retry.

- [ ] **Step 6: Commit**

```bash
git add "android/app/src/main/java/com/virtueson/copilotmaps/data/Place.kt" "android/app/src/main/java/com/virtueson/copilotmaps/network/PlacesDtos.kt" "android/app/src/main/java/com/virtueson/copilotmaps/data/PlacesRepository.kt" "android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt"
git commit -m "feat(app): destination search bar + results + camera fit (destination search)"
```

---

## Self-Review

**Spec coverage:**
- Reuse `searchText` via `/places/search`, no polyline → Task 2 `submit` passes `null`; no new endpoint.
- `price_level` + `open_now` on results → Task 1 (model/mask/stub) + Task 3 (Place/DTO/mapping + row render).
- Result row name·rating·price·open·distance·address; relevance order → Task 3 `SearchResultRow` (renders in returned order).
- Type+submit→list (no autocomplete) → Task 2 `submit` + Task 3 search bar IME Search action.
- Tap → destination + planRoutes + clear + camera fit → Task 3 row `onClick`.
- Separate `SearchViewModel`; hidden during nav → Task 2 (own VM) + Task 3 (`if (!navActive)`).
- priceSymbol mapping (exact enums) → Task 2 `priceSymbol` + test.
- Errors: blank no-op / no results / failure+retry → Task 2 (`submit` blank guard, `searched`) + Task 3 (`when` branches).
- Testing: backend passthrough + google mapping + stub; SearchViewModel + priceSymbol → Tasks 1 & 2.

**Placeholder scan:** none — all code steps contain full code; the one manual step (device test) is manual by design.

**Type consistency:** `SearchUiState(query, results, loading, error, searched)` and `SearchViewModel.onQueryChange/submit(GeoPoint)/clear` identical across Tasks 2 and 3. `priceSymbol(String?): String` identical across Tasks 2 and 3. `Place.priceLevel/openNow: String?/Boolean?` added in Task 3 Step 1, consumed by `SearchResultRow`. `PlaceDto` `@Json(name="price_level"/"open_now")` matches the backend snake_case fields from Task 1. `haversineMeters(GeoPoint, GeoPoint): Double` and `formatDistance(Int): String` match existing MapScreen usage.
