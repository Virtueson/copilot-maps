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
