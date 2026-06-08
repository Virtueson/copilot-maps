import asyncio

from app.copilot.tools import SEARCH_PLACES_TOOL, execute_search_places
from app.models import CopilotContext, LatLng, Place


class _FakeProvider:
    def __init__(self, along, near):
        self._along = along
        self._near = near

    async def along_route(self, query, polyline):
        return self._along

    async def nearby(self, query, origin):
        return self._near


def _place(name):
    return Place(id=name, name=name, lat=1.0, lng=2.0, rating=4.5)


def test_tool_schema_shape():
    assert SEARCH_PLACES_TOOL["name"] == "search_places"
    assert "query" in SEARCH_PLACES_TOOL["input_schema"]["properties"]


def test_executor_uses_route_when_polyline_present():
    ctx = CopilotContext(origin=LatLng(lat=1.0, lng=2.0), selected_route_polyline="abc")
    provider = _FakeProvider(along=[_place("OnRoute")], near=[_place("Nearby")])

    result = asyncio.run(execute_search_places("gas station", ctx, provider))

    assert "on the route" in result
    assert "OnRoute" in result


def test_executor_falls_back_to_nearby():
    ctx = CopilotContext(origin=LatLng(lat=1.0, lng=2.0), selected_route_polyline="abc")
    provider = _FakeProvider(along=[], near=[_place("Nearby")])

    result = asyncio.run(execute_search_places("gas station", ctx, provider))

    assert "nearby" in result
    assert "Nearby" in result
