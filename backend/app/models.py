from pydantic import BaseModel


class LatLng(BaseModel):
    lat: float
    lng: float


class Route(BaseModel):
    id: str
    summary: str
    distance_meters: int
    duration_seconds: int
    polyline: str


class RoutePlanRequest(BaseModel):
    origin: LatLng
    destination: LatLng


class RoutePlanResponse(BaseModel):
    routes: list[Route]
