# Copilot Maps — Step 2 (Routing) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** From the user's live location, long-press the map to set a destination and see up to 3 alternate driving routes (polyline + ETA + distance) drawn on the map and selectable — served by a FastAPI backend that starts stubbed and later swaps to the real Google Routes API with no app changes.

**Architecture:** Stub-first, contract-driven. A FastAPI `/routes/plan` endpoint depends on a `RoutePlanner` protocol; `StubRoutePlanner` (default) returns fake-but-real-geometry routes, `GoogleRoutePlanner` (added last) calls the Routes API. The app calls the backend over `adb reverse` via Retrofit → `RoutesRepository` → `RouteViewModel`, drawing polylines + ETA cards in the existing `MapScreen`.

**Tech Stack:** Backend — Python, FastAPI, Pydantic, httpx, polyline, pytest. App — Kotlin, Retrofit + Moshi + OkHttp logging, `android-maps-utils` (PolyUtil), Jetpack Compose / maps-compose.

---

## Conventions

- **Backend lives at** `D:\Russell\Data Science Job\AI Maps\backend\`. All backend commands run from there.
- **App package:** `com.virtueson.copilotmaps` (unchanged). App files under `android/app/src/main/java/com/virtueson/copilotmaps/`.
- **Connectivity:** phone reaches the backend via `adb reverse tcp:8000 tcp:8000`; app base URL is `http://localhost:8000/`.
- **Gradle from CLI:** set `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` first, then `cd android; .\gradlew.bat <task>`.
- **Python:** assumes Python 3.11+ on PATH (`python --version`). A virtualenv lives at `backend\.venv`.
- **Domain coordinate type:** the app uses a plain `GeoPoint(lat, lng)` in the domain/ViewModel (NOT the Android `LatLng`) so ViewModel logic is unit-testable on the JVM. `LatLng` is used only at the map-drawing layer.

---

## File Structure

```
backend/
  .venv/                         # virtualenv (git-ignored)
  requirements.txt
  .env.example                   # committed; real .env is git-ignored
  app/
    __init__.py
    main.py                      # FastAPI app + CORS + /health + router
    models.py                    # Pydantic contract
    config.py                    # Settings + get_planner() factory
    routers/
      __init__.py
      routes.py                  # POST /routes/plan
    planners/
      __init__.py
      base.py                    # RoutePlanner protocol
      stub.py                    # StubRoutePlanner
      google.py                  # GoogleRoutePlanner (Task 11)
  tests/
    __init__.py
    test_routes.py               # endpoint + stub + validation + health

android/app/src/main/java/com/virtueson/copilotmaps/
  network/
    Dtos.kt                      # LatLngDto, RoutePlanRequestDto, RouteDto, RoutePlanResponseDto
    RoutesApi.kt                 # Retrofit interface
    NetworkModule.kt             # Retrofit/OkHttp/Moshi setup
  data/
    GeoPoint.kt                  # domain coordinate
    Route.kt                     # domain route + RoutesResult
    RoutesRepository.kt          # interface + DefaultRoutesRepository
  ui/map/
    RoutesState.kt               # Idle/Loading/Loaded/Error
    RouteViewModel.kt            # + factory
    MapScreen.kt                 # MODIFIED: long-press, polylines, ETA cards
android/app/src/debug/
  AndroidManifest.xml            # debug-only: networkSecurityConfig
  res/xml/network_security_config.xml  # cleartext to localhost (debug only)
android/app/src/test/java/com/virtueson/copilotmaps/ui/map/
  RouteViewModelTest.kt          # JVM unit tests
```

---

# PHASE 1 — Backend (stubbed)

### Task 1: Backend skeleton + virtualenv + deps

**Files:**
- Create: `backend/requirements.txt`, `backend/.env.example`, `backend/app/__init__.py`, `backend/app/routers/__init__.py`, `backend/app/planners/__init__.py`, `backend/tests/__init__.py`

- [ ] **Step 1: Create `backend/requirements.txt`**

```
fastapi>=0.115
uvicorn[standard]>=0.30
pydantic>=2.7
pydantic-settings>=2.3
httpx>=0.27
polyline>=2.0
pytest>=8.0
```

