"""OpenAI-compatible copilot provider.

Talks to any OpenAI-compatible chat-completions endpoint (e.g. SumoPod at
https://ai.sumopod.com/v1). The base URL and model name are injected from config,
so the same code runs deepseek-v4-flash, gpt-5, gemini, etc. — swap by changing
MODEL_NAME in .env. Tools come from the shared registry, so adding a capability
needs no change here.
"""

import json

import openai

from app.copilot.base import CopilotError, AskResult
from app.copilot.prompt import PERSONA, format_context
from app.copilot.registry import dispatch, to_openai_tools
from app.copilot.tools import build_tools
from app.models import ChatMessage, CopilotContext

_MAX_TOKENS = 1024
_MAX_ITERATIONS = 5


def _system_message(context: CopilotContext) -> dict:
    return {"role": "system", "content": PERSONA + "\n\n" + format_context(context)}


async def run_openai_agent_loop(
    client, model, messages, tools, execute_tool, max_iterations=_MAX_ITERATIONS
):
    convo = list(messages)
    tools_used: list[str] = []
    loops = 0
    for _ in range(max_iterations):
        loops += 1
        response = await client.chat.completions.create(
            model=model,
            max_tokens=_MAX_TOKENS,
            messages=convo,
            tools=tools,
        )
        message = response.choices[0].message
        tool_calls = getattr(message, "tool_calls", None)
        if tool_calls:
            convo.append({
                "role": "assistant",
                "content": message.content or "",
                "tool_calls": [
                    {
                        "id": tc.id,
                        "type": "function",
                        "function": {
                            "name": tc.function.name,
                            "arguments": tc.function.arguments,
                        },
                    }
                    for tc in tool_calls
                ],
            })
            for tc in tool_calls:
                if tc.function.name not in tools_used:
                    tools_used.append(tc.function.name)
                try:
                    args = json.loads(tc.function.arguments or "{}")
                except json.JSONDecodeError:
                    args = {}
                result = await execute_tool(tc.function.name, args)
                convo.append({
                    "role": "tool",
                    "tool_call_id": tc.id,
                    "content": result,
                })
            continue
        return AskResult((message.content or "").strip(), tools_used, loops)
    return AskResult("Sorry, I couldn't work that out just now.", tools_used, loops)


class OpenAICompatCopilot:
    def __init__(self, api_key, base_url, model, places_provider, client=None):
        self._model = model
        self._tools = build_tools(places_provider)
        self._client = client or openai.AsyncOpenAI(api_key=api_key, base_url=base_url)

    async def ask(self, messages: list[ChatMessage], context: CopilotContext) -> AskResult:
        async def execute(name: str, args: dict) -> str:
            return await dispatch(self._tools, name, args, context)

        convo = [_system_message(context)] + [
            {"role": m.role, "content": m.content} for m in messages
        ]
        try:
            return await run_openai_agent_loop(
                client=self._client,
                model=self._model,
                messages=convo,
                tools=to_openai_tools(self._tools),
                execute_tool=execute,
            )
        except openai.OpenAIError as exc:
            raise CopilotError(str(exc)) from exc
