import anthropic

from app.copilot.base import CopilotError
from app.copilot.tools import SEARCH_PLACES_TOOL, execute_search_places
from app.models import ChatMessage, CopilotContext

_MODEL = "claude-haiku-4-5"
_MAX_TOKENS = 1024
_MAX_ITERATIONS = 5

_PERSONA = (
    "You are Copilot, a concise in-car navigation assistant. Answer in one or two "
    "short, spoken-style sentences — the driver will hear this aloud later. Use the "
    "current trip context for route and traffic questions. Use the search_places tool "
    "to find gas, food, or other places on the route or nearby. If you don't have the "
    "data, say so briefly."
)


def _format_context(context: CopilotContext) -> str:
    lines = [f"Current location: {context.origin.lat:.5f},{context.origin.lng:.5f}"]
    if context.routes:
        lines.append("Planned routes:")
        for r in context.routes:
            sel = " (selected)" if r.selected else ""
            mins = round(r.duration_seconds / 60)
            km = r.distance_meters / 1000
            lines.append(f"- {r.summary}{sel}: {mins} min, {km:.1f} km, traffic {r.traffic}")
    else:
        lines.append("No route is currently planned.")
    return "\n".join(lines)


def _system_blocks(context: CopilotContext) -> list[dict]:
    # Stable persona is the cached prefix; volatile context comes after the breakpoint.
    return [
        {"type": "text", "text": _PERSONA, "cache_control": {"type": "ephemeral"}},
        {"type": "text", "text": _format_context(context)},
    ]


async def run_agent_loop(client, model, system, tools, messages, execute_tool, max_iterations=_MAX_ITERATIONS):
    convo = list(messages)
    for _ in range(max_iterations):
        response = await client.messages.create(
            model=model,
            max_tokens=_MAX_TOKENS,
            system=system,
            tools=tools,
            messages=convo,
        )
        if response.stop_reason == "tool_use":
            convo.append({"role": "assistant", "content": response.content})
            tool_results = []
            for block in response.content:
                if getattr(block, "type", None) == "tool_use":
                    result = await execute_tool(block.name, block.input)
                    tool_results.append({
                        "type": "tool_result",
                        "tool_use_id": block.id,
                        "content": result,
                    })
            convo.append({"role": "user", "content": tool_results})
            continue
        return "".join(
            b.text for b in response.content if getattr(b, "type", None) == "text"
        ).strip()
    return "Sorry, I couldn't work that out just now."


class AnthropicCopilot:
    def __init__(self, api_key: str, places_provider, client=None):
        self._places_provider = places_provider
        self._client = client or anthropic.AsyncAnthropic(api_key=api_key)

    async def ask(self, messages: list[ChatMessage], context: CopilotContext) -> str:
        async def execute(name: str, tool_input: dict) -> str:
            if name == "search_places":
                return await execute_search_places(
                    tool_input.get("query", ""), context, self._places_provider
                )
            return "Unknown tool."

        convo = [{"role": m.role, "content": m.content} for m in messages]
        try:
            return await run_agent_loop(
                client=self._client,
                model=_MODEL,
                system=_system_blocks(context),
                tools=[SEARCH_PLACES_TOOL],
                messages=convo,
                execute_tool=execute,
            )
        except anthropic.AnthropicError as exc:
            raise CopilotError(str(exc)) from exc
