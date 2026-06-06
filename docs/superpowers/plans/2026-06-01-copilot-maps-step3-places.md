# Copilot Maps — Step 3 (Places) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tap a Gas/Food button to drop markers for places along the selected route, falling back to nearest-around-you when there's no route or no along-route results — served by a `POST /places/search` endpoint (stub first, then Google Places API New).

**Architecture:** A `PlacesProvider` protocol (stub + Google) behind one endpoint whose handler does the along-route→nearby fallback. The app adds a separate `PlacesViewModel`/`PlacesState`, calls the endpoint via Retrofit, and renders orange place markers on the existing map.

**Tech Stack:** Existing Step 2 stack. Google Places API (New) `places:searchText`. No new app dependencies.

---

## Conventions (unchanged)

- Backend at `backend/`: `.\.venv\Scripts\python.exe -m pytest` / `... -m uvicorn app.main:app --port 8000`.
- Gradle: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; cd android; .\gradlew.bat <task>`.
- Phone reaches backend via `adb reverse tcp:8000 tcp:8000`; base URL `http://localhost:8000/`.

## File Structure

```
backend/app/
  models.py              # + Place, PlacesSearchRequest, PlacesSearchResponse
  places/
    __init__.py          # NEW (empty)
    base.py              # NEW: PlacesProvider protocol
    stub.py              # NEW: StubPlacesProvider
    google.py            # NEW: GooglePlacesProvider
  config.py              # + places_provider, google_places_api_key, get_places_provider()
  routers/places.py      # NEW: POST /places/search
  main.py                # register places router
backend/tests/
  test_stub_places.py    # NEW
  test_places.py         # NEW

android/app/src/main/java/com/virtueson/copilotmaps/
  network/
    PlacesDtos.kt        # NEW
    PlacesApi.kt         # NEW
    NetworkModule.kt     # MODIFIED: shared Retrofit + placesApi
  data/
    Place.kt             # NEW: Place + PlacesResult
    PlacesRepository.kt  # NEW
    Route.kt             # MODIFIED: + polyline: String
    RoutesRepository.kt  # MODIFIED: map polyline
  ui/map/
    PlacesState.kt       # NEW
    PlacesViewModel.kt   # NEW: + PlaceCategory + factory
    MapScreen.kt         # MODIFIED: buttons, markers, note
android/app/src/test/java/com/virtueson/copilotmaps/ui/map/
  PlacesViewModelTest.kt # NEW
```

---

# PHASE 1 — Backend

### Task 1: Contract + places package skeleton

**Files:**
- Modify: `backend/app/models.py`
- Create: `backend/app/places/__init__.py`

- [ ] **Step 1: Append the places models to `models.py`**

Append to `backend/app/models.py`:
```python
class Place(BaseModel):
    id: str
    name: str
    lat: float
    lng: float
    address: str | None = None
    rating: float | None = None


class PlacesSearchRequest(BaseModel):
    query: str
    origin: LatLng
    polyline: str | None = None


class PlacesSearchResponse(BaseModel):
    mode: str
    places: list[Place]
```

- [ ] **Step 2: Create the empty package marker**

Create `backend/app/places/__init__.py` (empty).

- [ ] **Step 3: Verify import**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"
.\.venv\Scripts\python.exe -c "from app.models import Place, PlacesSearchResponse; print('ok')"
```
Expected: `ok`.

- [ ] **Step 4: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add backend/app/models.py backend/app/places/__init__.py
git commit -m "feat(backend): add places contract models"
```

---

### Task 2: `PlacesProvider` protocol + `StubPlacesProvider` (TDD)

**Files:**
- Create: `backend/app/places/base.py`, `backend/app/places/stub.py`
- Test: `backend/tests/test_stub_places.py`

- [ ] **Step 1: Write the failing test**

