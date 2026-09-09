# ml-service (Python / FastAPI)

## Current state: one real feature + a legacy ping

`ml-service/` is a FastAPI app with two routes:

- `POST /recommendations` — the recipe-recommendation endpoint (real).
- `GET /ping` — a cheap liveness check: `{"status": "ok", "service": "python"}`. The Java side no
  longer calls it, but the container healthcheck (compose + Dockerfile `HEALTHCHECK`) does, so it's
  load-bearing now — don't remove it.

There is **no database access** here. `ml-service` is a pure function: it receives candidate recipes
and meal history in the request body, embeds them, scores them, and returns a ranked list.

## What `POST /recommendations` does

Request/response schemas: `app/schemas/recommendation_schemas.py`. Shapes (snake_case on the wire):

```
POST /recommendations
{
  "candidates": [
    {"id": 1, "name": "Chicken Stir Fry",
     "ingredients": [{"name": "chicken", "quantity": 200, "unit": "g"}, ...]}
  ],
  "meal_history": [
    {"name": "grilled chicken", "logged_at": "2026-08-20T12:00:00"}
  ]
}
->
{"results": [{"id": 1, "name": "Chicken Stir Fry", "score": 0.82}, ...]}   # sorted by score desc
```

Pipeline (`app/routers/recommendations.py` → `app/services/`):

1. **`embedding_service.embed_recipe_text(candidate)`** — builds the string
   `"<name>: <ing> <qty> <unit>, <ing> <qty> <unit>, ..."` and encodes it. One vector per candidate.
   (Instructions are not embedded — the schema doesn't carry them; name + ingredients only.)
2. **`embedding_service.embed_meal_history(meal_history)`** — encodes each entry's `name` and returns
   the **mean** of those vectors. Empty history → a zero vector of the model's dimension.
   (`logged_at` is carried in the schema but not currently used — no recency weighting yet.)
3. **`recommendation_service.recommend(...)`** — cosine similarity between the history vector and each
   candidate vector (vectorized; zero-norm guarded), then returns `RecipeScore`s **sorted by score
   descending**. Empty candidate list → `[]`.
4. Any exception in the handler → `HTTP 500` with the message as `detail`.

## The embedding model

`app/models/embedding_model.py`:

```python
_MODEL_NAME = "all-MiniLM-L6-v2"   # sentence-transformers, 384-dim output
_model = None

def get_model():
    global _model
    if _model is None:
        _model = SentenceTransformer(_MODEL_NAME)
    return _model
```

- Loaded **once**, lazily, on the first call that needs it, and cached in a module global — not
  per request. The first `/recommendations` call (or the first test) is therefore slow.
- **First-ever load needs network access** — sentence-transformers downloads `all-MiniLM-L6-v2`
  (~80 MB) from Hugging Face into `~/.cache/huggingface` and reuses it after that.
- `all-MiniLM-L6-v2` is the only model name referenced anywhere; keep it that way (change it in one
  place if it ever needs to change).

## How the backend talks to it

`java-backend`'s `MlServiceClient` (`common/client/MlServiceClient.java`) is a Spring **`WebClient`**
(reactive) wrapper with a 5-second timeout:

```java
webClient.post().uri("/recommendations")
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue(request)
    .retrieve().bodyToMono(RecommendationResponse.class)
    .timeout(Duration.ofSeconds(5)).block();
```

Java DTOs `common/client/dto/RecommendationRequest.java` / `RecommendationResponse.java` mirror the
Pydantic schemas field-for-field (including `@JsonProperty("meal_history")` / `("logged_at")`). The
client configures its JSON codec with `JavaTimeModule` and `WRITE_DATES_AS_TIMESTAMPS` disabled so
`logged_at` is sent as an ISO-8601 string — a plain `WebClient` serializes `LocalDateTime` as a
numeric array, which the Pydantic `datetime` field rejects with `422`.

Call path: `RecipeService.searchRecipes` (when `rankBy=mealHistory`) →
`RecipeRankingService.rankByMealHistorySimilarity` assembles candidates from
`RecipeRepository`/`RecipeIngredientRepository` and meal history from `NutritionLogRepository.findAll()`
→ `MlServiceClient.getRecommendations` → reorders the `Recipe` list by returned score.

**Graceful degradation:** if `MlServiceClient` throws (connection refused, timeout, 5xx),
`RecipeService.rankByMealHistoryWithFallback` catches it and falls back to the default
fewest-ingredients ordering. `/api/recipes/search` never fails just because `ml-service` is down.

Base URL: `ml-service.base-url` in `application.yml` — `${ML_SERVICE_BASE_URL:http://localhost:8000}`.
The compose stack sets `ML_SERVICE_BASE_URL=http://ml-service:8000`; a plain `mvn spring-boot:run`
falls back to `localhost:8000`.

## Running it

It's containerized (`ml-service/Dockerfile`) and comes up with the rest of the stack via
`docker compose up --build` — no host port is published; `java-backend` reaches it at
`http://ml-service:8000` on the compose network. The image **bakes `all-MiniLM-L6-v2` in at build
time** and `app/main.py`'s FastAPI `lifespan` **preloads it into memory at startup**, so the
container needs no network at run time and the first `/recommendations` call is not cold. The
bake-vs-lazy-download tradeoff is documented in the Dockerfile.

To run it standalone on the host (e.g. while editing the recommendation code):

```
cd ml-service
python -m venv .venv && source .venv/bin/activate    # .venv/Scripts/activate on Windows
pip install -r requirements.txt                       # pins fastapi, pydantic, sentence-transformers,
                                                      # numpy, uvicorn, pytest (see the file)
uvicorn app.main:app --port 8000
```

`sentence-transformers` pulls in `torch` — a large download. On Linux, `pip install torch
--index-url https://download.pytorch.org/whl/cpu` first gets the ~200 MB CPU wheel instead of the
~2 GB CUDA build. Running out of `/mnt/...` under WSL makes imports and installs noticeably slower;
a native Linux path is much faster.

Smoke test:

```
curl -s -X POST http://localhost:8000/recommendations -H "Content-Type: application/json" -d '{
  "candidates":[
    {"id":1,"name":"Chicken Stir Fry","ingredients":[{"name":"chicken","quantity":200,"unit":"g"}]},
    {"id":2,"name":"Pancakes","ingredients":[{"name":"flour","quantity":100,"unit":"g"}]}],
  "meal_history":[{"name":"chicken curry","logged_at":"2026-08-22T19:00:00"}]}'
```

Expect a `results` array of two `{id,name,score}` objects, id 1 scoring higher.

## Tests

`pytest` (from `ml-service/`) — 16 tests across two files:

- `tests/test_recommendation_service.py` — the service functions directly: embedding shape,
  cosine ordering (1.0 / -1.0), empty candidate list → `[]`.
- `tests/test_recommendations_endpoint.py` — `TestClient` against `POST /recommendations`: the
  `RecommendationResponse` shape, score-descending order, the router's `Exception → HTTP 500`
  mapping, and request-validation `422`s (missing fields, non-numeric quantity, bad `logged_at`).

```
cd ml-service && pytest -q
```

`.github/workflows/ci.yml` runs this on every PR and on pushes to `main` / `ana-brihac/**`, with the
model restored from an `actions/cache`. First local run is slow (cold torch/transformers import +
one-time model download); subsequent runs are fast. `requirements.txt` versions are all pinned.

## Still not done

- No recency weighting on meal history; `logged_at` is parsed but unused.
