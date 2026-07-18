# Route-Ahead Place Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Filter place-search results so the Copilot only surfaces places ahead of the driver and on their route, nearest-ahead-first.

**Architecture:** A new pure geometry module (`app/places/route_geometry.py`) decodes the route polyline, projects the driver and each candidate place onto it, and keeps only places that are ahead (along-track) and within a ~1 km corridor (cross-track), sorted nearest-ahead-first. It is wired into `search_with_fallback` as a single post-filter step on the along-route results; when it empties the list the existing nearby fallback fires.

**Tech Stack:** Python 3, FastAPI, Pydantic v2, the `polyline` library (already a dependency, `polyline>=2.0`), pytest.

## Global Constraints

- Tests run against stubs and must never hit live Google/LLM: `cd backend && ./.venv/Scripts/python.exe -m pytest -q`.
- The geometry module is a **pure decider**: no `httpx`, no provider imports, no network — only math over `app.models` `LatLng`/`Place`. Fully unit-testable.
- No new dependencies (`polyline` is already present).
- No provider, router, prompt, or Android changes.
- Corridor width is a module constant (`CORRIDOR_METERS = 1000`); no env-var tuning in this work.
- Never stage `.env`, `local.properties`, or scratch files (`test_output.txt`, screenshots, `.superpowers/`). Stage only the exact files each commit lists.

---

### Task 1: Pure geometry module `route_geometry.py`

**Files:**
- Create: `backend/app/places/route_geometry.py`
- Test: `backend/tests/test_route_geometry.py`

**Interfaces:**
- Consumes: `app.models.LatLng`, `app.models.Place`; the `polyline` library.
- Produces:
  - `CORRIDOR_METERS: float` (= 1000.0)
  - `AHEAD_GRACE_METERS: float` (= 50.0)
  - `filter_ahead_on_route(origin: LatLng, encoded_polyline: str, places: list[Place], corridor_m: float = CORRIDOR_METERS) -> list[Place]` — returns the subset of `places` that are ahead of `origin` along the route and within `corridor_m` off it, sorted nearest-ahead-first. Returns `places` unchanged when the polyline decodes to fewer than 2 points.

**Geometry background (for the implementer):** Two distances matter. *Along-track* = how far along the route the nearest point to a place is (meters from the route start). *Cross-track* = the perpendicular distance from the place to the route line. A place is "ahead" if its along-track distance is >= the driver's own along-track distance (minus a small GPS grace). It is "on route" if its cross-track distance <= the corridor. Distances use a local equirectangular approximation: convert lat/lng to meters with `x = lng * 111320 * cos(lat_ref)`, `y = lat * 111320`, using the path's first latitude as `lat_ref`. Accurate to well under a meter at city scale.

- [ ] **Step 1: Write the failing test**

Create `backend/tests/test_route_geometry.py`:

