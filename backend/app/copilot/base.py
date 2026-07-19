from dataclasses import dataclass, field
from typing import Protocol

from app.models import ChatMessage, CopilotContext, Place


class CopilotError(Exception):
    """Raised when the copilot provider fails (mapped to HTTP 502)."""


@dataclass(frozen=True)
class AskResult:
    reply: str
    tools_used: list[str] = field(default_factory=list)
    loop_count: int = 0
    places: list[Place] = field(default_factory=list)
    navigation: Place | None = None


class CopilotProvider(Protocol):
    async def ask(self, messages: list[ChatMessage], context: CopilotContext) -> AskResult: ...
