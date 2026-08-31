from fastapi import FastAPI

from app.routers import recommendations

app = FastAPI()
app.include_router(recommendations.router)

@app.get("/ping")
async def ping():
    return {"status": "ok", "service": "python"}
