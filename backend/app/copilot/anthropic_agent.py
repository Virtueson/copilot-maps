import anthropic

from app.copilot.base import CopilotError
from app.copilot.prompt import PERSONA, format_context
from app.copilot.registry import dispatch, to_anthropic_tools
from app.copilot.tools import build_tools
from app.models import ChatMessage, CopilotContext

_MODEL = "claude-haiku-4-5"
_MAX_TOKENS = 1024
_MAX_ITERATIONS = 5


def _system_blocks(context: CopilotContext) -> list[dict]:
    # Stable persona is the cached prefix; volatile context comes after the breakpoint.
    return [
        {"type": "text", "text": PERSONA, "cache_control": {"type": "ephemeral"}},
        {"type": "text", "text": format_context(context)},
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
        self._tools = build_tools(places_provider)
        self._client = client or anthropic.AsyncAnthropic(api_key=api_key)

    async def ask(self, messages: list[ChatMessage], context: CopilotContext) -> str:
        async def execute(name: str, tool_input: dict) -> str:
            return await dispatch(self._tools, name, tool_input, context)

        convo = [{"role": m.role, "content": m.content} for m in messages]
        try:
            return await run_agent_loop(
                client=self._client,
                model=_MODEL,
                system=_system_blocks(context),
                tools=to_anthropic_tools(self._tools),
                messages=convo,
                execute_tool=execute,
            )
        except anthropic.AnthropicError as exc:
            raise CopilotError(str(exc)) from exc
