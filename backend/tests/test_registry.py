import asyncio

from app.copilot.registry import Tool, dispatch, to_anthropic_tools, to_openai_tools
from app.models import CopilotContext, LatLng


def _ctx():
    return CopilotContext(origin=LatLng(lat=1.0, lng=2.0))


async def _echo(args, context):
    return f"ran with {args.get('q')}"


def _tool():
    return Tool(
        name="demo",
        description="demo tool",
        parameters={"type": "object", "properties": {"q": {"type": "string"}}},
        executor=_echo,
    )


def test_openai_format():
    out = to_openai_tools([_tool()])
    assert out[0]["type"] == "function"
    assert out[0]["function"]["name"] == "demo"
    assert "q" in out[0]["function"]["parameters"]["properties"]


def test_anthropic_format():
    out = to_anthropic_tools([_tool()])
    assert out[0]["name"] == "demo"
    assert "q" in out[0]["input_schema"]["properties"]


def test_dispatch_runs_named_tool():
    result = asyncio.run(dispatch([_tool()], "demo", {"q": "hi"}, _ctx()))
    assert result == "ran with hi"


def test_dispatch_unknown_tool():
    result = asyncio.run(dispatch([_tool()], "missing", {}, _ctx()))
    assert "Unknown tool" in result
