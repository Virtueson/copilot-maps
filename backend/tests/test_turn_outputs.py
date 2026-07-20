import asyncio

import polyline

from app.copilot.registry import Tool, TurnOutputs, dispatch
from app.copilot.tools import build_tools
from app.models import CopilotContext, LatLng, Place

# A real, decodable west->east line; origin at its start makes the on-route hit
# survive the ahead-on-route filter.
_ROUTE = polyline.encode([(0.0, 0.0), (0.0, 0.1)])


class _FakeProvider:
    def __init__(self, along, near):
        self._along, self._near = along, near

    async def along_route(self, query, polyline):
        return self._along

    async def nearby(self, query, origin):
        return self._near


def _place(name):
    return Place(id=name, name=name, lat=0.0, lng=0.05, rating=4.5)


def test_dispatch_passes_outputs_to_executor():
    async def _exec(args, context, outputs):
        outputs.places = [_place("X")]
        return "ok"

    tool = Tool(name="t", description="", parameters={}, executor=_exec)
    ctx = CopilotContext(origin=LatLng(lat=0.0, lng=0.0))
    outputs = TurnOutputs()

    result = asyncio.run(dispatch([tool], "t", {}, ctx, outputs))

    assert result == "ok"
    assert [p.name for p in outputs.places] == ["X"]


def test_search_places_tool_writes_places_to_outputs():
    provider = _FakeProvider(along=[_place("OnRoute")], near=[])
    tools = build_tools(provider)
    search = next(t for t in tools if t.name == "search_places")
    # origin at the route start so the on-route hit survives the ahead-filter
    ctx = CopilotContext(
        origin=LatLng(lat=0.0, lng=0.0),
        selected_route_polyline=_ROUTE,
    )
    outputs = TurnOutputs()

    text = asyncio.run(search.executor({"query": "gas station"}, ctx, outputs))

    assert "OnRoute" in text
    assert [p.name for p in outputs.places] == ["OnRoute"]
