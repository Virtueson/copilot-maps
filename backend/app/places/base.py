from typing import Protocol

from app.models import LatLng, Place


class PlacesProvider(Protocol):
    async def along_route(self, query: str, polyline: str) -> list[Place]: ...
    async def nearby(self, query: str, origin: LatLng) -> list[Place]: ...
