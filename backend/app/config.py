from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict

from app.places.base import PlacesProvider
from app.places.stub import StubPlacesProvider
from app.planners.base import RoutePlanner
from app.planners.stub import StubRoutePlanner


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")
    route_planner: str = "stub"
    google_routes_api_key: str = ""
    places_provider: str = "stub"
    google_places_api_key: str = ""
    copilot_provider: str = "stub"
    anthropic_api_key: str = ""
    # OpenAI-compatible provider (e.g. SumoPod). Swap models by changing model_name.
    model_api: str = ""
    model_base_url: str = "https://ai.sumopod.com/v1"
    model_name: str = "deepseek-v4-flash"
    supabase_url: str = ""
    supabase_service_key: str = ""


@lru_cache
def get_settings() -> Settings:
    return Settings()


def get_planner() -> RoutePlanner:
    settings = get_settings()
    if settings.route_planner == "google":
        from app.planners.google import GoogleRoutePlanner

        return GoogleRoutePlanner(api_key=settings.google_routes_api_key)
    return StubRoutePlanner()


def get_places_provider() -> PlacesProvider:
    settings = get_settings()
    if settings.places_provider == "google":
        from app.places.google import GooglePlacesProvider

        return GooglePlacesProvider(api_key=settings.google_places_api_key)
    return StubPlacesProvider()


def get_copilot():
    settings = get_settings()
    if settings.copilot_provider == "anthropic":
        from app.copilot.anthropic_agent import AnthropicCopilot

        return AnthropicCopilot(
            api_key=settings.anthropic_api_key,
            places_provider=get_places_provider(),
        )
    if settings.copilot_provider == "openai":
        from app.copilot.openai_compat import OpenAICompatCopilot

        return OpenAICompatCopilot(
            api_key=settings.model_api,
            base_url=settings.model_base_url,
            model=settings.model_name,
            places_provider=get_places_provider(),
        )
    from app.copilot.stub import StubCopilot

    return StubCopilot()
