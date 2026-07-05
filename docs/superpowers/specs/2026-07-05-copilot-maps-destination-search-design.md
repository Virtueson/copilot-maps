# Destination Search (typed place search → navigate)

**Date:** 2026-07-05
**Status:** Design approved, pending spec review
**Depends on:** Places (`/places/search` + `PlacesRepository`), Routing (`RouteViewModel.planRoutes`),
navigation/HUD (5c) — search must hide while nav is active.

## Goal

Let the user type a place name (e.g. "starbucks tangerang", "cheap sushi near me"),
see a short list of matches, tap one, and have it become the destination with routes
planned — instead of only long-pressing the map. Closes the biggest everyday-usability
gap. Reuses Google `searchText` (already behind `/places/search`); the only backend
change is enriching each result with price and open-now.

Personal-use app; low API volume (well inside Google's free allowance).

## Decisions (from brainstorm)

- **Interaction:** type + submit → results list (NOT autocomplete). Google `searchText`
  is already typo-tolerant, so submit-search handles messy input; autocomplete is
  deferred.
- **Result row:** name · ★rating · price ($–$$$$) · "Open now" · distance · address.
  List order = Google **relevance** (default), distance shown per row.
- **Tap a result** sets the destination and plans the route via the existing flow.
- **Camera auto-fit** to origin+destination on selection (so far-away results are
  visible). Also satisfies the long-parked camera-auto-fit wish.
- **Backend:** reuse `/places/search` with `query` + `origin`, **no polyline**
  (→ text search biased near the user; a city in the query steers it elsewhere).
  Add `price_level` + `open_now` to the `Place` model + field mask.

## Architecture

```
SearchBar (MapScreen)  --query,submit-->  SearchViewModel
     ▲                                        │ repository.searchPlaces(query, origin, polyline=null)
     │ results                                ▼
Results list (LazyColumn) <---- SearchUiState (query, results, loading, error)
     │ tap(place)
     ▼
destination = place.location ; onPlan(place.location)  ──►  RouteViewModel.planRoutes(origin, dest)
     └─ camera animate to LatLngBounds(origin, dest)          └─ existing RouteCards + Start
```

### 1. Backend (small, additive — reuses `searchText`)

- **`app/models.py` `Place`**: add `price_level: str | None = None` and
  `open_now: bool | None = None`.
- **`app/places/google.py`**: extend `_FIELD_MASK` with
  `places.priceLevel` and `places.currentOpeningHours.openNow`; in `_to_place`, set
  `price_level=p.get("priceLevel")` and
  `open_now=p.get("currentOpeningHours", {}).get("openNow")`. `priceLevel` comes back
  as a Google enum string (e.g. `PRICE_LEVEL_INEXPENSIVE`).
- **`app/places/stub.py`**: emit sample `price_level` / `open_now` so offline/tests work.
- No new endpoint, no request-shape change. Destination search sends `query` + `origin`
  and omits `polyline`, which the existing handler treats as a nearby/text search.

### 2. App data layer

- **`data/Place.kt`**: add `priceLevel: String? = null`, `openNow: Boolean? = null`.
- **`data/PlacesDto`/`PlacesRepository`**: add the two DTO fields and map them through
  (alongside the existing `address`/`rating` mapping). Existing
  `searchPlaces(query, origin, routePolyline)` is reused unchanged.

### 3. App: `SearchViewModel` (new — separate from the gas/food `PlacesViewModel`)

Kept separate so along-route markers and destination search never share state.

```kotlin
data class SearchUiState(
    val query: String = "",
    val results: List<Place> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val searched: Boolean = false,   // distinguishes "no search yet" from "no results"
)

class SearchViewModel(private val repository: PlacesRepository) : ViewModel() {
    val state: StateFlow<SearchUiState>
    fun onQueryChange(text: String)          // updates query only
    fun submit(origin: GeoPoint)             // repository.searchPlaces(query, origin, null)
    fun clear()                              // back to empty SearchUiState
}
```
`submit` on a blank query is a no-op. Success → `results` (+ `searched = true`,
`loading = false`); failure → `error`.

### 4. App: UI in `MapScreen`

- A **search bar** pinned at the top (`OutlinedTextField`, status-bar inset), with a
  leading 🔍 and a trailing ✕ (clear) when non-empty; IME "Search" action calls
  `submit(origin)`. Coexists with the existing Gas/Food/Clear controls.
- When `state.results` is non-empty (or `searched && empty`), a **results panel**
  (`Card` containing a `LazyColumn`) overlays the map below the bar. Each row renders
  **name · ★rating · price · Open now · distance · address**; distance =
  `haversineMeters(origin, place.location)` formatted (reuse the existing distance
  formatter). `loading` → a small progress indicator; `error` → message + Retry;
  `searched && results empty` → "No places found".
- **Row tap** → `destination = place.location`; `onPlan(place.location)`;
  `searchViewModel.clear()`; the destination marker drops (existing marker state).
- **Camera** → animate `cameraPositionState` to
  `LatLngBounds` of {origin, destination} with padding, so both are visible.
- The whole search UI is **hidden while navigation is active** (like the other browse UI).
- Price enum → symbol mapping (pure helper), using Google's exact enum strings:
  `PRICE_LEVEL_INEXPENSIVE → "$"`, `PRICE_LEVEL_MODERATE → "$$"`,
  `PRICE_LEVEL_EXPENSIVE → "$$$"`, `PRICE_LEVEL_VERY_EXPENSIVE → "$$$$"`;
  `PRICE_LEVEL_FREE`, `PRICE_LEVEL_UNSPECIFIED`, unknown, or null → "" (omit).

### 5. Data flow

```
type → onQueryChange(text)
IME search / 🔍 → submit(origin) → searchPlaces(query, origin, null)
  → SearchUiState.results → results panel
tap(place) → destination = place.location ; onPlan(place.location) → planRoutes
           → RouteCards + Start ; clear() ; camera fits origin+destination
```

## Error handling / edge cases

- **Blank query** → `submit` no-op (no API call).
- **No matches** → `searched && results.isEmpty()` → "No places found."
- **Network / provider error** → `PlacesResult.Failure.reason` → error row + Retry
  (backend already maps provider errors to HTTP 502).
- **Far destination** → camera auto-fit frames origin+destination so the route is visible.
- **Missing price/open-now** on a result → simply omit those bits (nullable fields).
- **Search during navigation** → search UI hidden; no interference with active guidance.

## Testing

- **Backend** (pytest): stub emits `price_level`/`open_now`; a test asserts they appear
  in the `/places/search` response. Google `_to_place` mapping covered by a parse test
  feeding a sample `searchText` place dict (following the existing places test pattern).
- **App** (`SearchViewModelTest`, fake repository):
  - `submit` with results → `state.results` populated, `loading=false`, `searched=true`.
  - `submit` failure → `state.error` set, results empty.
  - blank query `submit` → repository NOT called, state unchanged.
  - `clear()` → back to empty `SearchUiState`.
- **Pure price-symbol helper** unit test: each enum → `$`…`$$$$`, null/free → "".

## Out of scope (deferred)

- Autocomplete-as-you-type (would add Places Autocomplete + Place Details + session
  tokens + backend endpoints).
- Favorites / recent destinations; distance-sort toggle (`rankPreference=DISTANCE`);
  photos; voice search.
- Copilot "take me there" (4c) — this feature builds the reusable *set-destination-from-
  a-Place* path that 4c will later reuse.

## Definition of done

- Backend `Place` carries `price_level`/`open_now`; Google field mask + `_to_place`
  updated; stub emits them; pytest green.
- `SearchViewModel` implemented + unit-tested; `Place`/DTO carry the new fields.
- `MapScreen` has a top search bar + results panel; tapping a result sets the
  destination, plans the route, clears search, and the camera fits origin+destination;
  search UI hidden during navigation.
- Verified on the Pixel-class device: searching "starbucks tangerang" from Jakarta
  returns a list; tapping one plans a route to it and frames it on the map.
