from app.models import CopilotContext, Place
from app.places.base import PlacesProvider
from app.places.search import search_with_fallback

SEARCH_PLACES_TOOL = {
    "name": "search_places",
    "description": (
        "Find places such as gas stations, restaurants, ATMs, or any category on the "
        "user's current route (or near them if no route is planned). Pass a natural "
        "language query like 'gas station' or 'french restaurant'."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "query": {
                "type": "string",
                "description": "What to search for, e.g. 'gas station' or 'coffee'.",
            }
        },
        "required": ["query"],
    },
}


def _format_places(mode: str, places: list[Place]) -> str:
    if not places:
        return "No matching places found."
    where = "on the route" if mode == "along_route" else "nearby"
    lines = [f"Found {len(places)} places {where}:"]
    for p in places[:5]:
        rating = f" {p.rating} stars" if p.rating is not None else ""
        lines.append(f"- {p.name}{rating} at {p.lat:.4f},{p.lng:.4f}")
    return "\n".join(lines)


async def execute_search_places(
    query: str, context: CopilotContext, places_provider: PlacesProvider
) -> str:
    mode, places = await search_with_fallback(
        places_provider, query, context.origin, context.selected_route_polyline
    )
    return _format_places(mode, places)
