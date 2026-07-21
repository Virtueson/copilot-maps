from app.copilot.registry import Tool
from app.models import CopilotContext, Place
from app.places.base import PlacesProvider
from app.places.search import search_with_fallback

_SEARCH_PLACES_DESCRIPTION = (
    "Find places such as gas stations, restaurants, ATMs, or any category on the "
    "user's current route (or near them if no route is planned). "
    "Call this EVERY time the user asks to find or locate places — even if you "
    "already searched earlier in the conversation — because the route and the "
    "driver's position keep changing as they drive; never answer a place request "
    "from earlier results or memory. "
    "Pass ONLY the place category as the query, e.g. 'gas station', 'coffee', or "
    "'french restaurant'. Do NOT add road, street, route, city, area, or place "
    "names — not even ones that appear in the trip context. The search is already "
    "restricted to the driver's route and current location automatically; adding "
    "location words to the query breaks it (it will search the wrong area)."
)
# Google returns up to 20 hits; only this many are shown to the LLM (the full list
# still goes to the app for map pins).
_MAX_PLACES_SHOWN = 5

_SEARCH_PLACES_PARAMETERS = {
    "type": "object",
    "properties": {
        "query": {
            "type": "string",
            "description": (
                "The place category ONLY, e.g. 'gas station' or 'coffee'. "
                "No road, city, or area names — the route/location is applied "
                "automatically."
            ),
        }
    },
    "required": ["query"],
}

# Kept for backward compatibility / native-Anthropic schema shape.
SEARCH_PLACES_TOOL = {
    "name": "search_places",
    "description": _SEARCH_PLACES_DESCRIPTION,
    "input_schema": _SEARCH_PLACES_PARAMETERS,
}


def _format_places(mode: str, places: list[Place]) -> str:
    if not places:
        return "No matching places found."
    where = "on the route" if mode == "along_route" else "nearby"
    shown = places[:_MAX_PLACES_SHOWN]
    # Report both counts: the model may only cite the places it can actually see,
    # but the total still tells it whether such places are plentiful or scarce.
    # No coordinates: the list is already nearest-first, the model never needs
    # lat/lng (it would only read them aloud), and the app gets real coordinates
    # via the structured places list.
    lines = [f"Found {len(places)} places {where}. Top {len(shown)}:"]
    for p in shown:
        rating = f" {p.rating} stars" if p.rating is not None else ""
        lines.append(f"- {p.name}{rating}")
    return "\n".join(lines)


async def execute_search_places(
    query: str,
    context: CopilotContext,
    places_provider: PlacesProvider,
    outputs=None,
) -> str:
    mode, places = await search_with_fallback(
        places_provider, query, context.origin, context.selected_route_polyline
    )
    if outputs is not None:
        outputs.places = list(places)
    return _format_places(mode, places)


def build_tools(places_provider: PlacesProvider) -> list[Tool]:
    """Single registration point for every copilot tool.

    To add a capability, write its executor and append one `Tool(...)` here; all
    providers (OpenAI-compatible and Anthropic-native) pick it up automatically.
    """

    async def _search_places(args: dict, context: CopilotContext, outputs) -> str:
        return await execute_search_places(
            args.get("query", ""), context, places_provider, outputs
        )

    return [
        Tool(
            name="search_places",
            description=_SEARCH_PLACES_DESCRIPTION,
            parameters=_SEARCH_PLACES_PARAMETERS,
            executor=_search_places,
        ),
        # Add future tools here, e.g.:
        # Tool(name="get_weather", description=..., parameters=..., executor=_get_weather),
    ]