`backend/tests/test_stub_places.py`:
```python
import asyncio

import polyline as polyline_lib

from app.models import LatLng
from app.places.stub import StubPlacesProvider


def test_nearby_returns_places_near_origin():
    origin = LatLng(lat=1.3, lng=103.8)

    places = asyncio.run(StubPlacesProvider().nearby("gas station", origin))

    assert len(places) >= 1
    for p in places:
        assert abs(p.lat - origin.lat) < 0.01
        assert abs(p.lng - origin.lng) < 0.01
        assert "gas station" in p.name


def test_along_route_returns_places_near_polyline():
    encoded = polyline_lib.encode([(1.30, 103.80), (1.31, 103.81), (1.32, 103.82)])

    places = asyncio.run(StubPlacesProvider().along_route("restaurant", encoded))

    assert len(places) >= 1
    for p in places:
        assert "restaurant" in p.name
```

- [ ] **Step 2: Run it to verify it fails**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"
.\.venv\Scripts\python.exe -m pytest tests/test_stub_places.py -v
```
Expected: FAIL — `ModuleNotFoundError: app.places.stub`.

- [ ] **Step 3: Write `base.py`**

```python
from typing import Protocol

from app.models import LatLng, Place


class PlacesProvider(Protocol):
    async def along_route(self, query: str, polyline: str) -> list[Place]: ...
    async def nearby(self, query: str, origin: LatLng) -> list[Place]: ...
```

- [ ] **Step 4: Write `stub.py`**

```python
import polyline as polyline_lib

from app.models import LatLng, Place

_OFFSETS = [(0.0010, 0.0010), (-0.0015, 0.0012), (0.0008, -0.0017)]


def _fake(query: str, lat: float, lng: float) -> list[Place]:
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
            )
        )
    return places


