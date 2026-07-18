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