- [ ] **Step 2: Create `backend/.env.example`**

```
ROUTE_PLANNER=stub
GOOGLE_ROUTES_API_KEY=
```

- [ ] **Step 3: Create the empty package markers**

Create these four empty files (no content needed):
`backend/app/__init__.py`, `backend/app/routers/__init__.py`, `backend/app/planners/__init__.py`, `backend/tests/__init__.py`

- [ ] **Step 4: Create the venv and install deps**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```
Expected: installs without errors; `pip list` shows fastapi, uvicorn, polyline, pytest.

- [ ] **Step 5: Ignore venv and real .env**

Append to the repo-root `.gitignore`:
```
# Python backend
backend/.venv/
backend/.env
__pycache__/
.pytest_cache/
```

- [ ] **Step 6: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add backend/requirements.txt backend/.env.example backend/app/__init__.py backend/app/routers/__init__.py backend/app/planners/__init__.py backend/tests/__init__.py .gitignore
git commit -m "chore(backend): FastAPI skeleton, venv deps, gitignore"
```

---

### Task 2: The contract (`models.py`)

**Files:**
- Create: `backend/app/models.py`

- [ ] **Step 1: Write `models.py`**

```python
from pydantic import BaseModel


class LatLng(BaseModel):
    lat: float
    lng: float


class Route(BaseModel):
    id: str
    summary: str
    distance_meters: int
    duration_seconds: int
    polyline: str


class RoutePlanRequest(BaseModel):
    origin: LatLng
    destination: LatLng


class RoutePlanResponse(BaseModel):
    routes: list[Route]
```

- [ ] **Step 2: Verify it imports**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"; .\.venv\Scripts\Activate.ps1
python -c "from app.models import RoutePlanResponse; print('ok')"
```
Expected: prints `ok`.

- [ ] **Step 3: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add backend/app/models.py
git commit -m "feat(backend): add /routes/plan Pydantic contract"
```

---

### Task 3: `RoutePlanner` protocol + `StubRoutePlanner` (TDD)

**Files:**
- Create: `backend/app/planners/base.py`, `backend/app/planners/stub.py`
- Test: `backend/tests/test_stub_planner.py`

- [ ] **Step 1: Write the failing test**

`backend/tests/test_stub_planner.py`:
```python
import asyncio

from app.models import LatLng
from app.planners.stub import StubRoutePlanner


def test_stub_returns_three_connected_routes():
    origin = LatLng(lat=1.2966, lng=103.7764)
    dest = LatLng(lat=1.3521, lng=103.8198)

    routes = asyncio.run(StubRoutePlanner().plan(origin, dest))

    assert len(routes) == 3
    ids = [r.id for r in routes]
    assert ids == ["route-0", "route-1", "route-2"]
    for r in routes:
        assert r.polyline                      # non-empty encoded polyline
        assert r.distance_meters > 0
        assert r.duration_seconds > 0
        assert r.summary
```

- [ ] **Step 2: Run it to verify it fails**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"; .\.venv\Scripts\Activate.ps1
pytest tests/test_stub_planner.py -v
```
Expected: FAIL — `ModuleNotFoundError: app.planners.stub`.

- [ ] **Step 3: Write `base.py`**

```python
from typing import Protocol

from app.models import LatLng, Route


class RoutePlanner(Protocol):
    async def plan(self, origin: LatLng, destination: LatLng) -> list[Route]: ...
```

- [ ] **Step 4: Write `stub.py`**

```python
import math

import polyline

from app.models import LatLng, Route

# ~40 km/h average urban speed, in metres/second.
_ASSUMED_SPEED_MPS = 11.0


