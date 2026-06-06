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
