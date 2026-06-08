import asyncio

from app.copilot.stub import StubCopilot
from app.models import ChatMessage, CopilotContext, LatLng, RouteSummary


def _context() -> CopilotContext:
    return CopilotContext(
        origin=LatLng(lat=1.0, lng=2.0),
        selected_route_polyline=None,
        routes=[
            RouteSummary(summary="Main St", distance_meters=12400, duration_seconds=960,
                         traffic="moderate", selected=True),
        ],
    )


def test_stub_reply_mentions_route_count():
    messages = [ChatMessage(role="user", content="which route is fastest?")]

    reply = asyncio.run(StubCopilot().ask(messages, _context()))

    assert isinstance(reply, str)
    assert reply
    assert "1" in reply  # references the one route in context


from fastapi.testclient import TestClient

from app.config import get_copilot
from app.main import app


class _FixedCopilot:
    async def ask(self, messages, context):
        return "fixed reply about " + str(len(context.routes)) + " routes"


def _client() -> TestClient:
    app.dependency_overrides[get_copilot] = lambda: _FixedCopilot()
    return TestClient(app)


def teardown_function():
    app.dependency_overrides.pop(get_copilot, None)


def test_copilot_ask_returns_reply():
    client = _client()
    body = {
        "messages": [{"role": "user", "content": "hi"}],
        "context": {"origin": {"lat": 1.0, "lng": 2.0},
                    "routes": [{"summary": "A", "distance_meters": 100,
                                "duration_seconds": 60, "traffic": "light", "selected": True}]},
    }
    resp = client.post("/copilot/ask", json=body)
    assert resp.status_code == 200
    assert resp.json()["reply"] == "fixed reply about 1 routes"


def test_copilot_ask_malformed_422():
    client = _client()
    resp = client.post("/copilot/ask", json={"messages": []})
    assert resp.status_code == 422
