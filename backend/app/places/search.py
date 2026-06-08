from app.models import LatLng, Place
from app.places.base import PlacesProvider


async def search_with_fallback(
    provider: PlacesProvider,
    query: str,
    origin: LatLng,
    polyline: str | None,
) -> tuple[str, list[Place]]:
    """Along-route search, falling back to nearby. Returns (mode, places)."""
    places: list[Place] = []
    mode = "nearby"
    if polyline:
        places = await provider.along_route(query, polyline)
        mode = "along_route"
    if not places:
        places = await provider.nearby(query, origin)
        mode = "nearby"
    return mode, places
