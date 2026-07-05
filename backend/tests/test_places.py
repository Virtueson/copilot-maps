from fastapi.testclient import TestClient

from app.config import get_places_provider
from app.main import app
from app.models import Place


class _FakeProvider:
    def __init__(self, along: list[Place], near: list[Place]):
        self._along = along
        self._near = near

    async def along_route(self, query: str, polyline: str) -> list[Place]:
        return self._along

    async def nearby(self, query: str, origin) -> list[Place]:
        return self._near


def _place(name: str) -> Place:
    return Place(id=name, name=name, lat=1.0, lng=2.0)


def _client(along: list[Place], near: list[Place]) -> TestClient:
    app.dependency_overrides[get_places_provider] = lambda: _FakeProvider(along, near)
    return TestClient(app)


def teardown_function():
    app.dependency_overrides.pop(get_places_provider, None)


def test_along_route_returns_along_route_mode():
    client = _client(along=[_place("A")], near=[_place("N")])
    body = {"query": "gas station", "origin": {"lat": 1.0, "lng": 2.0}, "polyline": "abc"}
    resp = client.post("/places/search", json=body)
    assert resp.status_code == 200
    data = resp.json()
    assert data["mode"] == "along_route"
    assert [p["name"] for p in data["places"]] == ["A"]


def test_no_polyline_uses_nearby():
    client = _client(along=[_place("A")], near=[_place("N")])
    body = {"query": "restaurant", "origin": {"lat": 1.0, "lng": 2.0}}
    resp = client.post("/places/search", json=body)
    data = resp.json()
    assert data["mode"] == "nearby"
    assert [p["name"] for p in data["places"]] == ["N"]


def test_empty_along_route_falls_back_to_nearby():
    client = _client(along=[], near=[_place("N")])
    body = {"query": "gas station", "origin": {"lat": 1.0, "lng": 2.0}, "polyline": "abc"}
    resp = client.post("/places/search", json=body)
    data = resp.json()
    assert data["mode"] == "nearby"
    assert [p["name"] for p in data["places"]] == ["N"]


def test_malformed_body_422():
    client = _client(along=[], near=[])
    resp = client.post("/places/search", json={"query": "x"})
    assert resp.status_code == 422


def test_place_carries_price_and_open_now():
    p = Place(id="x", name="X", lat=1.0, lng=2.0,
              price_level="PRICE_LEVEL_MODERATE", open_now=True)
    client = _client(along=[], near=[p])
    resp = client.post("/places/search",
                       json={"query": "q", "origin": {"lat": 1.0, "lng": 2.0}})
    place = resp.json()["places"][0]
    assert place["price_level"] == "PRICE_LEVEL_MODERATE"
    assert place["open_now"] is True


def test_google_to_place_maps_price_and_open_now():
    from app.places.google import _to_place
    raw = {
        "id": "g1", "displayName": {"text": "Cafe"},
        "location": {"latitude": 1.0, "longitude": 2.0},
        "formattedAddress": "1 St", "rating": 4.5,
        "priceLevel": "PRICE_LEVEL_INEXPENSIVE",
        "currentOpeningHours": {"openNow": False},
    }
    p = _to_place(raw)
    assert p.price_level == "PRICE_LEVEL_INEXPENSIVE"
    assert p.open_now is False


def test_google_to_place_tolerates_missing_price_and_hours():
    from app.places.google import _to_place
    p = _to_place({"id": "g2", "displayName": {"text": "X"},
                   "location": {"latitude": 0.0, "longitude": 0.0}})
    assert p.price_level is None
    assert p.open_now is None
