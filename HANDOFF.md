# Handoff: meal-grocery-assistant

## Purpose

A grocery-receipt-to-nutrition tracker with meal planning. Upload a receipt photo → it's OCR'd
(Gemini) and parsed into inventory items and ingredient prices. Separately, log what you ate → the
app looks up nutrition facts (USDA FoodData Central, cached locally, with a Gemini fallback for
misses) and lets you pull date-range summaries of nutrition and spending, combined into one
dashboard endpoint.

On top of that:

- **Recipes** — search for recipes fully makeable from a list of ingredients, log a recipe eaten
  (sums nutrition across its ingredients, scaled by servings), and add/edit recipes at runtime.
  `GET /api/recipes/search?rankBy=mealHistory` re-ranks results by embedding similarity to your
  logged meal history, computed by the Python `ml-service` (sentence-transformers); it falls back
  to the default ingredient-count ordering if `ml-service` is unavailable.
- **Ingredient prices** — a price catalog fed by receipt uploads, manual entry
  (`POST /api/prices`), or an OCR'd shelf price-tag photo (`POST /api/prices/from-photo`).
- **Meal plans** — `POST /api/mealplan/generate` builds a week of slots so each day's calories land
  within ±100 kcal of the target, protein/fiber clear ~90% of target, and the whole plan stays
  under the weekly budget. Plans are persisted; you can browse history, swap a single meal, and
  reuse a past plan for a new week. `mealPrepBatchSize` (a preference) lets one cooked recipe cover
  2–3 consecutive same-meal slots.
- **Grocery lists** — `POST /api/grocerylist/generate` turns a chosen plan into a shopping list:
  aggregate its ingredients, drop what's already in inventory, price the rest. Ingredients with no
  usable price are still listed and named in the response's `missingPrices` (with a `reason`:
  `NO_PRICE_ON_FILE` or `NEEDS_GRAMS_PER_ITEM`) so the client can prompt the user to fill them in;
  it's recomputed on every grocery-list response, so it clears as prices are added.

## Architecture at a glance

- **`java-backend/`** — Spring Boot 3.3.4 (Java 21) REST API. Does essentially all the real work:
  receipts, inventory, nutrition, spending, dashboard, recipes, ingredient prices, meal plans,
  grocery lists. Runs on `:8080`.
- **`ml-service/`** — Python/FastAPI. Hosts one real feature: `POST /recommendations`, which embeds
  candidate recipes and meal history with sentence-transformers (`all-MiniLM-L6-v2`) and ranks
  candidates by cosine similarity. Still has the legacy `/ping` route. Containerized — `ml-service/Dockerfile`
  bakes the model into the image and preloads it at startup; it comes up with the rest of the stack
  via `docker compose`. See [`docs/ml-service.md`](docs/ml-service.md).
- **Postgres 16** — schema is hand-written SQL under `db/init/`, no migration tool. Runs on
  `:5432`.

All three services run together with `docker compose up --build` (Postgres + `ml-service` +
`java-backend`, health-gated startup, API keys from a repo-root `.env`). Running the backend
directly with `mvn spring-boot:run` against `docker compose up -d postgres` is still fine for
iterating on Java. Full VM deployment (nginx + HTTPS): [`docs/deployment.md`](docs/deployment.md).

```
receipt image → java-backend → Gemini (OCR) → inventory_items + ingredient_price
nutrition log → java-backend → USDA FoodData Central (cached, Gemini fallback) → nutrition_info / nutrition_log
recipe search → java-backend → recipes / recipe_ingredients (CSV on startup, or POST/PUT /api/recipes)
   rankBy=mealHistory ↓
                  java-backend → ml-service POST /recommendations (sentence-transformers)
meal plan     → java-backend → MealPlanOptimizer (nutrition + ingredient_price) → meal_plan / meal_plan_slot
grocery list  → java-backend → aggregate slots − inventory → grocery_list_item
                       ↓
                  Postgres (pantrydb)
```

Full detail: [`docs/architecture.md`](docs/architecture.md).

## Getting started

Whole stack in containers:

```
# create .env in the repo root with USDA_API_KEY and OCR_API_KEY (see .env.example / docs/setup.md)
docker compose up --build            # Postgres + ml-service + java-backend, :8080
```