class StubPlacesProvider:
    async def along_route(self, query: str, polyline: str) -> list[Place]:
        points = polyline_lib.decode(polyline)
        if not points:
            return []
        mid_lat, mid_lng = points[len(points) // 2]
        return _fake(query, mid_lat, mid_lng)

    async def nearby(self, query: str, origin: LatLng) -> list[Place]:
        return _fake(query, origin.lat, origin.lng)
```

- [ ] **Step 5: Run the test to verify it passes**

```powershell
.\.venv\Scripts\python.exe -m pytest tests/test_stub_places.py -v
```
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add backend/app/places/base.py backend/app/places/stub.py backend/tests/test_stub_places.py
git commit -m "feat(backend): PlacesProvider protocol + StubPlacesProvider with tests"
```

---

### Task 3: Config factory for the places provider

**Files:**
- Modify: `backend/app/config.py`

- [ ] **Step 1: Add settings fields + `get_places_provider()`**

Replace `backend/app/config.py` with:
```python
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict

from app.places.base import PlacesProvider
from app.places.stub import StubPlacesProvider
from app.planners.base import RoutePlanner
from app.planners.stub import StubRoutePlanner


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")
    route_planner: str = "stub"
    google_routes_api_key: str = ""
    places_provider: str = "stub"
    google_places_api_key: str = ""


@lru_cache
def get_settings() -> Settings:
    return Settings()


def get_planner() -> RoutePlanner:
    settings = get_settings()
    if settings.route_planner == "google":
        from app.planners.google import GoogleRoutePlanner

        return GoogleRoutePlanner(api_key=settings.google_routes_api_key)
    return StubRoutePlanner()


def get_places_provider() -> PlacesProvider:
    settings = get_settings()
    if settings.places_provider == "google":
        from app.places.google import GooglePlacesProvider

        return GooglePlacesProvider(api_key=settings.google_places_api_key)
    return StubPlacesProvider()
```

- [ ] **Step 2: Verify default provider is the stub**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"
.\.venv\Scripts\python.exe -c "from app.config import get_places_provider; print(type(get_places_provider()).__name__)"
```
Expected: `StubPlacesProvider`.

- [ ] **Step 3: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add backend/app/config.py
git commit -m "feat(backend): config factory for places provider"
```

---

### Task 4: `POST /places/search` endpoint + fallback (TDD)

**Files:**
- Create: `backend/app/routers/places.py`, `backend/tests/test_places.py`
- Modify: `backend/app/main.py`

- [ ] **Step 1: Write the failing tests (including the fallback)**

`backend/tests/test_places.py`:
```python
from fastapi.testclient import TestClient

from app.config import get_places_provider
from app.main import app
from app.models import Place


class _FakeProvider:
    def __init__(self, along: list[Place], near: list[Place]):
        self._along = along
        self._near = near

    async def along_route(self, query: str, polyline: str) -> list[Place]:
        return self._along

    async def nearby(self, query: str, origin) -> list[Place]:
        return self._near


def _place(name: str) -> Place:
    return Place(id=name, name=name, lat=1.0, lng=2.0)


def _client(along: list[Place], near: list[Place]) -> TestClient:
    app.dependency_overrides[get_places_provider] = lambda: _FakeProvider(along, near)
    return TestClient(app)


def teardown_function():
    app.dependency_overrides.pop(get_places_provider, None)


def test_along_route_returns_along_route_mode():
    client = _client(along=[_place("A")], near=[_place("N")])
    body = {"query": "gas station", "origin": {"lat": 1.0, "lng": 2.0}, "polyline": "abc"}
    resp = client.post("/places/search", json=body)
    assert resp.status_code == 200
    data = resp.json()
    assert data["mode"] == "along_route"
    assert [p["name"] for p in data["places"]] == ["A"]


def test_no_polyline_uses_nearby():
    client = _client(along=[_place("A")], near=[_place("N")])
    body = {"query": "restaurant", "origin": {"lat": 1.0, "lng": 2.0}}
    resp = client.post("/places/search", json=body)
    data = resp.json()
    assert data["mode"] == "nearby"
    assert [p["name"] for p in data["places"]] == ["N"]


def test_empty_along_route_falls_back_to_nearby():
    client = _client(along=[], near=[_place("N")])
    body = {"query": "gas station", "origin": {"lat": 1.0, "lng": 2.0}, "polyline": "abc"}
    resp = client.post("/places/search", json=body)
    data = resp.json()
    assert data["mode"] == "nearby"
    assert [p["name"] for p in data["places"]] == ["N"]


def test_malformed_body_422():
    client = _client(along=[], near=[])
    resp = client.post("/places/search", json={"query": "x"})
    assert resp.status_code == 422
```

- [ ] **Step 2: Run it to verify it fails**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"
.\.venv\Scripts\python.exe -m pytest tests/test_places.py -v
```
Expected: FAIL — `/places/search` returns 404 (route not registered yet).

- [ ] **Step 3: Write `routers/places.py`**

```python
import httpx
from fastapi import APIRouter, Depends, HTTPException

from app.config import get_places_provider
from app.models import PlacesSearchRequest, PlacesSearchResponse
from app.places.base import PlacesProvider

router = APIRouter()


@router.post("/places/search", response_model=PlacesSearchResponse)
async def search_places(
    request: PlacesSearchRequest,
    provider: PlacesProvider = Depends(get_places_provider),
) -> PlacesSearchResponse:
    places = []
    mode = "nearby"
    try:
        if request.polyline:
            places = await provider.along_route(request.query, request.polyline)
            mode = "along_route"
        if not places:
            places = await provider.nearby(request.query, request.origin)
            mode = "nearby"
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=f"Places provider error: {exc}") from exc
    return PlacesSearchResponse(mode=mode, places=places)
```

- [ ] **Step 4: Register the router in `main.py`**

Replace `backend/app/main.py` with:
```python
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.routers import places, routes

app = FastAPI(title="Copilot Maps Backend")

# Permissive CORS for local development only.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(routes.router)
app.include_router(places.router)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}
```

- [ ] **Step 5: Run the full backend suite to verify it passes**

```powershell
.\.venv\Scripts\python.exe -m pytest -q
```
Expected: all pass (routes, stub planner, stub places, places endpoint).

- [ ] **Step 6: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add backend/app/routers/places.py backend/app/main.py backend/tests/test_places.py
git commit -m "feat(backend): POST /places/search with along-route->nearby fallback"
```

---

### Task 5: `GooglePlacesProvider` + Cloud setup

**Files:**
- Create: `backend/app/places/google.py`
- Modify: `backend/.env` (git-ignored)

- [ ] **Step 1: Enable Places API (New) + allow it on the server key (Cloud Console)**

In the `copilot-maps` project:
- **APIs & Services → Library** → search **"Places API (New)"** → **Enable**.
- **APIs & Services → Credentials** → open your existing **routes server key** → under **API restrictions**, add **Places API (New)** to the allowed list (keep Routes API too) → **Save**.

