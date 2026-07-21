import asyncio

import polyline

from app.copilot.tools import SEARCH_PLACES_TOOL, execute_search_places
from app.models import CopilotContext, LatLng, Place

# Straight west->east line along the equator; origin sits at its start so any
# hit further east (larger lng) is "ahead on route".
ROUTE = polyline.encode([(0.0, 0.0), (0.0, 0.1)])


class _FakeProvider:
    def __init__(self, along, near):
        self._along = along
        self._near = near

    async def along_route(self, query, polyline):
        return self._along

    async def nearby(self, query, origin):
        return self._near


def _place(name, lat=0.0, lng=0.05):
    # Default coordinates sit on ROUTE, ahead of an origin at (0.0, 0.0).
    return Place(id=name, name=name, lat=lat, lng=lng, rating=4.5)


def _ctx():
    return CopilotContext(origin=LatLng(lat=0.0, lng=0.0), selected_route_polyline=ROUTE)


def test_tool_schema_shape():
    assert SEARCH_PLACES_TOOL["name"] == "search_places"
    assert "query" in SEARCH_PLACES_TOOL["input_schema"]["properties"]


def test_executor_uses_route_when_polyline_present():
    provider = _FakeProvider(along=[_place("OnRoute")], near=[_place("Nearby")])

    result = asyncio.run(execute_search_places("gas station", _ctx(), provider))

    assert "on the route" in result
    assert "OnRoute" in result


def test_executor_falls_back_to_nearby():
    provider = _FakeProvider(along=[], near=[_place("Nearby")])

    result = asyncio.run(execute_search_places("gas station", _ctx(), provider))

    assert "nearby" in result
    assert "Nearby" in result


def test_executor_drops_places_behind_the_driver():
    """A place already passed must not appear; one ahead on-route must."""
    ctx = CopilotContext(
        origin=LatLng(lat=0.0, lng=0.02), selected_route_polyline=ROUTE
    )
    behind = _place("Behind", lat=0.0, lng=0.01)  # ~1.1 km, before the driver
    ahead = _place("Ahead", lat=0.0, lng=0.05)  # ~5.6 km, ahead on route
    provider = _FakeProvider(along=[behind, ahead], near=[])

    result = asyncio.run(execute_search_places("gas station", ctx, provider))

    assert "Ahead" in result
    assert "Behind" not in result


def test_truncated_results_say_how_many_are_shown():
    """The header must never claim more places than the list actually shows."""
    provider = _FakeProvider(along=[_place(f"P{i}") for i in range(20)], near=[])

    result = asyncio.run(execute_search_places("gas station", _ctx(), provider))
    listed = [line for line in result.splitlines() if line.startswith("- ")]

    assert len(listed) == 5
    assert result.startswith("Found 20 places on the route. Top 5:")
    assert "P4" in result and "P5" not in result


def test_untruncated_results_report_the_real_count():
    provider = _FakeProvider(along=[_place("A"), _place("B")], near=[])

    result = asyncio.run(execute_search_places("gas station", _ctx(), provider))

    assert result.startswith("Found 2 places on the route. Top 2:")


def test_result_text_omits_coordinates():
    """The model-facing text must not include lat/lng: coordinates tempt the
    model to read them aloud (e.g. to disambiguate same-named places), and the
    app already gets real coordinates via the structured places list."""
    provider = _FakeProvider(along=[_place("OnRoute")], near=[])

    result = asyncio.run(execute_search_places("gas station", _ctx(), provider))

    assert "OnRoute" in result
    assert "4.5 stars" in result
    assert " at " not in result
    assert "0.0500" not in result and "0.0000" not in result
