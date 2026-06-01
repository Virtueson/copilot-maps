from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health_ok():
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}


def test_plan_returns_three_routes():
    body = {
        "origin": {"lat": 1.2966, "lng": 103.7764},
        "destination": {"lat": 1.3521, "lng": 103.8198},
    }
    resp = client.post("/routes/plan", json=body)
    assert resp.status_code == 200
    data = resp.json()
    assert len(data["routes"]) == 3
    for route in data["routes"]:
        assert route["polyline"]
        assert route["distance_meters"] > 0
        assert route["duration_seconds"] > 0
        assert route["id"].startswith("route-")


def test_plan_rejects_malformed_body():
    resp = client.post("/routes/plan", json={"origin": {"lat": 1.0, "lng": 2.0}})
    assert resp.status_code == 422
