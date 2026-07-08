import asyncio

from app.copilot.base import AskResult, CopilotError
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

    result = asyncio.run(StubCopilot().ask(messages, _context()))

    assert isinstance(result.reply, str)
    assert result.reply
    assert "1" in result.reply  # references the one route in context


from fastapi.testclient import TestClient

from app.config import get_copilot
from app.main import app


class _FixedCopilot:
    async def ask(self, messages, context):
        return AskResult("fixed reply about " + str(len(context.routes)) + " routes", [], 1)


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


class _FakeCopilot:
    def __init__(self, reply):
        self._reply = reply

    async def ask(self, messages, context):
        return AskResult(self._reply, [], 1)


def _body():
    return {
        "messages": [{"role": "user", "content": "test"}],
        "context": {"origin": {"lat": 1.0, "lng": 2.0}},
    }


def test_reply_language_indonesian():
    app.dependency_overrides[get_copilot] = lambda: _FakeCopilot("Belok kiri lalu lurus terus")
    try:
        resp = TestClient(app).post("/copilot/ask", json=_body())
        assert resp.status_code == 200
        assert resp.json()["language"] == "id"
    finally:
        app.dependency_overrides.pop(get_copilot, None)


def test_reply_language_english():
    app.dependency_overrides[get_copilot] = lambda: _FakeCopilot("Turn left then continue straight")
    try:
        resp = TestClient(app).post("/copilot/ask", json=_body())
        assert resp.status_code == 200
        assert resp.json()["language"] == "en"
    finally:
        app.dependency_overrides.pop(get_copilot, None)


def test_ask_accepts_session_and_turn_and_still_returns_reply():
    client = _client()  # _FixedCopilot, Supabase unconfigured
    body = {
        "messages": [{"role": "user", "content": "hi"}],
        "context": {"origin": {"lat": 1.0, "lng": 2.0}, "routes": []},
        "session_id": "sess-1",
        "turn_index": 0,
    }
    resp = client.post("/copilot/ask", json=body)
    assert resp.status_code == 200
    assert resp.json()["reply"] == "fixed reply about 0 routes"
    assert set(resp.json().keys()) == {"reply", "language"}  # contract unchanged


class _FailingCopilot:
    async def ask(self, messages, context):
        raise CopilotError("upstream boom")


def test_copilot_ask_error_path_logs_run(monkeypatch):
    import app.routers.copilot as copilot_router

    recorded = {}

    async def fake_log_run(row):
        recorded["row"] = row

    monkeypatch.setattr(copilot_router, "log_run", fake_log_run)

    app.dependency_overrides[get_copilot] = lambda: _FailingCopilot()
    try:
        resp = TestClient(app).post("/copilot/ask", json=_body())
        assert resp.status_code == 502
    finally:
        app.dependency_overrides.pop(get_copilot, None)

    assert "row" in recorded
    assert recorded["row"]["error"] == "upstream boom"
    assert recorded["row"]["answer"] == ""
