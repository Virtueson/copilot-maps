from app.copilot.base import AskResult
from app.models import ChatMessage, CopilotContext


class StubCopilot:
    """Deterministic offline copilot: summarizes context, makes no API call."""

    async def ask(self, messages: list[ChatMessage], context: CopilotContext) -> AskResult:
        last_user = next((m.content for m in reversed(messages) if m.role == "user"), "")
        n = len(context.routes)
        if n == 0:
            return AskResult(f"(stub) You asked: '{last_user}'. No route is planned yet.", [], 1)
        fastest = min(context.routes, key=lambda r: r.duration_seconds)
        mins = round(fastest.duration_seconds / 60)
        return AskResult(
            f"(stub) You asked: '{last_user}'. You have {n} route(s); "
            f"the fastest is {fastest.summary} at about {mins} min.",
            [], 1,
        )
