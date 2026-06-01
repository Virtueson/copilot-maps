from fastapi import APIRouter, Depends

from app.config import get_planner
from app.models import RoutePlanRequest, RoutePlanResponse
from app.planners.base import RoutePlanner

router = APIRouter()


@router.post("/routes/plan", response_model=RoutePlanResponse)
async def plan_routes(
    request: RoutePlanRequest,
    planner: RoutePlanner = Depends(get_planner),
) -> RoutePlanResponse:
    routes = await planner.plan(request.origin, request.destination)
    return RoutePlanResponse(routes=routes)