def _haversine_m(a: LatLng, b: LatLng) -> float:
    radius = 6_371_000.0
    phi1, phi2 = math.radians(a.lat), math.radians(b.lat)
    d_phi = math.radians(b.lat - a.lat)
    d_lambda = math.radians(b.lng - a.lng)
    h = math.sin(d_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2
    return 2 * radius * math.asin(math.sqrt(h))


def _offset_midpoint(a: LatLng, b: LatLng, perp_offset_deg: float) -> LatLng:
    """Midpoint of a->b, shifted perpendicular to the line so alternates look distinct."""
    mid_lat = (a.lat + b.lat) / 2
    mid_lng = (a.lng + b.lng) / 2
    d_lat = b.lat - a.lat
    d_lng = b.lng - a.lng
    norm = math.hypot(d_lat, d_lng) or 1.0
    perp_lat = -d_lng / norm
    perp_lng = d_lat / norm
    return LatLng(lat=mid_lat + perp_lat * perp_offset_deg, lng=mid_lng + perp_lng * perp_offset_deg)


class StubRoutePlanner:
    """Offline planner: 3 fake routes that actually connect origin to destination."""

    _VARIANTS = [
        (0.0, "Direct route"),
        (0.012, "Scenic detour"),
        (-0.018, "Alternate way"),
    ]

    async def plan(self, origin: LatLng, destination: LatLng) -> list[Route]:
        routes: list[Route] = []
        for i, (offset, summary) in enumerate(self._VARIANTS):
            mid = _offset_midpoint(origin, destination, offset)
            points = [(origin.lat, origin.lng), (mid.lat, mid.lng), (destination.lat, destination.lng)]
            encoded = polyline.encode(points)
            leg_m = _haversine_m(origin, mid) + _haversine_m(mid, destination)
            routes.append(
                Route(
                    id=f"route-{i}",
                    summary=summary,
                    distance_meters=int(leg_m),
                    duration_seconds=int(leg_m / _ASSUMED_SPEED_MPS),
                    polyline=encoded,
                )
            )
        return routes
```

- [ ] **Step 5: Run the test to verify it passes**

```powershell
pytest tests/test_stub_planner.py -v
```
Expected: PASS (1 test).

- [ ] **Step 6: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add backend/app/planners/base.py backend/app/planners/stub.py backend/tests/test_stub_planner.py
git commit -m "feat(backend): RoutePlanner protocol + StubRoutePlanner with tests"
```

---

### Task 4: Config + planner factory

**Files:**
- Create: `backend/app/config.py`

- [ ] **Step 1: Write `config.py`**

```python
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict

from app.planners.base import RoutePlanner
from app.planners.stub import StubRoutePlanner


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")
    route_planner: str = "stub"
    google_routes_api_key: str = ""


@lru_cache
def get_settings() -> Settings:
    return Settings()


def get_planner() -> RoutePlanner:
    settings = get_settings()
    if settings.route_planner == "google":
        from app.planners.google import GoogleRoutePlanner

        return GoogleRoutePlanner(api_key=settings.google_routes_api_key)
    return StubRoutePlanner()
```

- [ ] **Step 2: Verify default planner is the stub**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"; .\.venv\Scripts\Activate.ps1
python -c "from app.config import get_planner; print(type(get_planner()).__name__)"
```
Expected: prints `StubRoutePlanner`.

- [ ] **Step 3: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add backend/app/config.py
git commit -m "feat(backend): settings + config-driven planner factory"
```

---

### Task 5: Endpoint + app wiring + health (TDD)

**Files:**
- Create: `backend/app/routers/routes.py`, `backend/app/main.py`, `backend/tests/test_routes.py`

- [ ] **Step 1: Write the failing endpoint test**

`backend/tests/test_routes.py`:
```python
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health_ok():
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}


def test_plan_returns_three_routes():
    body = {
        "origin": {"lat": 1.2966, "lng": 103.7764},
        "destination": {"lat": 1.3521, "lng": 103.8198},
    }
    resp = client.post("/routes/plan", json=body)
    assert resp.status_code == 200
    data = resp.json()
    assert len(data["routes"]) == 3
    for route in data["routes"]:
        assert route["polyline"]
        assert route["distance_meters"] > 0
        assert route["duration_seconds"] > 0
        assert route["id"].startswith("route-")


def test_plan_rejects_malformed_body():
    resp = client.post("/routes/plan", json={"origin": {"lat": 1.0, "lng": 2.0}})
    assert resp.status_code == 422
```

- [ ] **Step 2: Run it to verify it fails**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"; .\.venv\Scripts\Activate.ps1
pytest tests/test_routes.py -v
```
Expected: FAIL — `ModuleNotFoundError: app.main`.

- [ ] **Step 3: Write `routers/routes.py`**

```python
from fastapi import APIRouter, Depends

from app.config import get_planner
from app.models import RoutePlanRequest, RoutePlanResponse
from app.planners.base import RoutePlanner

router = APIRouter()


@router.post("/routes/plan", response_model=RoutePlanResponse)
async def plan_routes(
    request: RoutePlanRequest,
    planner: RoutePlanner = Depends(get_planner),
) -> RoutePlanResponse:
    routes = await planner.plan(request.origin, request.destination)
    return RoutePlanResponse(routes=routes)
```

- [ ] **Step 4: Write `main.py`**

```python
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.routers import routes

app = FastAPI(title="Copilot Maps Backend")

# Permissive CORS for local development only.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(routes.router)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}
```

- [ ] **Step 5: Run all backend tests to verify they pass**

```powershell
pytest -v
```
Expected: PASS — all tests in `test_stub_planner.py` and `test_routes.py`.

- [ ] **Step 6: Run the server and hit it manually**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"; .\.venv\Scripts\Activate.ps1
uvicorn app.main:app --reload --port 8000
```
In a second terminal:
```powershell
curl.exe -X POST http://localhost:8000/routes/plan -H "Content-Type: application/json" -d "{\"origin\":{\"lat\":1.2966,\"lng\":103.7764},\"destination\":{\"lat\":1.3521,\"lng\":103.8198}}"
```
Expected: JSON with a `routes` array of 3 objects, each having `polyline`, `distance_meters`, `duration_seconds`. Stop the server with Ctrl+C.

- [ ] **Step 7: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add backend/app/routers/routes.py backend/app/main.py backend/tests/test_routes.py
git commit -m "feat(backend): POST /routes/plan + /health + CORS, with tests"
```

---

# PHASE 2 — App ↔ backend (against the stub)

### Task 6: App deps + INTERNET permission + debug cleartext config

**Files:**
- Modify: `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/debug/AndroidManifest.xml`, `android/app/src/debug/res/xml/network_security_config.xml`

- [ ] **Step 1: Add app dependencies**

In `android/app/build.gradle.kts`, inside `dependencies { }`, after the existing location/lifecycle block, add:

```kotlin
    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Polyline decoding
    implementation("com.google.maps.android:android-maps-utils:3.8.2")
```

- [ ] **Step 2: Add INTERNET permission**

In `android/app/src/main/AndroidManifest.xml`, add below the existing `<uses-feature ...>` line (still above `<application>`):

```xml
    <uses-permission android:name="android.permission.INTERNET" />
```

- [ ] **Step 3: Create the debug-only cleartext network config**

`android/app/src/debug/res/xml/network_security_config.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="true">127.0.0.1</domain>
    </domain-config>
</network-security-config>
```

- [ ] **Step 4: Create the debug manifest that applies it**

`android/app/src/debug/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:networkSecurityConfig="@xml/network_security_config" />
</manifest>
```
This merges into **debug builds only** — release builds never permit cleartext.

- [ ] **Step 5: Sync / build to verify dependencies resolve**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add android/app/build.gradle.kts android/app/src/main/AndroidManifest.xml android/app/src/debug
git commit -m "chore(app): add networking deps, INTERNET permission, debug cleartext config"
```

---

### Task 7: Network DTOs + Retrofit API + module

**Files:**
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/network/Dtos.kt`, `RoutesApi.kt`, `NetworkModule.kt`

- [ ] **Step 1: Write `Dtos.kt`**

```kotlin
package com.virtueson.copilotmaps.network

import com.squareup.moshi.Json

data class LatLngDto(
    val lat: Double,
    val lng: Double,
)

data class RoutePlanRequestDto(
    val origin: LatLngDto,
    val destination: LatLngDto,
)

data class RouteDto(
    val id: String,
    val summary: String,
    @Json(name = "distance_meters") val distanceMeters: Int,
    @Json(name = "duration_seconds") val durationSeconds: Int,
    val polyline: String,
)

data class RoutePlanResponseDto(
    val routes: List<RouteDto>,
)
```

- [ ] **Step 2: Write `RoutesApi.kt`**

```kotlin
package com.virtueson.copilotmaps.network

import retrofit2.http.Body
import retrofit2.http.POST

interface RoutesApi {
    @POST("routes/plan")
    suspend fun planRoutes(@Body body: RoutePlanRequestDto): RoutePlanResponseDto
}
```

- [ ] **Step 3: Write `NetworkModule.kt`**

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

    val routesApi: RoutesApi by lazy {
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
            .create(RoutesApi::class.java)
    }
}
```

- [ ] **Step 4: Compile to verify**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add android/app/src/main/java/com/virtueson/copilotmaps/network
git commit -m "feat(app): Retrofit DTOs, RoutesApi, NetworkModule"
```

---

### Task 8: Domain model + repository

**Files:**
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/data/GeoPoint.kt`, `Route.kt`, `RoutesRepository.kt`

- [ ] **Step 1: Write `GeoPoint.kt`**

```kotlin
package com.virtueson.copilotmaps.data

/** Framework-free coordinate so domain/ViewModel logic stays JVM-unit-testable. */
data class GeoPoint(
    val lat: Double,
    val lng: Double,
)
```

- [ ] **Step 2: Write `Route.kt`**

```kotlin
package com.virtueson.copilotmaps.data

/** A drawable route: geometry already decoded into points. */
data class Route(
    val id: String,
    val summary: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val points: List<GeoPoint>,
)

sealed interface RoutesResult {
    data class Success(val routes: List<Route>) : RoutesResult
    data class Failure(val reason: String) : RoutesResult
}
```

- [ ] **Step 3: Write `RoutesRepository.kt`**

```kotlin
package com.virtueson.copilotmaps.data

import com.google.maps.android.PolyUtil
import com.virtueson.copilotmaps.network.LatLngDto
import com.virtueson.copilotmaps.network.RoutePlanRequestDto
import com.virtueson.copilotmaps.network.RoutesApi

interface RoutesRepository {
    suspend fun planRoutes(origin: GeoPoint, destination: GeoPoint): RoutesResult
}

class DefaultRoutesRepository(
    private val api: RoutesApi,
) : RoutesRepository {
    override suspend fun planRoutes(origin: GeoPoint, destination: GeoPoint): RoutesResult {
        return try {
            val response = api.planRoutes(
                RoutePlanRequestDto(
                    origin = LatLngDto(origin.lat, origin.lng),
                    destination = LatLngDto(destination.lat, destination.lng),
                )
            )
            val routes = response.routes.map { dto ->
                Route(
                    id = dto.id,
                    summary = dto.summary,
                    distanceMeters = dto.distanceMeters,
                    durationSeconds = dto.durationSeconds,
                    points = PolyUtil.decode(dto.polyline).map { GeoPoint(it.latitude, it.longitude) },
                )
            }
            if (routes.isEmpty()) {
                RoutesResult.Failure("No route found for that destination.")
            } else {
                RoutesResult.Success(routes)
            }
        } catch (e: Exception) {
            RoutesResult.Failure("Can't reach the server — is the backend running and adb reverse set?")
        }
    }
}
```

- [ ] **Step 4: Compile to verify**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add android/app/src/main/java/com/virtueson/copilotmaps/data
git commit -m "feat(app): GeoPoint, Route domain model, RoutesRepository"
```

---

### Task 9: `RoutesState` + `RouteViewModel` (TDD)

**Files:**
- Create: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/RoutesState.kt`, `RouteViewModel.kt`
- Test: `android/app/src/test/java/com/virtueson/copilotmaps/ui/map/RouteViewModelTest.kt`

- [ ] **Step 1: Write `RoutesState.kt`**

```kotlin
package com.virtueson.copilotmaps.ui.map

import com.virtueson.copilotmaps.data.Route

sealed interface RoutesState {
    data object Idle : RoutesState
    data object Loading : RoutesState
    data class Loaded(val routes: List<Route>, val selectedId: String) : RoutesState
    data class Error(val message: String) : RoutesState
}
```

- [ ] **Step 2: Write the failing test**

`android/app/src/test/java/com/virtueson/copilotmaps/ui/map/RouteViewModelTest.kt`:
```kotlin
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
    override suspend fun planRoutes(origin: GeoPoint, destination: GeoPoint): RoutesResult = result
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
```

- [ ] **Step 3: Run it to verify it fails**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat :app:testDebugUnitTest --tests "com.virtueson.copilotmaps.ui.map.RouteViewModelTest"
```
Expected: FAIL — `RouteViewModel` unresolved.

- [ ] **Step 4: Write `RouteViewModel.kt`**

```kotlin
package com.virtueson.copilotmaps.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.virtueson.copilotmaps.data.GeoPoint
import com.virtueson.copilotmaps.data.RoutesRepository
import com.virtueson.copilotmaps.data.RoutesResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RouteViewModel(
    private val repository: RoutesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<RoutesState>(RoutesState.Idle)
    val state: StateFlow<RoutesState> = _state.asStateFlow()

    fun planRoutes(origin: GeoPoint, destination: GeoPoint) {
        _state.value = RoutesState.Loading
        viewModelScope.launch {
            _state.value = when (val result = repository.planRoutes(origin, destination)) {
                is RoutesResult.Success -> RoutesState.Loaded(result.routes, result.routes.first().id)
                is RoutesResult.Failure -> RoutesState.Error(result.reason)
            }
        }
    }

    fun selectRoute(id: String) {
        val current = _state.value
        if (current is RoutesState.Loaded) {
            _state.value = current.copy(selectedId = id)
        }
    }
}

class RouteViewModelFactory(
    private val repository: RoutesRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        RouteViewModel(repository) as T
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.virtueson.copilotmaps.ui.map.RouteViewModelTest"
```
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add android/app/src/main/java/com/virtueson/copilotmaps/ui/map/RoutesState.kt android/app/src/main/java/com/virtueson/copilotmaps/ui/map/RouteViewModel.kt android/app/src/test/java/com/virtueson/copilotmaps/ui/map/RouteViewModelTest.kt
git commit -m "feat(app): RoutesState + RouteViewModel with unit tests"
```

---

# PHASE 3 — App UI: draw routes & pick among them

### Task 10: Wire routing into `MapScreen` + on-device verification

**Files:**
- Modify: `android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt`

- [ ] **Step 1: Replace `MapScreen.kt` with the routing-enabled version**

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
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import com.virtueson.copilotmaps.data.DefaultRoutesRepository
import com.virtueson.copilotmaps.data.GeoPoint
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
    val locationState by mapViewModel.uiState.collectAsStateWithLifecycle()
    val routesState by routeViewModel.state.collectAsStateWithLifecycle()

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
                Text("Location permission is needed to show your position on the map.", textAlign = TextAlign.Center)
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

        is MapUiState.Located -> RoutingMap(
            modifier = modifier,
            origin = GeoPoint(loc.latitude, loc.longitude),
            routesState = routesState,
            onPlan = { dest -> routeViewModel.planRoutes(GeoPoint(loc.latitude, loc.longitude), dest) },
            onSelect = routeViewModel::selectRoute,
        )
    }
}

