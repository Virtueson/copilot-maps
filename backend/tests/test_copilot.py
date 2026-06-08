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
