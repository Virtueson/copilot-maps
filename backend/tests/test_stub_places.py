import asyncio

import polyline as polyline_lib

from app.models import LatLng
from app.places.stub import StubPlacesProvider


def test_nearby_returns_places_near_origin():
    origin = LatLng(lat=1.3, lng=103.8)

    places = asyncio.run(StubPlacesProvider().nearby("gas station", origin))

    assert len(places) >= 1
    for p in places:
        assert abs(p.lat - origin.lat) < 0.01
        assert abs(p.lng - origin.lng) < 0.01
        assert "gas station" in p.name


def test_along_route_returns_places_near_polyline():
    encoded = polyline_lib.encode([(1.30, 103.80), (1.31, 103.81), (1.32, 103.82)])

    places = asyncio.run(StubPlacesProvider().along_route("restaurant", encoded))

    assert len(places) >= 1
    for p in places:
        assert "restaurant" in p.name


def test_stub_places_include_price_and_open_now():
    provider = StubPlacesProvider()
    places = asyncio.run(provider.nearby("coffee", LatLng(lat=1.0, lng=2.0)))
    assert places, "stub should return places"
    assert places[0].price_level is not None
    assert places[0].open_now in (True, False)