```python
import polyline

from app.models import LatLng, Place
from app.places.route_geometry import filter_ahead_on_route

# Straight west->east line along the equator: (0,0) -> (0,0.1).
# At the equator 0.1 deg lng ~= 11.1 km; 0.001 deg ~= 111 m; cos(0) = 1.
ROUTE = polyline.encode([(0.0, 0.0), (0.0, 0.05), (0.0, 0.1)])
ORIGIN = LatLng(lat=0.0, lng=0.02)  # ~2.2 km along the route


def _place(name, lat, lng):
    return Place(id=name, name=name, lat=lat, lng=lng)


def _names(places):
    return [p.name for p in places]


def test_drops_place_behind_origin():
    behind = _place("Behind", 0.0, 0.01)  # ~1.1 km along, before the origin
    assert filter_ahead_on_route(ORIGIN, ROUTE, [behind]) == []


def test_keeps_place_ahead_on_route():
    ahead = _place("Ahead", 0.0, 0.05)  # ~5.6 km along, on the line
    assert _names(filter_ahead_on_route(ORIGIN, ROUTE, [ahead])) == ["Ahead"]


def test_drops_place_far_off_route():
    off = _place("Off", 0.02, 0.06)  # ahead but ~2.2 km to the side (> 1 km)
    assert filter_ahead_on_route(ORIGIN, ROUTE, [off]) == []


def test_keeps_place_just_inside_corridor():
    near = _place("Near", 0.005, 0.04)  # ahead, ~557 m to the side (< 1 km)
    assert _names(filter_ahead_on_route(ORIGIN, ROUTE, [near])) == ["Near"]


def test_sorts_nearest_ahead_first():
    far = _place("Far", 0.0, 0.08)
    near = _place("Near", 0.0, 0.04)
    result = filter_ahead_on_route(ORIGIN, ROUTE, [far, near])
    assert _names(result) == ["Near", "Far"]


def test_keeps_place_at_origin_within_grace():
    here = _place("Here", 0.0, 0.02)  # exactly at the origin's progress point
    assert _names(filter_ahead_on_route(ORIGIN, ROUTE, [here])) == ["Here"]


def test_passthrough_when_polyline_undecodable():
    # A malformed polyline must never crash a search; it decodes to nothing,
    # so filtering is skipped and the input is returned unchanged.
    p = _place("Anything", 9.9, 9.9)
    assert _names(filter_ahead_on_route(ORIGIN, "abc", [p])) == ["Anything"]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./.venv/Scripts/python.exe -m pytest tests/test_route_geometry.py -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.places.route_geometry'`.

- [ ] **Step 3: Write minimal implementation**

Create `backend/app/places/route_geometry.py`:

```python
"""Filter place-search results to those ahead of the driver, on-route.

Pure geometry: no network, no provider imports. Google's "search along route"
returns hits anywhere along the whole polyline with no notion of the driver's
current position, so a place can be behind them or well off the route.
filter_ahead_on_route() drops those and orders the rest nearest-ahead-first.

Distances use a local equirectangular approximation (lat/lng offsets scaled to
meters via cos(lat)); accurate to well under a meter at city scale.
"""
import math

import polyline as _polyline

from app.models import LatLng, Place

# Max cross-track distance (meters off the route line) a place may have and
# still count as "on my route". ~1 km => ~2 km round-trip detour.
CORRIDOR_METERS = 1000.0
# Along-track grace so GPS jitter doesn't drop the place the driver is
# currently sitting on.
AHEAD_GRACE_METERS = 50.0

_METERS_PER_DEG = 111_320.0


def _decode(encoded: str) -> list[tuple[float, float]]:
    """Decode an encoded polyline to [(lat, lng), ...].

    A malformed string must never crash a search, so any decode error yields an
    empty path (callers treat < 2 points as "can't filter").
    """
    try:
        return _polyline.decode(encoded)
    except Exception:
        return []


def _project(
    lat: float, lng: float, path: list[tuple[float, float]]
) -> tuple[float, float]:
    """Project one point onto the path.

    Returns (along_m, cross_m): meters from the path start to the nearest point
    on the path, and the perpendicular distance from the point to that nearest
    point.
    """
    scale = math.cos(math.radians(path[0][0]))

    def to_xy(la: float, ln: float) -> tuple[float, float]:
        return (ln * _METERS_PER_DEG * scale, la * _METERS_PER_DEG)

    px, py = to_xy(lat, lng)
    best_cross = math.inf
    best_along = 0.0
    cum = 0.0
    for (alat, alng), (blat, blng) in zip(path, path[1:]):
        ax, ay = to_xy(alat, alng)
        bx, by = to_xy(blat, blng)
        vx, vy = bx - ax, by - ay
        seg_len_sq = vx * vx + vy * vy
        if seg_len_sq == 0.0:
            continue
        seg_len = math.sqrt(seg_len_sq)
        t = ((px - ax) * vx + (py - ay) * vy) / seg_len_sq
        t = max(0.0, min(1.0, t))
        projx, projy = ax + t * vx, ay + t * vy
        cross = math.hypot(px - projx, py - projy)
        if cross < best_cross:
            best_cross = cross
            best_along = cum + t * seg_len
        cum += seg_len
    return best_along, best_cross


def filter_ahead_on_route(
    origin: LatLng,
    encoded_polyline: str,
    places: list[Place],
    corridor_m: float = CORRIDOR_METERS,
) -> list[Place]:
    """Keep only places ahead of the driver and within the route corridor.

    Ordered nearest-ahead-first. If the polyline can't be decoded to a line
    (< 2 points), places are returned unchanged.
    """
    path = _decode(encoded_polyline)
    if len(path) < 2:
        return places
    d0, _ = _project(origin.lat, origin.lng, path)
    kept: list[tuple[float, Place]] = []
    for p in places:
        along, cross = _project(p.lat, p.lng, path)
        if along >= d0 - AHEAD_GRACE_METERS and cross <= corridor_m:
            kept.append((along, p))
    kept.sort(key=lambda ap: ap[0])
    return [p for _, p in kept]
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./.venv/Scripts/python.exe -m pytest tests/test_route_geometry.py -q`
Expected: PASS — 7 passed.