- [ ] **Step 2: Set places env in `backend/.env`**

Add to `backend/.env` (reuse the same server key string as `GOOGLE_ROUTES_API_KEY`):
```
PLACES_PROVIDER=google
GOOGLE_PLACES_API_KEY=PASTE_SAME_SERVER_KEY_HERE
```

- [ ] **Step 3: Write `google.py`**

```python
import httpx

from app.models import LatLng, Place

_SEARCH_TEXT_URL = "https://places.googleapis.com/v1/places:searchText"
_FIELD_MASK = (
    "places.id,places.displayName,places.location,"
    "places.formattedAddress,places.rating"
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
    )


class GooglePlacesProvider:
    def __init__(self, api_key: str):
        self._api_key = api_key

    async def _search(self, body: dict) -> list[Place]:
        headers = {
            "Content-Type": "application/json",
            "X-Goog-Api-Key": self._api_key,
            "X-Goog-FieldMask": _FIELD_MASK,
        }
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.post(_SEARCH_TEXT_URL, json=body, headers=headers)
        resp.raise_for_status()
        return [_to_place(p) for p in resp.json().get("places", [])]

    async def along_route(self, query: str, polyline: str) -> list[Place]:
        body = {
            "textQuery": query,
            "searchAlongRouteParameters": {"polyline": {"encodedPolyline": polyline}},
        }
        return await self._search(body)

    async def nearby(self, query: str, origin: LatLng) -> list[Place]:
        body = {
            "textQuery": query,
            "locationBias": {
                "circle": {
                    "center": {"latitude": origin.lat, "longitude": origin.lng},
                    "radius": 5000.0,
                }
            },
        }
        return await self._search(body)
```

- [ ] **Step 4: Confirm the suite still passes (tests use the fake/stub, unaffected)**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"
.\.venv\Scripts\python.exe -m pytest -q
```
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add backend/app/places/google.py
git commit -m "feat(backend): GooglePlacesProvider (Places API New searchText)"
```

---

# PHASE 2 — App

### Task 6: Network layer — places DTOs, API, shared Retrofit

**Files:**
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/network/PlacesDtos.kt`, `PlacesApi.kt`
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/network/NetworkModule.kt`

- [ ] **Step 1: Write `PlacesDtos.kt`**

```kotlin
package com.virtueson.copilotmaps.network

data class PlaceDto(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String? = null,
    val rating: Double? = null,
)

data class PlacesSearchRequestDto(
    val query: String,
    val origin: LatLngDto,
    val polyline: String? = null,
)

data class PlacesSearchResponseDto(
    val mode: String,
    val places: List<PlaceDto>,
)
```

- [ ] **Step 2: Write `PlacesApi.kt`**

```kotlin
package com.virtueson.copilotmaps.network

import retrofit2.http.Body
import retrofit2.http.POST

interface PlacesApi {
    @POST("places/search")
    suspend fun searchPlaces(@Body body: PlacesSearchRequestDto): PlacesSearchResponseDto
}
```

- [ ] **Step 3: Refactor `NetworkModule.kt` to share one Retrofit and expose both APIs**

Replace `NetworkModule.kt` with:
```kotlin
package com.virtueson.copilotmaps.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** Single Retrofit instance for the app. Base URL works via `adb reverse`. */
object NetworkModule {
    private const val BASE_URL = "http://localhost:8000/"

    private val retrofit: Retrofit by lazy {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    val routesApi: RoutesApi by lazy { retrofit.create(RoutesApi::class.java) }
    val placesApi: PlacesApi by lazy { retrofit.create(PlacesApi::class.java) }
}
```

- [ ] **Step 4: Compile**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add android/app/src/main/java/com/virtueson/copilotmaps/network/PlacesDtos.kt android/app/src/main/java/com/virtueson/copilotmaps/network/PlacesApi.kt android/app/src/main/java/com/virtueson/copilotmaps/network/NetworkModule.kt
git commit -m "feat(app): places DTOs, PlacesApi, shared Retrofit"
```

---

### Task 7: Domain `Place` + repository + keep route polyline

**Files:**
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/data/Place.kt`, `PlacesRepository.kt`
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/data/Route.kt`, `RoutesRepository.kt`

- [ ] **Step 1: Write `Place.kt`**

```kotlin
package com.virtueson.copilotmaps.data

