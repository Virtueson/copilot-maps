from pydantic import BaseModel


class LatLng(BaseModel):
    lat: float
    lng: float


class TrafficInterval(BaseModel):
    start_index: int
    end_index: int
    speed: str


class RouteStep(BaseModel):
    instruction: str
    maneuver: str
    distance_meters: int
    location: LatLng


class Route(BaseModel):
    id: str
    summary: str
    distance_meters: int
    duration_seconds: int
    polyline: str
    traffic_intervals: list[TrafficInterval] = []
    steps: list[RouteStep] = []


class RoutePlanRequest(BaseModel):
    origin: LatLng
    destination: LatLng


class RoutePlanResponse(BaseModel):
    routes: list[Route]


class Place(BaseModel):
    id: str
    name: str
    lat: float
    lng: float
    address: str | None = None
    rating: float | None = None


class PlacesSearchRequest(BaseModel):
    query: str
    origin: LatLng
    polyline: str | None = None


class PlacesSearchResponse(BaseModel):
    mode: str
    places: list[Place]


class ChatMessage(BaseModel):
    role: str  # "user" | "assistant"
    content: str


class RouteSummary(BaseModel):
    summary: str
    distance_meters: int
    duration_seconds: int
    traffic: str  # "light" | "moderate" | "heavy"
    selected: bool


class CopilotContext(BaseModel):
    origin: LatLng
    selected_route_polyline: str | None = None
    routes: list[RouteSummary] = []


class CopilotAskRequest(BaseModel):
    messages: list[ChatMessage]
    context: CopilotContext


class CopilotAskResponse(BaseModel):
    reply: str
