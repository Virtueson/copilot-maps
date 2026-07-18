# Design: Filter place results to "ahead on my route"

**Date:** 2026-07-18
**Status:** Approved (pending spec review)

## Problem

When a route is set and the driver asks "what's the nearest gas?", the Copilot
sometimes surfaces a place that is behind them or one that sits well off the
route (a large detour). Google's "search along route" returns hits anywhere
along the *entire* polyline — origin to destination — ranked by a blend of
relevance and proximity, with no notion of where the driver currently is. The
top 5 shown to the model are simply Google's order (`tools.py::_format_places`
takes `places[:5]`), so a behind-you or far-off-route place can easily appear.

Two independent distances matter here:

- **Along-track** — how far forward/backward a place is along the route relative
  to the driver's current position. Behind = already passed.
- **Cross-track** — how far a place is off to the side of the route line. Large
  cross-track = a detour, not "on my way."

The current code filters on neither.

## Decisions (from brainstorming)

- **Intent:** "Only on my way" — strictly places ahead along the route. Never
  send the driver backward.
- **Empty case:** When the strict filter removes everything, fall back to a
  plain nearby search and let the Copilot say so (it already does, via `mode`).
- **Corridor width:** ~1 km off the route (moderate; ~2 km round-trip detour).

## Approach (chosen: A — post-filter in Python)

Let Google's along-route search return its hits as today, then pass them through
one pure geometry function that:

1. decodes the route polyline,
2. drops places behind the driver or outside the corridor, and
3. sorts the survivors nearest-ahead-first.

If the filter empties the list, the existing nearby fallback fires automatically.

Rejected alternatives:

- **B — Trim the polyline then search.** Cut the route at the driver's position,
  re-encode, send only the remainder to Google. Still requires the identical
  projection + cross-track math to enforce the corridor, so it is strictly more
  code than A (plus re-encoding), and it cannot be unit-tested without mocking
  Google. No offsetting benefit.
- **C — Detour-cost ranking.** Rank by actual added drive time via extra routing
  calls. Costs money per query and adds latency. YAGNI.

Approach A is the smallest change, is a single pure "decider" function matching
the project's pure-decider convention (no network, fully pytest-testable), and
requires no provider, router, or prompt changes and no new dependencies.

## Components

### New module: `app/places/route_geometry.py`

Pure module. No `httpx`, no Google, no provider imports — just math over
`LatLng` / `Place`.

- `CORRIDOR_METERS = 1000` — module constant; max cross-track distance a place
  may have and still count as "on the route."
- `AHEAD_GRACE_METERS = 50` — small along-track tolerance so GPS jitter doesn't
  drop the place the driver is currently sitting on.

Functions:

- `_decode(polyline: str) -> list[tuple[float, float]]`
  Thin wrapper over the `polyline` library (already a dependency,
  `polyline>=2.0`). Returns route points as `(lat, lng)`.

- `_project(point: LatLng, path: list[tuple[float, float]]) -> tuple[float, float]`
  Projects one point onto the polyline. Returns `(along_m, cross_m)`:
  `along_m` is the cumulative distance in meters from the route start to the
  nearest point on the path; `cross_m` is the perpendicular distance in meters
  from the point to that nearest point. Uses a local equirectangular
  approximation (convert lat/lng offsets to meters via `cos(lat)` scaling),
  accurate to well under a meter at city scale and dependency-free.

- `filter_ahead_on_route(origin: LatLng, polyline: str, places: list[Place], corridor_m: float = CORRIDOR_METERS) -> list[Place]`
  The decider:
  1. Project `origin` → `d0` (driver's current progress along the route).
  2. Project each place → `(d, x)`.
  3. Keep places where `d >= d0 - AHEAD_GRACE_METERS` **and** `x <= corridor_m`.
  4. Return survivors sorted by `d` ascending (nearest-ahead-first).

### Wiring: `app/places/search.py::search_with_fallback`

One inserted line — filter the along-route result before the empty-check, so the
existing fallback handles the empty case:

```python
if polyline:
    places = await provider.along_route(query, polyline)
    places = filter_ahead_on_route(origin, polyline, places)   # NEW
    mode = "along_route"
if not places:                    # filter emptied it -> nearby fallback fires
    places = await provider.nearby(query, origin)
    mode = "nearby"
```

`mode` continues to drive the `_format_places` wording ("on the route" vs
"nearby"). When the fallback fires, the model sees "nearby" and tells the driver
so. Because the survivors are sorted nearest-ahead-first, the top 5 shown to the
model become the genuine 5 nearest ahead rather than Google's mixed order.

## Data flow

```
along_route(query, polyline) -> up to 20 places (Google order)
        |
        v
filter_ahead_on_route(origin, polyline, places)
        |  decode polyline; project origin -> d0
        |  project each place -> (d, x)
        |  keep d >= d0 - grace AND x <= corridor; sort by d asc
        v
0..N places, nearest-ahead-first
        |
        +-- non-empty -> mode "along_route" -> _format_places "on the route"
        +-- empty     -> nearby(query, origin) -> mode "nearby" -> "nearby"
```

## Edge cases

| Situation | Behavior |
|---|---|
| Polyline decodes to <2 points (garbage/empty) | Return `places` unchanged — cannot project onto a non-line. Never crash. |
| All places filtered out | Empty list → nearby fallback (by design). |
| Origin off-route (driver has strayed) | Projection finds the nearest path point; filter works relative to it. |
| Place beyond the destination | `d > d0`, within corridor → kept. It is still ahead; rare and harmless. |
| `polyline` is `None` | Unchanged from today — the `if polyline:` branch is skipped entirely; filter never called. |

## Testing

### New `tests/test_route_geometry.py` (core, pure JVM-style)

Build a simple straight polyline (a row of points along one axis so along/cross
distances are hand-checkable), place the origin partway, and assert:

- a place behind the origin → dropped;
- a place ahead and within the corridor → kept;
- a place ahead but off-route (>1 km cross-track) → dropped;
- multiple ahead places → returned sorted nearest-ahead-first;
- a place right at the origin (within `AHEAD_GRACE_METERS`) → kept;
- a <2-point polyline → passthrough (input returned unchanged).

### Update `tests/test_copilot_tools.py`

The two tests that currently pass `polyline="abc"` with places at `(1.0, 2.0)`
will now be filtered by real geometry. Give them a real small polyline and
coordinates that place the hit ahead and on-route, keeping the same assertions
against valid geometry. The nearby-fallback and truncation/count tests are
unaffected in intent but may need coordinates updated so the along-route hit
survives the filter.

## Out of scope / non-goals

- No polyline trimming or re-encoding (approach B).
- No detour-cost / added-drive-time ranking (approach C).
- No new dependency (`polyline` is already present).
- No provider, router, prompt, or Android changes.
- Corridor width stays a module constant; env-var tuning is a trivial future
  addition if wanted, not part of this work.
