# Setup / environment

## Prerequisites

- JDK 21 and Maven (the backend is Spring Boot 3.3.4 / Java 21, see `java-backend/pom.xml`)
- Docker + Docker Compose, for Postgres
- A USDA FoodData Central API key and a Gemini API key (see `docs/third-party-integrations.md`)

## 1. Environment variables

Copy `.env.example` to `.env` and fill in real values:

```
USDA_API_KEY=your_usda_api_key_here
OCR_API_KEY=your_ocr_api_key_here
```

- `USDA_API_KEY` — get one free at https://fdc.nal.usda.gov/api-key-signup, or use `DEMO_KEY` for
  light manual testing. `DEMO_KEY` is shared and rate-limited (~30 requests/hour) — a 502 with
  `"USDA API Rate Limit Exceeded"` means you've tripped that, not a bug.
- `OCR_API_KEY` — a Gemini API key, used by `OcrClient` for receipt parsing.

The Spring app reads these from the process environment (`${USDA_API_KEY}` /
`${OCR_API_KEY}` placeholders in `application.yml`) — `.env` isn't auto-loaded by
`mvn spring-boot:run`, so `export` them yourself or use a tool like `direnv` / your IDE's run
config to inject them.

## 2. Start Postgres

```
docker-compose up -d postgres
```

This runs Postgres 16 on `localhost:5432` (db `pantrydb`, user/pass `postgres`/`postgres`) and, **on
first container start only**, applies `db/init/001_init_schema.sql`, `002_nutrition_spending.sql`,
`003_recipes.sql`, and `004_user_preference.sql` (the last of these also seeds one default
preferences row, `id=1`).

**Caveat:** if you're reusing an existing `pantry_pg_data` Docker volume from before one of these
tables/columns existed, its init script will *not* rerun automatically — Postgres only runs
`docker-entrypoint-initdb.d` scripts against a fresh data directory. This is very likely to bite you
specifically for `003_recipes.sql` if your volume predates Phase 4 (i.e. you had Postgres running
for nutrition/spending/preferences before recipes existed) — the backend will fail to start with a
Hibernate schema-validation error mentioning `recipes.source`, `recipe_ingredients`, or
`nutrition_log.recipe_id` as missing. If you see that, or `nutrition_info`/`nutrition_log`/
`user_preference` missing instead, apply the relevant migration by hand, e.g.:

```
docker exec -i <postgres-container> psql -U postgres -d pantrydb < db/init/002_nutrition_spending.sql
docker exec -i <postgres-container> psql -U postgres -d pantrydb < db/init/003_recipes.sql
docker exec -i <postgres-container> psql -U postgres -d pantrydb < db/init/004_user_preference.sql
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

## 4. ml-service (optional, not required for the backend's core features)

`ml-service/` is a bare FastAPI scaffold — see `docs/ml-service.md`. Its `Dockerfile` and
`requirements.txt` are currently empty, so it isn't runnable via Docker yet. To run it manually:

```
cd ml-service
pip install fastapi uvicorn
uvicorn app.main:app --reload --port 8000
```

The backend expects it at `http://localhost:8000` (`ml-service.base-url` in `application.yml`) and
only uses it for one passthrough endpoint, `GET /api/inventory/ping-python`.

## Verifying the stack is up

```
curl -X POST http://localhost:8080/api/nutrition/log \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"itemName":"banana","quantityGrams":118}'
```

A `201` means Postgres, the nutrition schema, and the USDA integration are all working. See
`docs/testing.md` for the full manual verification checklist.
