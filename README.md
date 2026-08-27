# meal-grocery-assistant

A meal planning and grocery spend tracker. Upload a grocery receipt, get it OCR'd and parsed into
inventory items, then track nutrition and spending against it.

## Stack

- **java-backend** — Spring Boot 3.3.4 (Java 21) REST API. Owns receipts, inventory, nutrition,
  spending, and recipes. Talks to the USDA FoodData Central API for nutrition lookups and to Gemini
  for receipt OCR.
- **ml-service** — Python service used by the backend for auxiliary ML tasks.
- **Postgres 16** — primary datastore, schema-managed via the SQL scripts in `db/init`.

## Project layout

```
java-backend/   Spring Boot API (see src/main/java/com/yourname/mealassistant)
ml-service/     Python ML service
db/init/        Postgres schema, applied on first container start
docs/           Architecture and setup notes
```

## Setup

1. Copy `.env.example` to `.env` and fill in real values:

   ```
   USDA_API_KEY=your_usda_api_key_here
   OCR_API_KEY=your_ocr_api_key_here
   ```

   `USDA_API_KEY` is a FoodData Central key (get one at https://fdc.nal.usda.gov/api-key-signup, or use
   `DEMO_KEY` for light manual testing — it's rate-limited to a handful of requests per hour).
   `OCR_API_KEY` is a Gemini API key, used for receipt parsing.

2. Start Postgres:

   ```
   docker-compose up -d postgres
   ```

   This applies `db/init/001_init_schema.sql`, `002_nutrition_spending.sql`, `003_recipes.sql`, and
   `004_user_preference.sql` on first boot. If you're reusing an existing `pantry_pg_data` volume from
   before one of these tables existed, its init script won't rerun automatically — apply it by hand
   against the running container in that case (see `docs/setup.md`).

3. Optional: add a recipe dataset at `java-backend/src/main/resources/data/recipes.csv` (one row
   per ingredient — see `RecipeDataLoader.java` for the exact column format). The app boots fine
   without it; `/api/recipes/search` just returns nothing until it's added. It loads automatically
   on the next backend startup, once (won't reload/duplicate on later restarts).

4. Run the backend from `java-backend/`:

   ```
   mvn spring-boot:run
   ```

   Reads `USDA_API_KEY` and `OCR_API_KEY` from the environment (export them, or use an env-file runner).
   Defaults to `jdbc:postgresql://localhost:5432/pantrydb` and port 8080 — override with `SERVER_PORT` if
   that port is taken locally.

## API

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/receipts/upload` | POST (multipart `file`) | Upload a receipt image; OCR'd and parsed asynchronously |
| `/api/inventory` | GET / POST | List / add inventory items |
| `/api/nutrition/log` | POST | Log a food item + quantity (grams) eaten by a user |
| `/api/nutrition/summary` | GET (`userId`, `from`, `to` as ISO datetimes) | Aggregated macros over a date range |
| `/api/nutrition/calendar` | GET (`start`, `end` as ISO dates) | Per-day macro breakdown over a date range, one entry per calendar day (zero-log days included) |
| `/api/spending/summary` | GET (`userId`, `from`, `to` as ISO dates) | Total receipt spend over a date range |
| `/api/dashboard/summary` | GET (`userId`, `from`, `to` as ISO dates) | Combined nutrition + spending summary |
| `/api/preferences` | GET / PUT | Read / update daily calorie-protein-fiber targets and weekly budget (single-user, one row) |
| `/api/recipes/search` | GET (`ingredients`, repeated) | Recipes fully makeable from the given ingredients — every ingredient the recipe needs must be in the list, not just "any overlap" |
| `/api/nutrition/log-recipe` | POST | Log a recipe eaten (`recipeId`, `servings`) — writes one nutrition log row per ingredient, scaled by servings |

Recipes are loaded from a CSV (`java-backend/src/main/resources/data/recipes.csv`) by
`RecipeDataLoader` on backend startup — see the Setup section above.

Nutrition lookups are cached: the first time an item name is logged, `NutritionService` normalizes it
(lowercased, quantity tokens like `1L`/`200g` stripped) and looks it up in `nutrition_info`; on a miss it
queries USDA and caches the result under the normalized name, so the same item never re-hits the API.
Items USDA has no match for are still cached, with null macros, rather than failing the request.

## Testing

Unit tests (mocked repositories/API client, no external services needed):

```
cd java-backend
mvn test
```

Manual/integration check against a real stack:

1. Start Postgres and the backend as above.
2. Log an item and confirm real macros come back:
   ```
   curl -X POST http://localhost:8080/api/nutrition/log \
     -H "Content-Type: application/json" \
     -d '{"userId":1,"itemName":"banana","quantityGrams":118}'
   ```
3. Log the same item again, then check `nutrition_info` has a single row for it (cache hit, no repeat
   USDA call) while `nutrition_log` has two entries.
4. Log a noisy name (e.g. `"Milk 1L Almond"`) and confirm it normalizes to a sane cache key (`milk
   almond`) instead of creating a duplicate `nutrition_info` row per variant.
5. Hit `/api/nutrition/summary` and `/api/spending/summary` with a date range and sanity-check the
   totals against what's in `nutrition_log` / `receipts`.
6. Connect to Postgres directly (`psql -U postgres -d pantrydb`) and inspect `nutrition_log` and
   `nutrition_info` to confirm persistence, not just 200 responses.

Note: USDA's `DEMO_KEY` is shared and rate-limited (roughly 30 requests/hour); a burst of manual testing
can trip a `502 Bad Gateway` ("USDA API Rate Limit Exceeded") — that's the app's rate-limit handling
working as intended, not a bug. Use a personal API key for anything beyond light spot-checking.
