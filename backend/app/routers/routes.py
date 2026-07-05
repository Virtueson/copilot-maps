import httpx
from fastapi import APIRouter, Depends, HTTPException

from app.config import get_planner
from app.models import RoutePlanRequest, RoutePlanResponse
from app.planners.base import RoutePlanner

router = APIRouter()


@router.post("/routes/plan", response_model=RoutePlanResponse)
async def plan_routes(
    request: RoutePlanRequest,
    planner: RoutePlanner = Depends(get_planner),
) -> RoutePlanResponse:
    try:
        routes = await planner.plan(request.origin, request.destination, request.language_code)
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=f"Route provider error: {exc}") from exc
    return RoutePlanResponse(routes=routes)