Or run just the backend against a containerized Postgres (handy while editing Java):

```
docker compose up -d postgres
cd java-backend
export USDA_API_KEY=...     # mvn spring-boot:run doesn't read .env automatically
export OCR_API_KEY=...
export ML_SERVICE_BASE_URL=http://localhost:8000   # only if you also run ml-service locally
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

For recipes, add `java-backend/src/main/resources/data/recipes.csv` (see `RecipeDataLoader.java`
for the column format) before starting the backend, then:

```
curl "http://localhost:8080/api/recipes/search?ingredients=<name>&ingredients=<name>"
```

Add `&rankBy=mealHistory` to re-rank by meal-history similarity — this needs `ml-service` running.
It comes up automatically with `docker compose up`; to run it standalone,
`cd ml-service && uvicorn app.main:app --port 8000` after `pip install -r requirements.txt` into a
venv. If it's down the search still succeeds, just with the default ordering.

To try meal planning: seed a few prices (`POST /api/prices` or a receipt upload), then
`POST /api/mealplan/generate` with `{"weekStartDate":"2026-09-01"}`, then
`POST /api/grocerylist/generate` with the returned plan id.

See `docs/testing.md` for the full manual verification checklist, and `docs/ml-service.md` for the
recommendation service.

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
| [`docs/ml-service.md`](docs/ml-service.md) | The Python `ml-service` — the recommendation endpoint, how it's called, how to run it |

## Recent changes (2026-09-08)

- **Full stack is containerized.** `docker compose up --build` brings up Postgres, `ml-service`,
  and `java-backend` together — health-gated startup (`java-backend` waits for Postgres's
  `pg_isready`), API keys substituted from a repo-root `.env` (see `.env.example`), `db/init/`
  mount and `pantry_pg_data` volume unchanged. `java-backend/Dockerfile` is a multi-stage Maven →
  slim-JRE build; `ml-service/Dockerfile` bakes `all-MiniLM-L6-v2` into the image and preloads it
  at startup. `java-backend` is published on `127.0.0.1:8080` (reach it through a reverse proxy on
  a server — see [`docs/deployment.md`](docs/deployment.md), a full Oracle Cloud VM + nginx +
  Let's Encrypt guide).
- **`rankBy=mealHistory` actually uses the ML signal now.** Two latent bugs made it silently fall
  back to the default ordering whenever any meal history existed: `MlServiceClient` serialized
  `logged_at` as a numeric array (Pydantic rejected it 422), and the first post-restart call
  cold-loaded the model past the 5s client timeout. Fixed (ISO-8601 serialization + startup model
  preload). New `db/init/008_users_seed.sql` seeds the default `users` id 1 row so
  `POST /api/nutrition/log {"userId":1}` works on a fresh volume.
- **`mvn test` is now 196** (added `MlServiceClientTest` request-body coverage); `pytest` still 16.

## Recent changes (2026-09-01)

- **Uniform response shape.** Controllers now return raw DTOs — the old `ApiResponse<T>
  {success, data, error}` envelope is gone. Errors are RFC 9457 `ProblemDetail`
  (`application/problem+json`) from `GlobalExceptionHandler`: `NotFoundException` → 404,
  `BadRequestException` → 400, `NutritionApiException` → 502, else → 500. `POST
  /api/receipts/upload` is now `202`; `POST /api/mealplan/generate` and
  `POST /api/grocerylist/generate` are `201`. See [`docs/backend-api.md`](docs/backend-api.md).
- **Test coverage + CI.** `mvn test` covers services, every controller, and a `@WebMvcTest`
  for error mapping; `pytest` is 16 (incl. a `TestClient` test of `POST /recommendations`).
  [`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs both on every PR and on pushes to
  `main` / `ana-brihac/**`.

## If you only read one more thing

[`docs/known-issues.md`](docs/known-issues.md) — it lists what's actually rough or unfinished right
now (an unwired `AsyncConfig`, silent failure paths in receipt upload, the meal-plan optimizer's
untuned scoring weights, name-match-only inventory subtraction) so you don't have to rediscover any
of it the hard way.
