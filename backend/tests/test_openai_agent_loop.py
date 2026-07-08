import asyncio
from dataclasses import dataclass

from app.copilot.openai_compat import run_openai_agent_loop


@dataclass
class _Func:
    name: str
    arguments: str


@dataclass
class _ToolCall:
    id: str
    function: _Func
    type: str = "function"


@dataclass
class _Msg:
    content: str | None = None
    tool_calls: list | None = None


@dataclass
class _Choice:
    message: _Msg


@dataclass
class _Resp:
    choices: list


class _FakeCompletions:
    def __init__(self, scripted):
        self._scripted = list(scripted)
        self.calls = 0
        self.last_kwargs = None

    async def create(self, **kwargs):
        self.last_kwargs = kwargs
        resp = self._scripted[self.calls]
        self.calls += 1
        return resp


class _FakeChat:
    def __init__(self, scripted):
        self.completions = _FakeCompletions(scripted)


class _FakeClient:
    def __init__(self, scripted):
        self.chat = _FakeChat(scripted)


def test_openai_loop_runs_tool_then_returns_text():
    scripted = [
        _Resp(choices=[_Choice(_Msg(content="Let me check.", tool_calls=[
            _ToolCall(id="c1", function=_Func(name="search_places", arguments='{"query": "gas"}'))]))]),
        _Resp(choices=[_Choice(_Msg(content="There's a Shell ahead."))]),
    ]
    client = _FakeClient(scripted)
    executed = {}

    async def fake_execute(name, args):
        executed["name"] = name
        executed["query"] = args["query"]
        return "Found 1 place on the route: Shell"

    result = asyncio.run(run_openai_agent_loop(
        client=client, model="deepseek-v4-flash",
        messages=[{"role": "user", "content": "gas?"}],
        tools=[], execute_tool=fake_execute,
    ))

    assert executed == {"name": "search_places", "query": "gas"}
    assert result.reply == "There's a Shell ahead."
    assert result.tools_used == ["search_places"]
    assert result.loop_count == 2
    assert client.chat.completions.calls == 2


def test_openai_loop_dedupes_repeated_tool_in_tools_used():
    scripted = [
        _Resp(choices=[_Choice(_Msg(content="Let me check.", tool_calls=[
            _ToolCall(id="c1", function=_Func(name="search_places", arguments='{"query": "gas"}'))]))]),
        _Resp(choices=[_Choice(_Msg(content="Checking more.", tool_calls=[
            _ToolCall(id="c2", function=_Func(name="search_places", arguments='{"query": "gas2"}'))]))]),
        _Resp(choices=[_Choice(_Msg(content="There's a Shell ahead."))]),
    ]
    client = _FakeClient(scripted)

    async def fake_execute(name, args):
        return "Found 1 place on the route: Shell"

    result = asyncio.run(run_openai_agent_loop(
        client=client, model="deepseek-v4-flash",
        messages=[{"role": "user", "content": "gas?"}],
        tools=[], execute_tool=fake_execute,
    ))

    assert result.tools_used == ["search_places"]
    assert result.loop_count == 3
    assert client.chat.completions.calls == 3


def test_openai_loop_returns_text_without_tool():
    scripted = [_Resp(choices=[_Choice(_Msg(content="Take the expressway."))])]
    client = _FakeClient(scripted)

    async def fake_execute(name, args):
        raise AssertionError("should not be called")

    result = asyncio.run(run_openai_agent_loop(
        client=client, model="m", messages=[], tools=[], execute_tool=fake_execute,
    ))

    assert result.reply == "Take the expressway."
    assert result.tools_used == []
    assert result.loop_count == 1