data class Place(
    val id: String,
    val name: String,
    val location: GeoPoint,
    val address: String?,
    val rating: Double?,
)

sealed interface PlacesResult {
    data class Success(val places: List<Place>, val mode: String) : PlacesResult
    data class Failure(val reason: String) : PlacesResult
}
```

- [ ] **Step 2: Write `PlacesRepository.kt`**

```kotlin
package com.virtueson.copilotmaps.data

import com.virtueson.copilotmaps.network.LatLngDto
import com.virtueson.copilotmaps.network.PlacesApi
import com.virtueson.copilotmaps.network.PlacesSearchRequestDto

interface PlacesRepository {
    suspend fun searchPlaces(query: String, origin: GeoPoint, polyline: String?): PlacesResult
}

class DefaultPlacesRepository(
    private val api: PlacesApi,
) : PlacesRepository {
    override suspend fun searchPlaces(query: String, origin: GeoPoint, polyline: String?): PlacesResult {
        return try {
            val response = api.searchPlaces(
                PlacesSearchRequestDto(
                    query = query,
                    origin = LatLngDto(origin.lat, origin.lng),
                    polyline = polyline,
                )
            )
            val places = response.places.map { dto ->
                Place(
                    id = dto.id,
                    name = dto.name,
                    location = GeoPoint(dto.lat, dto.lng),
                    address = dto.address,
                    rating = dto.rating,
                )
            }
            PlacesResult.Success(places, response.mode)
        } catch (e: Exception) {
            PlacesResult.Failure("Can't reach the server — is the backend running and adb reverse set?")
        }
    }
}
```

- [ ] **Step 3: Add `polyline` to the domain `Route`**

Replace `Route.kt` with:
```kotlin
package com.virtueson.copilotmaps.data

/** A drawable route: geometry already decoded into points. */
data class Route(
    val id: String,
    val summary: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val points: List<GeoPoint>,
    val polyline: String = "",
    val trafficIntervals: List<TrafficInterval> = emptyList(),
)

sealed interface RoutesResult {
    data class Success(val routes: List<Route>) : RoutesResult
    data class Failure(val reason: String) : RoutesResult
}
```

- [ ] **Step 4: Keep the encoded polyline in `RoutesRepository.kt`**

In `RoutesRepository.kt`, update the `Route(...)` construction inside `planRoutes` to pass `polyline = dto.polyline`. The full mapping block becomes:
```kotlin
            val routes = response.routes.map { dto ->
                Route(
                    id = dto.id,
                    summary = dto.summary,
                    distanceMeters = dto.distanceMeters,
                    durationSeconds = dto.durationSeconds,
                    points = PolyUtil.decode(dto.polyline).map { GeoPoint(it.latitude, it.longitude) },
                    polyline = dto.polyline,
                    trafficIntervals = dto.trafficIntervals.map {
                        TrafficInterval(it.startIndex, it.endIndex, it.speed.toTrafficSpeed())
                    },
                )
            }
```

- [ ] **Step 5: Compile**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add android/app/src/main/java/com/virtueson/copilotmaps/data/Place.kt android/app/src/main/java/com/virtueson/copilotmaps/data/PlacesRepository.kt android/app/src/main/java/com/virtueson/copilotmaps/data/Route.kt android/app/src/main/java/com/virtueson/copilotmaps/data/RoutesRepository.kt
git commit -m "feat(app): Place domain + PlacesRepository; keep route polyline"
```

---

### Task 8: `PlacesState` + `PlacesViewModel` (TDD)

**Files:**
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/PlacesState.kt`, `PlacesViewModel.kt`
- Test: `android/app/src/test/java/com/virtueson/copilotmaps/ui/map/PlacesViewModelTest.kt`

- [ ] **Step 1: Write `PlacesState.kt`**

```kotlin
package com.virtueson.copilotmaps.ui.map

import com.virtueson.copilotmaps.data.Place

