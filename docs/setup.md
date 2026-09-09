# Setup / environment

## Prerequisites

- Docker + Docker Compose (the whole stack runs in containers)
- A USDA FoodData Central API key and a Gemini API key (see `docs/third-party-integrations.md`)
- JDK 21 and Maven — only if you want to run the backend outside a container while editing Java
  (Spring Boot 3.3.4 / Java 21, see `java-backend/pom.xml`)

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

`docker compose` reads `.env` from the repo root automatically for `${VAR}` substitution and passes
`USDA_API_KEY` / `OCR_API_KEY` through to the `java-backend` container. A plain `mvn spring-boot:run`
does *not* auto-load `.env` (see the local-dev path below).

## 2. Run the whole stack

```
docker compose up --build
```

Brings up Postgres + `ml-service` + `java-backend` on the compose network, health-gated
(`java-backend` waits for Postgres to pass `pg_isready`; it doesn't block on `ml-service`, which
degrades gracefully). `java-backend` is published on `127.0.0.1:8080`. `ml-service` bakes the
embedding model into its image and preloads it at startup, so the first `rankBy=mealHistory` call
is not cold. `docker compose down` stops everything and keeps the `pantry_pg_data` volume.

On **first** start Postgres applies every script in `db/init/` — `001_init_schema.sql` through
`008_users_seed.sql` (`004` seeds the default preferences row `id=1`, `008` seeds the default
`users` row `id=1`).

**Volume caveat:** `db/init/` scripts only run against a fresh data directory. If you're reusing an
older `pantry_pg_data` volume, new scripts won't rerun and `java-backend` fails to boot with a
Hibernate schema-validation error naming a missing table/column (e.g. `meal_plan`, `ingredient_price`,
`user_preference.meal_prep_batch_size`, `recipes.source`), or `POST /api/nutrition/log` 500s with a
`users` foreign-key violation. Either recreate the volume (`docker compose down -v && docker compose
up --build` — wipes all data) or apply the missing scripts by hand:

```
docker compose exec -T postgres psql -U postgres -d pantrydb < db/init/005_mealplan_grocerylist.sql
docker compose exec -T postgres psql -U postgres -d pantrydb < db/init/006_pricing.sql
docker compose exec -T postgres psql -U postgres -d pantrydb < db/init/007_user_preference_mealprep.sql
docker compose exec -T postgres psql -U postgres -d pantrydb < db/init/008_users_seed.sql
```

For a VM deployment (nginx reverse proxy + Let's Encrypt HTTPS), see `docs/deployment.md`.

## 3. Local backend dev (optional — run Java outside a container)

Handy while iterating on the backend. Run Postgres (and optionally `ml-service`) in containers, the
backend on the host:

```
docker compose up -d postgres            # plus `ml-service` if you need rankBy=mealHistory
cd java-backend
export USDA_API_KEY=...                   # mvn spring-boot:run doesn't read .env
export OCR_API_KEY=...
mvn spring-boot:run
```

- Defaults to port **8080** — override with `SERVER_PORT` if it's taken.
- Connects to `jdbc:postgresql://localhost:5432/pantrydb` (see `application.yml`).
- `ddl-auto: validate` — the app refuses to start if the live schema doesn't exactly match the JPA
  entities (column types included). See `docs/database.md`.

## 4. Running ml-service standalone (optional)

`ml-service/` comes up automatically with `docker compose up`. To run it on the host instead — e.g.
while editing the recommendation code — see `docs/ml-service.md`:

```
cd ml-service
python -m venv .venv && source .venv/bin/activate     # .venv/Scripts/activate on Windows
pip install -r requirements.txt                        # large — pulls torch via sentence-transformers
uvicorn app.main:app --port 8000
```

The backend reaches it via `ml-service.base-url` (`${ML_SERVICE_BASE_URL:http://localhost:8000}` in
`application.yml`; the container sets `ML_SERVICE_BASE_URL=http://ml-service:8000`). It's called only
by `RecipeRankingService.rankByMealHistorySimilarity` for `rankBy=mealHistory`; every other endpoint
works without it, and even that search falls back to the default ranking if `ml-service` is down.
Running standalone, the first request downloads `all-MiniLM-L6-v2` (~80 MB) from Hugging Face; the
container image already has it baked in.

## Verifying the stack is up

```
curl -X POST http://localhost:8080/api/nutrition/log \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"itemName":"banana","quantityGrams":118}'
```

A `201` means Postgres, the nutrition schema, and the USDA integration are all working. See
`docs/testing.md` for the full manual verification checklist.
