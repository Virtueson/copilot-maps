from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict

from app.planners.base import RoutePlanner
from app.planners.stub import StubRoutePlanner


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")
    route_planner: str = "stub"
    google_routes_api_key: str = ""


@lru_cache
def get_settings() -> Settings:
    return Settings()


def get_planner() -> RoutePlanner:
    settings = get_settings()
    if settings.route_planner == "google":
        from app.planners.google import GoogleRoutePlanner

        return GoogleRoutePlanner(api_key=settings.google_routes_api_key)
    return StubRoutePlanner()
