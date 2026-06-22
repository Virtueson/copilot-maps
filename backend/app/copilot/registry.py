"""Provider-agnostic tool registry.

A `Tool` is declared once (name + JSON-schema parameters + an async executor with
its dependencies already bound in). Each LLM provider renders the same registry into
its own wire format and dispatches calls by name. To add a capability, append one
`Tool` in `build_tools()` — no provider or agent-loop code needs to change.
"""

from dataclasses import dataclass
from typing import Awaitable, Callable

from app.models import CopilotContext

# An executor receives the parsed tool arguments + the current trip context.
ToolExecutor = Callable[[dict, CopilotContext], Awaitable[str]]


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
    tools: list[Tool], name: str, args: dict, context: CopilotContext
) -> str:
    """Run the executor for `name`, or report an unknown tool."""
    for tool in tools:
        if tool.name == name:
            return await tool.executor(args, context)
    return f"Unknown tool: {name}"