@Composable
private fun RoutingMap(
    modifier: Modifier,
    origin: GeoPoint,
    routesState: RoutesState,
    onPlan: (GeoPoint) -> Unit,
    onSelect: (String) -> Unit,
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
                    Polyline(
                        points = route.points.map { LatLng(it.lat, it.lng) },
                        color = if (selected) Color(0xFF1A73E8) else Color(0xFF9AA0A6),
                        width = if (selected) 18f else 9f,
                        zIndex = if (selected) 2f else 1f,
                        clickable = true,
                        onClick = { onSelect(route.id) },
                    )
                }
            }
        }

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

@Composable
private fun Centered(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        content()
    }
}
```

> Note: the `BottomBar` is defined as a `BoxScope` extension so it can use `align`. If the compiler objects to the inline fully-qualified receiver, move `import androidx.compose.foundation.layout.BoxScope` to the top and declare `private fun BoxScope.BottomBar(...)`. (Delete the no-op `BoxScopeBottomBarHelper`.)

- [ ] **Step 2: Compile**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "D:\Russell\Data Science Job\AI Maps\android"
.\gradlew.bat :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL. (Fix any maps-compose symbol drift, e.g. `rememberMarkerState`/`Polyline.onClick` signature, or a `Card(onClick=...)` `@OptIn(ExperimentalMaterial3Api::class)` requirement, if the version differs.)

- [ ] **Step 3: Start the backend**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"; .\.venv\Scripts\Activate.ps1
uvicorn app.main:app --reload --port 8000
```
Leave it running.

