import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.routers import copilot, places, routes

# Uvicorn only configures its own loggers, so app loggers ("copilot.*") need a
# root handler or their INFO records are dropped.
logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")

app = FastAPI(title="Copilot Maps Backend")

# Permissive CORS for local development only.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(routes.router)
app.include_router(places.router)
app.include_router(copilot.router)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}
