# Copilot Maps — Step 3: Places — Design Spec

**Date:** 2026-06-01
**Status:** Approved (design), pending implementation plan
**Author:** Russell (with Claude)

---

## Context

Steps 1, 2, and 2b are complete: the app shows live GPS, plans up to 3 alternate
routes with ETAs, and colors the selected route by traffic. Backend has one POST
endpoint (`/routes/plan`) plus `/health`.

**This spec covers Step 3: Places** — a `POST /places/search` endpoint and on-map
markers for gas stations / restaurants, triggered by category buttons. **No AI yet**
(that's Step 4); everything is deterministic, button-driven, and testable. The
`/places/search` endpoint becomes a *tool* the Step 4 copilot calls.

---

## Goal & Scope

**Goal:** From the map, tap a **Gas** or **Food** button to see places **along the
selected route**, falling back to **nearest around you** when there's no route or no
results along it.

**Scope decisions (agreed):**

- **Behavior C:** category buttons search *along the selected route* when one is
  selected; *around current location* when no route; and **auto-fall back to nearby**
  when an along-route search returns empty. One button per category — context-aware.
- **Categories:** a fixed pair — **Gas** (`"gas station"`) and **Food**
  (`"restaurant"`).
- **One endpoint, backend-side fallback** (Approach 1): `POST /places/search` takes
  `{query, origin, polyline?}`; the handler tries along-route then nearby and returns
  the `mode` used. The app makes a single call.

**Definition of Done:**

1. `POST /places/search` returns places (stub, then real Google Places API), with the
   along-route → nearby fallback.
2. On the phone: tap Gas/Food → markers appear (along the selected route, or nearby);
   the *"None on your route — showing nearest"* note shows on fallback; Clear removes
   markers.
3. Marker tap shows name + rating/address.
4. Server-unreachable / empty results handled gracefully — no crash.
5. Backend pytest (incl. the fallback test) and app `PlacesViewModel` unit tests pass.

---

## Verified Google API facts

Google **Places API (New) Text Search** (`POST
https://places.googleapis.com/v1/places:searchText`) does both modes with one call
shape, switching only the bias parameter:

- **Along route:** `searchAlongRouteParameters.polyline.encodedPolyline` (biases to
  the path, ranked by detour).
- **Nearby:** `locationBias.circle` (center + radius).
- `textQuery` = the category text. Field mask required.

Sources:
- https://developers.google.com/maps/documentation/places/web-service/search-along-route
- https://developers.google.com/maps/documentation/places/web-service/text-search

---

## The Contract (`POST /places/search`)

**Request:**
```json
{
  "query": "gas station",
  "origin": { "lat": 1.2966, "lng": 103.7764 },
  "polyline": "yvw~Fhi}uM...encoded..."
}
```
- `query` — `"gas station"` | `"restaurant"` (app maps Gas/Food buttons to these).
- `origin` — current location (always sent; nearby center + fallback center).
- `polyline` — selected route's encoded polyline, or null/omitted when no route.

**Response:**
```json
{
  "mode": "along_route",
  "places": [
    { "id": "ChIJ...", "name": "Shell Beach Road",
      "lat": 1.3012, "lng": 103.7841,
      "address": "12 Beach Rd", "rating": 4.2 }
  ]
}
```
- `mode` — `"along_route"` | `"nearby"`. App shows the *"showing nearest"* note when
  it sent a polyline but got `"nearby"` back.
- `places[]` — `{id, name, lat, lng, address?, rating?}` (address/rating optional).

**Pydantic:**
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
    mode: str          # "along_route" | "nearby"
    places: list[Place]
```

---

## Backend Design

```
backend/app/
  models.py              # + Place, PlacesSearchRequest, PlacesSearchResponse
  places/
    __init__.py
    base.py              # PlacesProvider protocol
    stub.py              # StubPlacesProvider (offline)
    google.py            # GooglePlacesProvider (Places API New)
  routers/places.py      # POST /places/search (fallback orchestrator)
  config.py              # + places_provider, google_places_api_key, get_places_provider()
  main.py                # register places router
```

- **`PlacesProvider` protocol:**
  ```python
  class PlacesProvider(Protocol):
      async def along_route(self, query: str, polyline: str) -> list[Place]: ...
      async def nearby(self, query: str, origin: LatLng) -> list[Place]: ...
  ```
- **`StubPlacesProvider`:** `nearby` → ~3 fake places offset from `origin`;
  `along_route` → decode the polyline, ~3 fake places near its midpoint. Names vary by
  `query`. Offline; makes the fallback testable.
- **`GooglePlacesProvider`:** `places:searchText` with field mask
  `places.id,places.displayName,places.location,places.formattedAddress,places.rating`.
  Body uses `searchAlongRouteParameters` (along route) or `locationBias.circle`
  (radius 5000 m, nearby). Maps `places[]` → `Place` (`displayName.text` → name,
  `location.latitude/longitude` → lat/lng).
- **`config.py`:** `places_provider` (`"stub"`|`"google"`, default `"stub"`) +
  `google_places_api_key`; `get_places_provider()` factory.
- **`routers/places.py`:** fallback handler —
  ```python
  places, mode = [], "nearby"
  if request.polyline:
      places = await provider.along_route(request.query, request.polyline)
      mode = "along_route"
  if not places:
      places = await provider.nearby(request.query, request.origin)
      mode = "nearby"
  return PlacesSearchResponse(mode=mode, places=places)
  ```
  `httpx.HTTPError` → 502 (same as `/routes/plan`).

**Cloud setup (build task):** enable **Places API (New)** in the `copilot-maps`
project; allow it on the existing server key (add "Places API (New)" to that key's API
restrictions); put the key in `.env` as `GOOGLE_PLACES_API_KEY` (same string as the
Routes key is fine) and set `PLACES_PROVIDER=google`.

---

## App Design

```
android/app/src/main/java/com/virtueson/copilotmaps/
  network/   # + PlaceDto, PlacesSearch*Dto, PlacesApi (shares NetworkModule Retrofit)
  data/
    Place.kt            # domain Place + PlacesResult
    PlacesRepository.kt # searchPlaces(query, origin, polyline?) -> PlacesResult(places, mode)
    Route.kt            # + polyline: String (kept from DTO for along-route search)
  ui/map/
    PlacesState.kt      # Idle / Loading / Loaded(places, fellBackToNearby) / Error
    PlacesViewModel.kt  # search(category, origin, routePolyline?), clear()
    MapScreen.kt        # + Gas/Food/Clear buttons, place markers, "nearest" note
```

- **Domain:** `Place(id, name, location: GeoPoint, address: String?, rating: Double?)`;
  `PlacesResult` success/failure. `Route` keeps its encoded `polyline`.
- **`PlacesViewModel`:** `PlaceCategory` enum (`GAS → "gas station"`,
  `FOOD → "restaurant"`); `search(category, origin, routePolyline?)` →
  `PlacesRepository`; `clear()` → `Idle`. `PlacesState.Loaded.fellBackToNearby` is true
  iff a polyline was sent and `mode == "nearby"`.
- **`MapScreen`:**
  - Top **button row**: Gas, Food, Clear. Gas/Food call `search` with `origin` =
    current location and `routePolyline` = the selected route's `polyline` if
    `RoutesState.Loaded`, else null. Clear → `placesViewModel.clear()`.
  - **Markers:** one per place (`title = name`, `snippet = "★ rating · address"`), a
    distinct hue (orange) vs. the red destination pin. Tap → info window.
  - **Note banner** when `fellBackToNearby`; spinner while `Loading`; message on
    `Error` / empty.
  - Layering: blue dot + route polylines (traffic) + destination pin + place markers;
    bottom ETA cards; top category buttons.

A **separate `PlacesViewModel`/`PlacesState`** alongside `MapViewModel` (location) and
`RouteViewModel` (routing) — three focused holders on one screen.

---

## Error Handling

- **Backend:** 422 (bad body), 502 (Google error), 200 + empty list (no results).
- **App:** `PlacesState.Error` with retry for unreachable/timeout; "No gas/restaurants
  found nearby" for empty; never crashes.

---

## Testing

- **Backend pytest** (fake provider via dependency override):
  - polyline + along-route results → `mode == "along_route"`.
  - no polyline → `mode == "nearby"`.
  - along-route empty + nearby has results → `mode == "nearby"` (fallback / C-behavior).
  - malformed body → 422.
- **App unit tests** (`PlacesViewModel`, fake repository):
  - success → `Loaded`; `fellBackToNearby` true only when polyline sent and mode
    `"nearby"`; failure → `Error`; `PlaceCategory.GAS.query == "gas station"`.
- **On-device:** route + Gas → along-route markers; route through empty area → note +
  nearby markers; no route + Food → nearby; Clear → markers gone; marker tap → info.

---

## Out of Scope

- Natural-language place queries / the copilot agent (Step 4 — this endpoint is its
  tool).
- More categories beyond Gas/Food, filters (open-now, price), sorting UI.
- Place details beyond name/location/address/rating (no photos, hours, phone).
- Adding a place as a waypoint / re-routing through it (later).

---

## Next Step

Proceed to the **writing-plans** skill for the implementation plan.
