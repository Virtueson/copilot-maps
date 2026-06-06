from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.routers import places, routes

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


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}
