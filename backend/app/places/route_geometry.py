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
    # Limitation: on routes that double back within the corridor (U-turns,
    # cloverleafs, or a divided highway traced as a single carriageway), a
    # point can project to either leg; its along-track distance (hence
    # ahead/behind and sort order) may then reflect the far leg. Acceptable
    # for this personal-use app; noted as a known edge.
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
