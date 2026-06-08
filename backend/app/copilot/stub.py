from app.models import ChatMessage, CopilotContext


class StubCopilot:
    """Deterministic offline copilot: summarizes context, makes no API call."""

    async def ask(self, messages: list[ChatMessage], context: CopilotContext) -> str:
        last_user = next((m.content for m in reversed(messages) if m.role == "user"), "")
        n = len(context.routes)
        if n == 0:
            return f"(stub) You asked: '{last_user}'. No route is planned yet."
        fastest = min(context.routes, key=lambda r: r.duration_seconds)
        mins = round(fastest.duration_seconds / 60)
        return (
            f"(stub) You asked: '{last_user}'. You have {n} route(s); "
            f"the fastest is {fastest.summary} at about {mins} min."
        )