- [ ] **Step 5: Commit**

```bash
git add backend/app/places/route_geometry.py backend/tests/test_route_geometry.py
git commit -m "feat(places): pure geometry filter for ahead-on-route places"
```

---

### Task 2: Wire the filter into `search_with_fallback`

**Files:**
- Modify: `backend/app/places/search.py`
- Test: `backend/tests/test_copilot_tools.py`

**Interfaces:**
- Consumes: `filter_ahead_on_route` from Task 1.
- Produces: no signature change. `search_with_fallback(provider, query, origin, polyline)` still returns `tuple[str, list[Place]]`, but the along-route branch now returns only ahead/on-route places (nearest-ahead-first), and an emptied filter result triggers the existing nearby fallback (`mode == "nearby"`).

**Why the existing tests change:** `tests/test_copilot_tools.py` currently builds places at `(1.0, 2.0)` with `selected_route_polyline="abc"`. Once the filter is wired, those coordinates would be judged against real geometry. Step 1 re-bases those fixtures onto a real route (origin at the route start, hits on the line ahead) so they still exercise the along-route path, and adds one new test that fails until the wiring exists.

- [ ] **Step 1: Update the test fixtures and add the failing test**

Edit `backend/tests/test_copilot_tools.py`. Replace the import block, `_place`, and the two context-building tests, and add the new behind-filter test. The full file should read:

```python
import asyncio

import polyline

from app.copilot.tools import SEARCH_PLACES_TOOL, execute_search_places
from app.models import CopilotContext, LatLng, Place

# Straight west->east line along the equator; origin sits at its start so any
# hit further east (larger lng) is "ahead on route".
ROUTE = polyline.encode([(0.0, 0.0), (0.0, 0.1)])


class _FakeProvider:
    def __init__(self, along, near):
        self._along = along
        self._near = near

    async def along_route(self, query, polyline):
        return self._along

    async def nearby(self, query, origin):
        return self._near


def _place(name, lat=0.0, lng=0.05):
    # Default coordinates sit on ROUTE, ahead of an origin at (0.0, 0.0).
    return Place(id=name, name=name, lat=lat, lng=lng, rating=4.5)


def _ctx():
    return CopilotContext(origin=LatLng(lat=0.0, lng=0.0), selected_route_polyline=ROUTE)


def test_tool_schema_shape():
    assert SEARCH_PLACES_TOOL["name"] == "search_places"
    assert "query" in SEARCH_PLACES_TOOL["input_schema"]["properties"]


def test_executor_uses_route_when_polyline_present():
    provider = _FakeProvider(along=[_place("OnRoute")], near=[_place("Nearby")])

    result = asyncio.run(execute_search_places("gas station", _ctx(), provider))

    assert "on the route" in result
    assert "OnRoute" in result


def test_executor_falls_back_to_nearby():
    provider = _FakeProvider(along=[], near=[_place("Nearby")])

    result = asyncio.run(execute_search_places("gas station", _ctx(), provider))

    assert "nearby" in result
    assert "Nearby" in result


def test_executor_drops_places_behind_the_driver():
    """A place already passed must not appear; one ahead on-route must."""
    ctx = CopilotContext(
        origin=LatLng(lat=0.0, lng=0.02), selected_route_polyline=ROUTE
    )
    behind = _place("Behind", lat=0.0, lng=0.01)  # ~1.1 km, before the driver
    ahead = _place("Ahead", lat=0.0, lng=0.05)  # ~5.6 km, ahead on route
    provider = _FakeProvider(along=[behind, ahead], near=[])

    result = asyncio.run(execute_search_places("gas station", ctx, provider))

    assert "Ahead" in result
    assert "Behind" not in result


def test_truncated_results_say_how_many_are_shown():
    """The header must never claim more places than the list actually shows."""
    provider = _FakeProvider(along=[_place(f"P{i}") for i in range(20)], near=[])

    result = asyncio.run(execute_search_places("gas station", _ctx(), provider))
    listed = [line for line in result.splitlines() if line.startswith("- ")]

    assert len(listed) == 5
    assert result.startswith("Found 20 places on the route. Top 5:")
    assert "P4" in result and "P5" not in result


def test_untruncated_results_report_the_real_count():
    provider = _FakeProvider(along=[_place("A"), _place("B")], near=[])

    result = asyncio.run(execute_search_places("gas station", _ctx(), provider))

    assert result.startswith("Found 2 places on the route. Top 2:")
```