- [ ] **Step 4: Set up the USB tunnel**

In another terminal:
```powershell
cd "C:\Users\Russell\AppData\Local\Android\Sdk\platform-tools"
.\adb.exe reverse tcp:8000 tcp:8000
```
Expected: prints `8000`. (Confirms the phone's `localhost:8000` now reaches your PC.)

- [ ] **Step 5: Run on the phone and verify (against the stub)**

Run the app from Android Studio (▶). On the map (centered on your location):
- **Long-press** anywhere → a destination pin drops, and after a moment **3 polylines** appear from you to the pin, with a **bottom row of 3 cards** (summary · ETA · distance). The first route is highlighted blue.
- **Tap a different card** → the highlight (thick blue line) switches to that route.
- **Stop the backend** (Ctrl+C), long-press again → the bottom bar shows *"Can't reach the server — is the backend running and adb reverse set?"* with **Retry**. Restart the backend, tap Retry → routes appear.

✅ This is the Phase-3 payoff: real route lines on your phone, served by your backend, with zero Google dependency yet.

- [ ] **Step 6: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add android/app/src/main/java/com/virtueson/copilotmaps/ui/map/MapScreen.kt
git commit -m "feat(app): draw routes + ETA cards, long-press destination, tap-to-select"
```

---

# PHASE 4 — Real Google routes

### Task 11: Google Cloud setup + `GoogleRoutePlanner` + swap

**Files:**
- Create: `backend/app/planners/google.py`
- Modify: `backend/app/routers/routes.py` (502 mapping), `backend/.env` (git-ignored)

- [ ] **Step 1: Enable the Routes API + create a server key (Google Cloud Console)**

In the same `copilot-maps` project used in Step 1:
- **APIs & Services → Library** → search **"Routes API"** → **Enable**.
- **APIs & Services → Credentials → Create credentials → API key**. Edit it:
  - Name: `copilot-maps-routes-server`
  - **Application restrictions:** **None** (this is a *server* key called from your PC, not from the phone). *(Optionally restrict by your IP later.)*
  - **API restrictions:** **Restrict key** → check **Routes API** only → Save.
- Copy the key string.

> This is a **separate** key from the Step 1 Android Maps key. The Android key is package+SHA-restricted and only works inside the app; this server key calls the Routes web API from the backend.

- [ ] **Step 2: Put the key in `backend/.env` (git-ignored)**

Create `backend/.env`:
```
ROUTE_PLANNER=google
GOOGLE_ROUTES_API_KEY=PASTE_YOUR_SERVER_KEY_HERE
```

- [ ] **Step 3: Write `google.py`**

```python
import httpx

from app.models import LatLng, Route

_COMPUTE_ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
_FIELD_MASK = (
    "routes.polyline.encodedPolyline,routes.duration,"
    "routes.distanceMeters,routes.description"
)


class GoogleRoutePlanner:
    def __init__(self, api_key: str):
        self._api_key = api_key

    async def plan(self, origin: LatLng, destination: LatLng) -> list[Route]:
        body = {
            "origin": {"location": {"latLng": {"latitude": origin.lat, "longitude": origin.lng}}},
            "destination": {"location": {"latLng": {"latitude": destination.lat, "longitude": destination.lng}}},
            "travelMode": "DRIVE",
            "routingPreference": "TRAFFIC_AWARE",
            "computeAlternativeRoutes": True,
        }
        headers = {
            "Content-Type": "application/json",
            "X-Goog-Api-Key": self._api_key,
            "X-Goog-FieldMask": _FIELD_MASK,
        }
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.post(_COMPUTE_ROUTES_URL, json=body, headers=headers)
        resp.raise_for_status()

        routes: list[Route] = []
        for i, item in enumerate(resp.json().get("routes", [])[:3]):
            duration = item.get("duration", "0s")
            seconds = int(duration[:-1]) if duration.endswith("s") else 0
            routes.append(
                Route(
                    id=f"route-{i}",
                    summary=item.get("description") or f"Route {i + 1}",
                    distance_meters=int(item.get("distanceMeters", 0)),
                    duration_seconds=seconds,
                    polyline=item["polyline"]["encodedPolyline"],
                )
            )
        return routes
```

- [ ] **Step 4: Map upstream failures to 502 in the router**

Replace `backend/app/routers/routes.py` with:
```python
import httpx
from fastapi import APIRouter, Depends, HTTPException

from app.config import get_planner
from app.models import RoutePlanRequest, RoutePlanResponse
from app.planners.base import RoutePlanner

router = APIRouter()


@router.post("/routes/plan", response_model=RoutePlanResponse)
async def plan_routes(
    request: RoutePlanRequest,
    planner: RoutePlanner = Depends(get_planner),
) -> RoutePlanResponse:
    try:
        routes = await planner.plan(request.origin, request.destination)
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=f"Route provider error: {exc}") from exc
    return RoutePlanResponse(routes=routes)
