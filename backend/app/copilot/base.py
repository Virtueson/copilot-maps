from typing import Protocol

from app.models import ChatMessage, CopilotContext


class CopilotError(Exception):
    """Raised when the copilot provider fails (mapped to HTTP 502)."""


class CopilotProvider(Protocol):
    async def ask(self, messages: list[ChatMessage], context: CopilotContext) -> str: ...
