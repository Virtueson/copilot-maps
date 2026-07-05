from app.models import CopilotContext

# Shared persona + trip-context formatting used by every LLM copilot provider
# (Anthropic-native and OpenAI-compatible alike).

PERSONA = (
    "You are Copilot, a concise in-car navigation assistant. Answer in one or two "
    "short, spoken-style sentences — the driver will hear this aloud later. Use the "
    "current trip context for route and traffic questions. Use the search_places tool "
    "to find gas, food, or other places on the route or nearby. If you don't have the "
    "data, say so briefly. Reply in the same language the user used."
)


def format_context(context: CopilotContext) -> str:
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
