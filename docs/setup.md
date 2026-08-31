# Setup / environment

## Prerequisites

- JDK 21 and Maven (the backend is Spring Boot 3.3.4 / Java 21, see `java-backend/pom.xml`)
- Docker + Docker Compose, for Postgres
- A USDA FoodData Central API key and a Gemini API key (see `docs/third-party-integrations.md`)

## 1. Environment variables

Create a `.env` file in the repo root with real values (it's gitignored — never commit it):

```
USDA_API_KEY=your_usda_api_key_here
OCR_API_KEY=your_gemini_api_key_here
```

- `USDA_API_KEY` — get one free at https://fdc.nal.usda.gov/api-key-signup, or use `DEMO_KEY` for
  light manual testing. `DEMO_KEY` is shared and rate-limited (~30 requests/hour) — a 502 with
  `"USDA API Rate Limit Exceeded"` means you've tripped that, not a bug.
- `OCR_API_KEY` — a Gemini API key. Used for receipt parsing (`OcrClient`), shelf price-tag OCR,
  and the fallback per-100g nutrition estimate when USDA has no match (`NutritionAiClient`). If
  Google reports the key leaked and disables it, rotate it and delete any null-macro rows from
  `nutrition_info` so those lookups retry.

The Spring app reads these from the process environment (`${USDA_API_KEY}` /
`${OCR_API_KEY}` placeholders in `application.yml`) — `.env` isn't auto-loaded by
`mvn spring-boot:run`, so `export` them yourself or use a tool like `direnv` / your IDE's run
config to inject them.

## 2. Start Postgres

```
docker-compose up -d postgres
```

This runs Postgres 16 on `localhost:5432` (db `pantrydb`, user/pass `postgres`/`postgres`) and, **on
first container start only**, applies every script in `db/init/` — `001_init_schema.sql` through
`007_user_preference_mealprep.sql` (`004` also seeds one default preferences row, `id=1`).

**Caveat:** if you're reusing an existing `pantry_pg_data` Docker volume from before one of these
tables/columns existed, its init script will *not* rerun automatically — Postgres only runs
`docker-entrypoint-initdb.d` scripts against a fresh data directory. The backend then fails to
start with a Hibernate schema-validation error naming a missing table/column (e.g. `meal_plan`,
`ingredient_price`, `user_preference.meal_prep_batch_size`, `recipes.source`). Either recreate the
volume (`docker-compose down -v && docker-compose up -d postgres` — wipes all data) or apply the
missing scripts by hand:

```
docker exec -i <postgres-container> psql -U postgres -d pantrydb < db/init/005_mealplan_grocerylist.sql
docker exec -i <postgres-container> psql -U postgres -d pantrydb < db/init/006_pricing.sql
docker exec -i <postgres-container> psql -U postgres -d pantrydb < db/init/007_user_preference_mealprep.sql
```

## 3. Run the backend

```
cd java-backend
mvn spring-boot:run
```

- Defaults to port **8080** — override with `SERVER_PORT` if it's taken.
- Connects to `jdbc:postgresql://localhost:5432/pantrydb` (see `application.yml`).
- `ddl-auto: validate` — the app will refuse to start if the live schema doesn't exactly match the
  JPA entities (column types included). See `docs/database.md`.

## 4. ml-service (optional — only needed for `GET /api/recipes/search?rankBy=mealHistory`)

`ml-service/` is a FastAPI app hosting the recommendation endpoint — see `docs/ml-service.md`.
`requirements.txt` is filled in and pinned, but the `Dockerfile` is still empty and there's no
`docker-compose` service for it, so run it manually:

```
cd ml-service
python -m venv .venv && source .venv/bin/activate     # .venv/Scripts/activate on Windows
pip install -r requirements.txt                        # large — pulls torch via sentence-transformers
uvicorn app.main:app --port 8000
```

The backend expects it at `http://localhost:8000` (`ml-service.base-url` in `application.yml`). It's
called only by `RecipeRankingService.rankByMealHistorySimilarity` when a recipe search passes
`rankBy=mealHistory`; every other endpoint works without it, and even that search falls back to the
default ranking if `ml-service` is down. The first request triggers a one-time ~80 MB model
download from Hugging Face.

## Verifying the stack is up

```
curl -X POST http://localhost:8080/api/nutrition/log \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"itemName":"banana","quantityGrams":118}'
```

A `201` means Postgres, the nutrition schema, and the USDA integration are all working. See
`docs/testing.md` for the full manual verification checklist.