sealed interface PlacesState {
    data object Idle : PlacesState
    data object Loading : PlacesState
    data class Loaded(val places: List<Place>, val fellBackToNearby: Boolean) : PlacesState
    data class Error(val message: String) : PlacesState
}
```

- [ ] **Step 2: Write the failing test**

`android/app/src/test/java/com/virtueson/copilotmaps/ui/map/PlacesViewModelTest.kt`:
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
```

- [ ] **Step 3: Run it to verify it fails**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat :app:testDebugUnitTest --tests "com.virtueson.copilotmaps.ui.map.PlacesViewModelTest"
```
Expected: FAIL — `PlacesViewModel` / `PlaceCategory` unresolved.

- [ ] **Step 4: Write `PlacesViewModel.kt`**

```kotlin
package com.virtueson.copilotmaps.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.virtueson.copilotmaps.data.GeoPoint
import com.virtueson.copilotmaps.data.PlacesRepository
import com.virtueson.copilotmaps.data.PlacesResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PlaceCategory(val query: String) {
    GAS("gas station"),
    FOOD("restaurant"),
}

class PlacesViewModel(
    private val repository: PlacesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<PlacesState>(PlacesState.Idle)
    val state: StateFlow<PlacesState> = _state.asStateFlow()

    fun search(category: PlaceCategory, origin: GeoPoint, routePolyline: String?) {
        _state.value = PlacesState.Loading
        viewModelScope.launch {
            _state.value = when (val result = repository.searchPlaces(category.query, origin, routePolyline)) {
                is PlacesResult.Success -> PlacesState.Loaded(
                    places = result.places,
                    fellBackToNearby = routePolyline != null && result.mode == "nearby",
                )
                is PlacesResult.Failure -> PlacesState.Error(result.reason)
            }
        }
    }

    fun clear() {
        _state.value = PlacesState.Idle
    }
}

class PlacesViewModelFactory(
    private val repository: PlacesRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PlacesViewModel(repository) as T
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.virtueson.copilotmaps.ui.map.PlacesViewModelTest"
```
Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add android/app/src/main/java/com/virtueson/copilotmaps/ui/map/PlacesState.kt android/app/src/main/java/com/virtueson/copilotmaps/ui/map/PlacesViewModel.kt android/app/src/test/java/com/virtueson/copilotmaps/ui/map/PlacesViewModelTest.kt
git commit -m "feat(app): PlacesState + PlacesViewModel with unit tests"
```

---

### Task 9: `MapScreen` — buttons, markers, note + on-device verify

**Files:**
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt`

- [ ] **Step 1: Replace `MapScreen.kt` with the places-enabled version**

```kotlin
package com.virtueson.copilotmaps.ui.map

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import com.virtueson.copilotmaps.data.DefaultPlacesRepository
import com.virtueson.copilotmaps.data.DefaultRoutesRepository
import com.virtueson.copilotmaps.data.GeoPoint
import com.virtueson.copilotmaps.data.Place
import com.virtueson.copilotmaps.data.TrafficSpeed
import com.virtueson.copilotmaps.data.buildTrafficSegments
import com.virtueson.copilotmaps.location.FusedLocationProvider
import com.virtueson.copilotmaps.network.NetworkModule

@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mapViewModel: MapViewModel = viewModel(
        factory = MapViewModelFactory(
            FusedLocationProvider(LocationServices.getFusedLocationProviderClient(context))
        )
    )
    val routeViewModel: RouteViewModel = viewModel(
        factory = RouteViewModelFactory(DefaultRoutesRepository(NetworkModule.routesApi))
    )
    val placesViewModel: PlacesViewModel = viewModel(
        factory = PlacesViewModelFactory(DefaultPlacesRepository(NetworkModule.placesApi))
    )
    val locationState by mapViewModel.uiState.collectAsStateWithLifecycle()
    val routesState by routeViewModel.state.collectAsStateWithLifecycle()
    val placesState by placesViewModel.state.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) mapViewModel.onPermissionGranted() else mapViewModel.onPermissionDenied()
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) mapViewModel.onPermissionGranted()
        else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    when (val loc = locationState) {
        is MapUiState.Loading -> Centered(modifier) { CircularProgressIndicator() }

        is MapUiState.PermissionNeeded -> Centered(modifier) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text(
                    "Location permission is needed to show your position on the map.",
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) {
                    Text("Grant permission")
                }
            }
        }

        is MapUiState.Error -> Centered(modifier) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text("Couldn't get your location: ${loc.message}", textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { mapViewModel.onPermissionGranted() }) { Text("Retry") }
            }
        }

        is MapUiState.Located -> {
            val origin = GeoPoint(loc.latitude, loc.longitude)
            RoutingMap(
                modifier = modifier,
                origin = origin,
                routesState = routesState,
                placesState = placesState,
                onPlan = { dest -> routeViewModel.planRoutes(origin, dest) },
                onSelect = routeViewModel::selectRoute,
                onSearchPlaces = { category ->
                    val polyline = (routesState as? RoutesState.Loaded)?.let { loaded ->
                        loaded.routes.firstOrNull { it.id == loaded.selectedId }?.polyline
                    }
                    placesViewModel.search(category, origin, polyline)
                },
                onClearPlaces = placesViewModel::clear,
            )
        }
    }
}

