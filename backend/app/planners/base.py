from typing import Protocol

from app.models import LatLng, Route


class RoutePlanner(Protocol):
    async def plan(self, origin: LatLng, destination: LatLng) -> list[Route]: ...
