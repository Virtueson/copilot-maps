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
