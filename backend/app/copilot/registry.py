"""Provider-agnostic tool registry.

A `Tool` is declared once (name + JSON-schema parameters + an async executor with
its dependencies already bound in). Each LLM provider renders the same registry into
its own wire format and dispatches calls by name. To add a capability, append one
`Tool` in `build_tools()` — no provider or agent-loop code needs to change.
"""

from dataclasses import dataclass, field
from typing import Awaitable, Callable

from app.models import CopilotContext, Place


@dataclass
class TurnOutputs:
    """Structured side-outputs a tool can emit during one /copilot/ask turn.

    Executors return text for the LLM to read; anything the *app* needs (map
    pins, a resolved navigation target) is written here and copied into the
    AskResult after the agent loop.
    """

    places: list[Place] = field(default_factory=list)
    navigation: Place | None = None


# An executor receives parsed args + the trip context + the turn's outputs sink.
ToolExecutor = Callable[[dict, CopilotContext, TurnOutputs], Awaitable[str]]


@dataclass(frozen=True)
class Tool:
    name: str
    description: str
    parameters: dict  # JSON Schema for the arguments object
    executor: ToolExecutor


def to_openai_tools(tools: list[Tool]) -> list[dict]:
    """Render the registry into OpenAI/SumoPod `tools` format."""
    return [
        {
            "type": "function",
            "function": {
                "name": t.name,
                "description": t.description,
                "parameters": t.parameters,
            },
        }
        for t in tools
    ]


def to_anthropic_tools(tools: list[Tool]) -> list[dict]:
    """Render the registry into Anthropic `tools` format."""
    return [
        {
            "name": t.name,
            "description": t.description,
            "input_schema": t.parameters,
        }
        for t in tools
    ]


async def dispatch(
    tools: list[Tool],
    name: str,
    args: dict,
    context: CopilotContext,
    outputs: TurnOutputs,
) -> str:
    """Run the executor for `name`, or report an unknown tool."""
    for tool in tools:
        if tool.name == name:
            return await tool.executor(args, context, outputs)
    return f"Unknown tool: {name}"