- [ ] **Step 2: Run tests to verify the new one fails**

Run: `cd backend && ./.venv/Scripts/python.exe -m pytest tests/test_copilot_tools.py -q`
Expected: FAIL — `test_executor_drops_places_behind_the_driver` fails on `assert "Behind" not in result` (the filter is not wired yet, so the behind place still appears). The other six tests pass.

- [ ] **Step 3: Wire the filter into `search_with_fallback`**

Edit `backend/app/places/search.py` to its full new contents (the only change from the current file is the new `route_geometry` import and the single `filter_ahead_on_route` line inside the `if polyline:` branch):

```python
from app.models import LatLng, Place
from app.places.base import PlacesProvider
from app.places.route_geometry import filter_ahead_on_route


async def search_with_fallback(
    provider: PlacesProvider,
    query: str,
    origin: LatLng,
    polyline: str | None,
) -> tuple[str, list[Place]]:
    """Along-route search, falling back to nearby. Returns (mode, places).

    Along-route hits are filtered to those ahead of the driver and within the
    route corridor (nearest-ahead-first). If that leaves nothing, the nearby
    fallback fires just as it does when the route search itself finds nothing.
    """
    places: list[Place] = []
    mode = "nearby"
    if polyline:
        places = await provider.along_route(query, polyline)
        places = filter_ahead_on_route(origin, polyline, places)
        mode = "along_route"
    if not places:
        places = await provider.nearby(query, origin)
        mode = "nearby"
    return mode, places
```

- [ ] **Step 4: Run the copilot-tools tests to verify they pass**

Run: `cd backend && ./.venv/Scripts/python.exe -m pytest tests/test_copilot_tools.py -q`
Expected: PASS — 7 passed.

- [ ] **Step 5: Run the full backend suite**

Run: `cd backend && ./.venv/Scripts/python.exe -m pytest -q`
Expected: PASS — all tests green (confirms no other caller of `search_with_fallback` regressed).

- [ ] **Step 6: Commit**

```bash
git add backend/app/places/search.py backend/tests/test_copilot_tools.py
git commit -m "feat(places): filter along-route search to ahead-on-route hits"
```

---

## Notes for the reviewer

- After both tasks: the along-route path returns at most the genuine nearest-ahead places, sorted, so `_format_places`' top-5 are meaningful. No change was needed in `tools.py` or `prompt.py`.
- The corridor (1 km) and grace (50 m) are named constants in `route_geometry.py`; adjust there if the on-road feel needs tuning.
