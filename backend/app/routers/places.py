import httpx
from fastapi import APIRouter, Depends, HTTPException

from app.config import get_places_provider
from app.models import PlacesSearchRequest, PlacesSearchResponse
from app.places.base import PlacesProvider
from app.places.search import search_with_fallback

router = APIRouter()


@router.post("/places/search", response_model=PlacesSearchResponse)
async def search_places(
    request: PlacesSearchRequest,
    provider: PlacesProvider = Depends(get_places_provider),
) -> PlacesSearchResponse:
    try:
        mode, places = await search_with_fallback(
            provider, request.query, request.origin, request.polyline
        )
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=f"Places provider error: {exc}") from exc
    return PlacesSearchResponse(mode=mode, places=places)
