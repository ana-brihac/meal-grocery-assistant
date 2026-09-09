from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.models.embedding_model import get_model
from app.routers import recommendations


@asynccontextmanager
async def lifespan(_app: FastAPI):
    # Load the (image-baked) embedding model during startup, before the server accepts
    # traffic. Otherwise the first /recommendations call pays the one-time load cost, which
    # exceeds the Java client's 5s timeout and makes rankBy=mealHistory silently fall back
    # to the non-ML ordering on the first request after every restart.
    get_model()
    yield


app = FastAPI(lifespan=lifespan)
app.include_router(recommendations.router)


@app.get("/ping")
async def ping():
    return {"status": "ok", "service": "python"}
