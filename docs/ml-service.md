# ml-service (Python / FastAPI)

## Current state: scaffold only

`ml-service/` is a FastAPI app with a single route:

```python
# ml-service/app/main.py
@app.get("/ping")
async def ping():
    return {"status": "ok", "service": "python"}
```

That's the entire application. There's no ML functionality here yet despite the directory name —
treat it as a placeholder for future work, not a broken feature.

- `ml-service/Dockerfile` — **empty file**. Not containerized; `docker-compose.yml` doesn't define
  a service for it either (only `postgres` is defined there).
- `ml-service/requirements.txt` — **empty file**. No pinned dependencies, even though the code
  imports `fastapi`.

## How the backend talks to it

`java-backend`'s `MlServiceClient` (`common/client/MlServiceClient.java`) is a thin Spring
`RestClient` wrapper:

```java
restClient.get().uri("/ping").retrieve().body(String.class);
```

Base URL comes from `ml-service.base-url` in `application.yml`, defaulting to
`http://localhost:8000`. It's only reachable through the backend via
`GET /api/inventory/ping-python` (`InventoryController` → `InventoryService.pingDummyEndpoint`) —
the naming ("dummy", "ping-python") signals this was wired up to prove connectivity, not as a real
feature.

## Running it locally

Since `requirements.txt` is empty, install manually:

```
cd ml-service
pip install fastapi uvicorn
uvicorn app.main:app --reload --port 8000
```

Then `curl http://localhost:8080/api/inventory/ping-python` (through the backend) or
`curl http://localhost:8000/ping` (directly) should both return the ping payload.

## If you're picking this up

Before adding real ML functionality here:

1. Fill in `requirements.txt` with actual pinned versions (`fastapi`, `uvicorn`, plus whatever the
   feature needs).
2. Fill in the `Dockerfile` and add a service entry to `docker-compose.yml` so the whole stack can
   come up with one command — right now only Postgres does.
3. Decide on an actual contract between `java-backend` and this service (request/response shapes,
   error handling — `MlServiceClient` currently has none) before building endpoints beyond `/ping`.