@Composable
private fun RoutingMap(
    modifier: Modifier,
    origin: GeoPoint,
    routesState: RoutesState,
    placesState: PlacesState,
    onPlan: (GeoPoint) -> Unit,
    onSelect: (String) -> Unit,
    onSearchPlaces: (PlaceCategory) -> Unit,
    onClearPlaces: () -> Unit,
) {
    var destination by remember { mutableStateOf<LatLng?>(null) }
    val originLatLng = LatLng(origin.lat, origin.lng)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(originLatLng, 15f)
    }

    Box(modifier = modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(isMyLocationEnabled = true),
            uiSettings = MapUiSettings(myLocationButtonEnabled = true),
            onMapLongClick = { latLng ->
                destination = latLng
                onPlan(GeoPoint(latLng.latitude, latLng.longitude))
            },
        ) {
            destination?.let { dest ->
                Marker(state = rememberMarkerState(key = dest.toString(), position = dest), title = "Destination")
            }
            if (routesState is RoutesState.Loaded) {
                routesState.routes.forEach { route ->
                    val selected = route.id == routesState.selectedId
                    if (selected) {
                        buildTrafficSegments(route.points, route.trafficIntervals).forEach { segment ->
                            Polyline(
                                points = segment.points.map { LatLng(it.lat, it.lng) },
                                color = trafficColor(segment.speed),
                                width = 18f,
                                zIndex = 2f,
                                clickable = true,
                                onClick = { onSelect(route.id) },
                            )
                        }
                    } else {
                        Polyline(
                            points = route.points.map { LatLng(it.lat, it.lng) },
                            color = Color(0xFF9AA0A6),
                            width = 9f,
                            zIndex = 1f,
                            clickable = true,
                            onClick = { onSelect(route.id) },
                        )
                    }
                }
            }
            if (placesState is PlacesState.Loaded) {
                placesState.places.forEach { place ->
                    Marker(
                        state = rememberMarkerState(
                            key = place.id,
                            position = LatLng(place.location.lat, place.location.lng),
                        ),
                        title = place.name,
                        snippet = placeSnippet(place),
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE),
                    )
                }
            }
        }

        // Top overlay: category buttons + status/notes.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier.padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Button(onClick = { onSearchPlaces(PlaceCategory.GAS) }) { Text("Gas") }
                    Button(onClick = { onSearchPlaces(PlaceCategory.FOOD) }) { Text("Food") }
                    OutlinedButton(onClick = onClearPlaces) { Text("Clear") }
                }
            }
            val banner: String? = when {
                placesState is PlacesState.Loading -> "Searching…"
                placesState is PlacesState.Error -> placesState.message
                placesState is PlacesState.Loaded && placesState.places.isEmpty() -> "No places found nearby."
                placesState is PlacesState.Loaded && placesState.fellBackToNearby -> "None on your route — showing nearest."
                else -> null
            }
            if (banner != null) {
                Spacer(Modifier.height(6.dp))
                Surface(tonalElevation = 3.dp) {
                    Text(banner, modifier = Modifier.padding(8.dp), textAlign = TextAlign.Center)
                }
            }
        }

        // Bottom overlay: route ETA cards / status.
        when (routesState) {
            is RoutesState.Idle -> BottomBar { Text("Long-press the map to set a destination") }
            is RoutesState.Loading -> BottomBar { Text("Finding routes…") }
            is RoutesState.Error -> BottomBar {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(routesState.message, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { destination?.let { onPlan(GeoPoint(it.latitude, it.longitude)) } }) {
                        Text("Retry")
                    }
                }
            }
            is RoutesState.Loaded -> RouteCards(
                state = routesState,
                onSelect = onSelect,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

private fun placeSnippet(place: Place): String {
    val parts = mutableListOf<String>()
    place.rating?.let { parts.add("★ $it") }
    place.address?.let { parts.add(it) }
    return parts.joinToString(" · ")
}

@Composable
private fun BoxScope.BottomBar(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(12.dp),
        tonalElevation = 3.dp,
    ) {
        Box(Modifier.padding(16.dp), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun RouteCards(
    state: RoutesState.Loaded,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.routes.forEach { route ->
            val selected = route.id == state.selectedId
            Card(
                onClick = { onSelect(route.id) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) Color(0xFFD2E3FC) else Color(0xFFF1F3F4)
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = route.summary,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text(formatDuration(route.durationSeconds))
                    Text(formatDistance(route.distanceMeters))
                }
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val minutes = (seconds + 30) / 60
    return "$minutes min"
}

private fun formatDistance(meters: Int): String =
    if (meters >= 1000) String.format("%.1f km", meters / 1000.0) else "$meters m"

private fun trafficColor(speed: TrafficSpeed): Color = when (speed) {
    TrafficSpeed.NORMAL -> Color(0xFF34A853)
    TrafficSpeed.SLOW -> Color(0xFFFBBC04)
    TrafficSpeed.JAM -> Color(0xFFEA4335)
    TrafficSpeed.UNKNOWN -> Color(0xFF1A73E8)
}

@Composable
private fun Centered(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        content()
    }
}
```

- [ ] **Step 2: Compile**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL. (Fix any maps-compose `Marker(icon=...)` drift if the version differs.)

- [ ] **Step 3: On-device verification (stub first)**

Start the backend with the stub places provider and the tunnel:
```powershell
# terminal A
cd "D:\Russell\Data Science Job\AI Maps\backend"; $env:PLACES_PROVIDER="stub"; .\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8000
# terminal B
& "C:\Users\Russell\AppData\Local\Android\Sdk\platform-tools\adb.exe" reverse tcp:8000 tcp:8000
```
Run the app (▶):
- No route: tap **Food** → orange markers appear near you; tap a marker → name + rating/address.
- Long-press a destination (routes draw), tap **Gas** → markers near the route.
- **Clear** → markers gone.

- [ ] **Step 4: On-device verification (real Google places)**

Restart the backend without the stub override (uses `.env` → `PLACES_PROVIDER=google`):
```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"; .\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8000
```
In the app: tap **Gas**/**Food** → **real** gas stations / restaurants appear (along the route if one's selected, else near you); routing through an area with none of a category shows the *"None on your route — showing nearest."* note.

- [ ] **Step 5: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt
git commit -m "feat(app): Gas/Food place markers along route or nearby"
```

---

## Definition of Done (verify all)

- [ ] `POST /places/search` returns places with along-route→nearby fallback (stub + Google).
- [ ] Tap Gas/Food → markers (along route or nearby); marker tap shows name + rating/address.
- [ ] "None on your route — showing nearest." note appears on fallback; Clear removes markers.
- [ ] Backend `pytest` (all) and app `PlacesViewModelTest` pass.
- [ ] `git status` clean; `.env`/`local.properties` never committed.

---

## Notes for later

- **Step 4 (copilot):** `POST /copilot/ask` LLM agent calls `/routes/plan` and `/places/search` as tools.
- Per-category marker hues (gas vs food) or richer place detail (photos, hours) can come later.
- "Add this place as a waypoint and re-route" is a natural future enhancement.
```
