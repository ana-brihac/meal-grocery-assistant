# Handoff: meal-grocery-assistant

## Purpose

A grocery-receipt-to-nutrition tracker. Upload a receipt photo → it's OCR'd (Gemini) and parsed
into inventory items. Separately, log what you ate → the app looks up nutrition facts (USDA
FoodData Central, cached locally) and lets you pull date-range summaries of nutrition and spending,
combined into one dashboard endpoint. Phase 4 added recipes: search for recipes fully makeable from
a list of ingredients, and log a recipe eaten (sums nutrition across its ingredients, scaled by
servings). Phase 5 added ML recipe recommendations: `GET /api/recipes/search?rankBy=mealHistory`
re-ranks the search results by embedding similarity to your logged meal history, computed by the
Python `ml-service` (sentence-transformers). Falls back to the Phase 4 ordering if `ml-service`
is unavailable.

## Architecture at a glance

- **`java-backend/`** — Spring Boot 3.3.4 (Java 21) REST API. Does essentially all the real work:
  receipts, inventory, nutrition, spending, dashboard. Runs on `:8080`.
- **`ml-service/`** — Python/FastAPI. As of Phase 5 it hosts one real feature: `POST /recommendations`,
  which embeds candidate recipes and meal history with sentence-transformers (`all-MiniLM-L6-v2`) and
  ranks candidates by cosine similarity. Still has the legacy `/ping` route. Not containerized (empty
  `Dockerfile`, no `docker-compose` service) — run it directly with `uvicorn`. See
  [`docs/ml-service.md`](docs/ml-service.md).
- **Postgres 16** — schema is hand-written SQL under `db/init/`, no migration tool. Runs on
  `:5432` via `docker-compose up -d postgres`.

```
receipt image → java-backend → Gemini (OCR) → inventory_items
nutrition log → java-backend → USDA FoodData Central (cached) → nutrition_info / nutrition_log
recipe search → java-backend → recipes / recipe_ingredients (loaded from a CSV on startup)
   rankBy=mealHistory ↓
                  java-backend → ml-service POST /recommendations (sentence-transformers)
                       ↓
                  Postgres (pantrydb)
```

Full detail: [`docs/architecture.md`](docs/architecture.md).

## Getting started

```
cp .env.example .env        # fill in USDA_API_KEY and OCR_API_KEY
docker-compose up -d postgres
cd java-backend
export USDA_API_KEY=...     # mvn spring-boot:run doesn't read .env automatically
export OCR_API_KEY=...
mvn spring-boot:run
```

Verify with:

```
curl -X POST http://localhost:8080/api/nutrition/log \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"itemName":"banana","quantityGrams":118}'
```

A `201` means Postgres, the schema, and the USDA integration are all working. Full setup detail,
including a caveat about reusing an old Postgres volume: [`docs/setup.md`](docs/setup.md).

For recipes specifically, add `java-backend/src/main/resources/data/recipes.csv` (see
`RecipeDataLoader.java` for the column format) before starting the backend, then:

```
curl "http://localhost:8080/api/recipes/search?ingredients=<name>&ingredients=<name>"
```

Add `&rankBy=mealHistory` to re-rank by meal-history similarity (Phase 5) — this needs `ml-service`
running (`cd ml-service && uvicorn app.main:app --port 8000`, after `pip install -r requirements.txt`
into a venv); if it's down the search still succeeds, just with the default ordering.

See `docs/testing.md`'s Recipes section for the full manual verification checklist, and
`docs/ml-service.md` for the recommendation service.

## Where to go next

| Doc | Covers |
|---|---|
| [`docs/architecture.md`](docs/architecture.md) | Services, package layout, data flows, conventions |
| [`docs/setup.md`](docs/setup.md) | Prerequisites, env vars, running the full stack locally |
| [`docs/backend-api.md`](docs/backend-api.md) | Every endpoint, request/response shapes, error handling, the async receipt pipeline |
| [`docs/database.md`](docs/database.md) | Tables, schema-validation gotchas, the entity/column type pairing rule |
| [`docs/testing.md`](docs/testing.md) | What `mvn test` actually covers (and doesn't), manual verification checklist |
| [`docs/third-party-integrations.md`](docs/third-party-integrations.md) | USDA FoodData Central and Gemini — endpoints, config, known rough edges |
| [`docs/known-issues.md`](docs/known-issues.md) | Open issues, recently-fixed bugs worth knowing about, TODOs |
| [`docs/ml-service.md`](docs/ml-service.md) | The Python `ml-service` — the Phase 5 recommendation endpoint, how it's called, how to run it |

## If you only read one more thing

[`docs/known-issues.md`](docs/known-issues.md) — it lists what's actually broken or unfinished
right now (empty test files masquerading as coverage, an unwired `AsyncConfig`, an uncontainerized
`ml-service`, silent failure paths in receipt upload) so you don't have to rediscover any of it the
hard way.
