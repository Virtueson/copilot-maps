import asyncio

from app.models import LatLng
from app.planners.stub import StubRoutePlanner


def test_stub_returns_three_connected_routes():
    origin = LatLng(lat=1.2966, lng=103.7764)
    dest = LatLng(lat=1.3521, lng=103.8198)

    routes = asyncio.run(StubRoutePlanner().plan(origin, dest))

    assert len(routes) == 3
    ids = [r.id for r in routes]
    assert ids == ["route-0", "route-1", "route-2"]
    for r in routes:
        assert r.polyline                      # non-empty encoded polyline
        assert r.distance_meters > 0
        assert r.duration_seconds > 0
        assert r.summary