```

- [ ] **Step 5: Verify existing tests still pass (stub path unaffected)**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"; .\.venv\Scripts\Activate.ps1
$env:ROUTE_PLANNER=""   # ensure tests use default stub, not your .env
pytest -v
```
Expected: PASS. (Tests construct `TestClient(app)` and the default planner is the stub; they don't read `.env` values for `route_planner` unless set in the process env.)

- [ ] **Step 6: Restart the backend with the real planner and verify on device**

```powershell
cd "D:\Russell\Data Science Job\AI Maps\backend"; .\.venv\Scripts\Activate.ps1
uvicorn app.main:app --reload --port 8000
```
Ensure `adb reverse tcp:8000 tcp:8000` is still set (re-run it if you reconnected the phone). In the app, long-press a **real reachable destination** (somewhere drivable from you).

Expected: **real road-following routes** (they bend along actual streets, unlike the stub's straight-ish lines), with realistic, traffic-aware ETAs and distances. Tapping cards still switches the highlight.

> If you get the bottom-bar server error: check the uvicorn terminal for the upstream message. Common causes: Routes API not enabled, key typo, or billing not active on the project.

- [ ] **Step 7: Commit**

```bash
cd "D:\Russell\Data Science Job\AI Maps"
git add backend/app/planners/google.py backend/app/routers/routes.py
git commit -m "feat(backend): GoogleRoutePlanner + 502 mapping; real routes end-to-end"
```

---

## Definition of Done (verify all)

- [ ] `POST /routes/plan` returns 3 routes (stub, then real Google).
- [ ] On the phone: long-press → 3 polylines + bottom ETA/distance cards between your location and the destination.
- [ ] Tapping a card highlights that route.
- [ ] Server-unreachable shows the explicit error + Retry; no crash.
- [ ] `pytest` (backend) and `gradlew :app:testDebugUnitTest` (app) both pass.
- [ ] `git status` clean; `backend/.env` and `local.properties` never committed.

---

## Notes for later steps

- **Step 2b (traffic coloring):** request `routes.travelAdvisory.speedReadingIntervals` in the field mask; split each polyline into colored segments by speed bucket.
- **Step 3 (Places):** replace long-press with place search for destinations.
- **Step 5 (live nav):** extend the contract additively with per-route `steps` (turn-by-turn) and add a re-routing endpoint reusing the `Route` shape.
- **Pre-launch:** deploy the backend (cloud HTTPS) and switch the app's base URL away from `localhost`.
```
