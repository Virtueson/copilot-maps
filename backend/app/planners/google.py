import httpx

from app.models import LatLng, Route, TrafficInterval

_COMPUTE_ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
_FIELD_MASK = (
    "routes.polyline.encodedPolyline,routes.duration,"
    "routes.distanceMeters,routes.description,"
    "routes.travelAdvisory.speedReadingIntervals"
)


def _parse_intervals(item: dict) -> list[TrafficInterval]:
    raw = item.get("travelAdvisory", {}).get("speedReadingIntervals", [])
    intervals: list[TrafficInterval] = []
    for iv in raw:
        intervals.append(
            TrafficInterval(
                # startPolylinePointIndex is omitted by the API when it is 0.
                start_index=int(iv.get("startPolylinePointIndex", 0)),
                end_index=int(iv.get("endPolylinePointIndex", 0)),
                speed=iv.get("speed", "SPEED_UNSPECIFIED"),
            )
        )
    return intervals


class GoogleRoutePlanner:
    def __init__(self, api_key: str):
        self._api_key = api_key

    async def plan(self, origin: LatLng, destination: LatLng) -> list[Route]:
        body = {
            "origin": {"location": {"latLng": {"latitude": origin.lat, "longitude": origin.lng}}},
            "destination": {"location": {"latLng": {"latitude": destination.lat, "longitude": destination.lng}}},
            "travelMode": "DRIVE",
            "routingPreference": "TRAFFIC_AWARE",
            "computeAlternativeRoutes": True,
            "extraComputations": ["TRAFFIC_ON_POLYLINE"],
        }
        headers = {
            "Content-Type": "application/json",
            "X-Goog-Api-Key": self._api_key,
            "X-Goog-FieldMask": _FIELD_MASK,
        }
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.post(_COMPUTE_ROUTES_URL, json=body, headers=headers)
        resp.raise_for_status()

        routes: list[Route] = []
        for i, item in enumerate(resp.json().get("routes", [])[:3]):
            duration = item.get("duration", "0s")
            seconds = int(duration[:-1]) if duration.endswith("s") else 0
            routes.append(
                Route(
                    id=f"route-{i}",
                    summary=item.get("description") or f"Route {i + 1}",
                    distance_meters=int(item.get("distanceMeters", 0)),
                    duration_seconds=seconds,
                    polyline=item["polyline"]["encodedPolyline"],
                    traffic_intervals=_parse_intervals(item),
                )
            )
        return routes
