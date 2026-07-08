import asyncio

from app.copilot import run_log
from app.copilot.base import AskResult
from app.models import ChatMessage, CopilotContext, LatLng, RouteSummary


def _messages():
    return [
        ChatMessage(role="user", content="any traffic?"),
        ChatMessage(role="assistant", content="Yes, heavy."),
        ChatMessage(role="user", content="find gas"),
    ]


def _context():
    return CopilotContext(
        origin=LatLng(lat=-6.2, lng=106.8),
        selected_route_polyline="abc",
        routes=[RouteSummary(summary="A", distance_meters=8200, duration_seconds=1320,
                             traffic="heavy", selected=True)],
    )


def test_build_run_row_maps_all_fields():
    result = AskResult("There's a Shell ahead.", ["search_places"], 2)
    row = run_log.build_run_row(
        session_id="s1", turn_index=3, messages=_messages(), context=_context(),
        result=result, answer_language="en", model="deepseek", provider="openai",
        latency_ms=1840, error=None,
    )
    assert row["session_id"] == "s1"
    assert row["turn_index"] == 3
    assert row["user_message"] == "find gas"
    assert row["memory_messages"] == [
        {"role": "user", "content": "any traffic?"},
        {"role": "assistant", "content": "Yes, heavy."},
        {"role": "user", "content": "find gas"},
    ]
    assert row["trip_context"]["routes"][0]["summary"] == "A"
    assert row["tools_used"] == ["search_places"]
    assert row["loop_count"] == 2
    assert row["answer"] == "There's a Shell ahead."
    assert row["answer_language"] == "en"
    assert row["latency_ms"] == 1840
    assert row["error"] is None


def test_build_run_row_error_case_has_empty_answer():
    row = run_log.build_run_row(
        session_id="s1", turn_index=0, messages=_messages(), context=_context(),
        result=None, answer_language=None, model="deepseek", provider="openai",
        latency_ms=12, error="boom",
    )
    assert row["answer"] == ""
    assert row["tools_used"] == []
    assert row["loop_count"] is None
    assert row["error"] == "boom"


class _RaisingClient:
    async def post(self, *a, **k):
        raise RuntimeError("network down")


class _SpyClient:
    def __init__(self):
        self.called = False

    async def post(self, *a, **k):
        self.called = True
        return _FakeResponse()


class _FakeResponse:
    def raise_for_status(self):
        raise RuntimeError("500 server error")


class _StatusErrorClient:
    async def post(self, *a, **k):
        return _FakeResponse()


def test_log_run_noop_when_unconfigured(monkeypatch):
    # No SUPABASE_URL/KEY -> must not touch the client at all.
    monkeypatch.setattr(run_log, "get_settings", lambda: _FakeSettings("", ""))
    spy = _SpyClient()
    asyncio.run(run_log.log_run({"session_id": "s"}, client=spy))
    assert spy.called is False


def test_log_run_swallows_post_failure(monkeypatch):
    monkeypatch.setattr(run_log, "get_settings", lambda: _FakeSettings("https://x", "key"))
    # Must swallow the RuntimeError, not propagate.
    asyncio.run(run_log.log_run({"session_id": "s", "turn_index": 1, "user_message": "hi"},
                                client=_RaisingClient()))


def test_log_run_swallows_http_status_error(monkeypatch):
    monkeypatch.setattr(run_log, "get_settings", lambda: _FakeSettings("https://x", "key"))
    # raise_for_status() raising must also be swallowed, not propagated.
    asyncio.run(run_log.log_run({"session_id": "s", "turn_index": 1, "user_message": "hi"},
                                client=_StatusErrorClient()))


class _FakeSettings:
    def __init__(self, url, key):
        self.supabase_url = url
        self.supabase_service_key = key
