import httpx
from fastapi import APIRouter, Depends, HTTPException

from app.config import get_places_provider
from app.models import PlacesSearchRequest, PlacesSearchResponse
from app.places.base import PlacesProvider

router = APIRouter()


@router.post("/places/search", response_model=PlacesSearchResponse)
async def search_places(
    request: PlacesSearchRequest,
    provider: PlacesProvider = Depends(get_places_provider),
) -> PlacesSearchResponse:
    places = []
    mode = "nearby"
    try:
        if request.polyline:
            places = await provider.along_route(request.query, request.polyline)
            mode = "along_route"
        if not places:
            places = await provider.nearby(request.query, request.origin)
            mode = "nearby"
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=f"Places provider error: {exc}") from exc
    return PlacesSearchResponse(mode=mode, places=places)
