import asyncio
from dataclasses import dataclass

from app.copilot.anthropic_agent import run_agent_loop


@dataclass
class _ToolUse:
    id: str
    name: str
    input: dict
    type: str = "tool_use"


@dataclass
class _Text:
    text: str
    type: str = "text"


@dataclass
class _Resp:
    stop_reason: str
    content: list


class _FakeMessages:
    def __init__(self, scripted):
        self._scripted = list(scripted)
        self.calls = 0

    async def create(self, **kwargs):
        resp = self._scripted[self.calls]
        self.calls += 1
        return resp


class _FakeClient:
    def __init__(self, scripted):
        self.messages = _FakeMessages(scripted)


def test_loop_runs_tool_then_returns_text():
    scripted = [
        _Resp(stop_reason="tool_use",
              content=[_ToolUse(id="t1", name="search_places", input={"query": "gas"})]),
        _Resp(stop_reason="end_turn", content=[_Text(text="There's a Shell ahead.")]),
    ]
    client = _FakeClient(scripted)
    executed = {}

    async def fake_execute(name, tool_input):
        executed["name"] = name
        executed["query"] = tool_input["query"]
        return "Found 1 place on the route: Shell"

    result = asyncio.run(run_agent_loop(
        client=client, model="m", system="s", tools=[], messages=[],
        execute_tool=fake_execute,
    ))

    assert executed == {"name": "search_places", "query": "gas"}
    assert result.reply == "There's a Shell ahead."
    assert result.tools_used == ["search_places"]
    assert result.loop_count == 2
    assert client.messages.calls == 2


def test_loop_returns_text_without_tool():
    scripted = [_Resp(stop_reason="end_turn", content=[_Text(text="Take the expressway.")])]
    client = _FakeClient(scripted)

    async def fake_execute(name, tool_input):
        raise AssertionError("should not be called")

    result = asyncio.run(run_agent_loop(
        client=client, model="m", system="s", tools=[], messages=[],
        execute_tool=fake_execute,
    ))

    assert result.reply == "Take the expressway."
    assert result.tools_used == []
    assert result.loop_count == 1
