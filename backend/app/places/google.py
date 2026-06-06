import httpx

from app.models import LatLng, Place

_SEARCH_TEXT_URL = "https://places.googleapis.com/v1/places:searchText"
_FIELD_MASK = (
    "places.id,places.displayName,places.location,"
    "places.formattedAddress,places.rating"
)


def _to_place(p: dict) -> Place:
    location = p.get("location", {})
    return Place(
        id=p.get("id", ""),
        name=p.get("displayName", {}).get("text", "Unknown"),
        lat=float(location.get("latitude", 0.0)),
        lng=float(location.get("longitude", 0.0)),
        address=p.get("formattedAddress"),
        rating=p.get("rating"),
    )


class GooglePlacesProvider:
    def __init__(self, api_key: str):
        self._api_key = api_key

    async def _search(self, body: dict) -> list[Place]:
        headers = {
            "Content-Type": "application/json",
            "X-Goog-Api-Key": self._api_key,
            "X-Goog-FieldMask": _FIELD_MASK,
        }
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.post(_SEARCH_TEXT_URL, json=body, headers=headers)
        resp.raise_for_status()
        return [_to_place(p) for p in resp.json().get("places", [])]

    async def along_route(self, query: str, polyline: str) -> list[Place]:
        body = {
            "textQuery": query,
            "searchAlongRouteParameters": {"polyline": {"encodedPolyline": polyline}},
        }
        return await self._search(body)

    async def nearby(self, query: str, origin: LatLng) -> list[Place]:
        body = {
            "textQuery": query,
            "locationBias": {
                "circle": {
                    "center": {"latitude": origin.lat, "longitude": origin.lng},
                    "radius": 5000.0,
                }
            },
        }
        return await self._search(body)
