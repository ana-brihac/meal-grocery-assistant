# meal-grocery-assistant

A meal planning and grocery spend tracker. Upload a grocery receipt, get it OCR'd and parsed into
inventory items, track nutrition and spending against it, and generate weekly meal plans that stay
within your calorie / protein / fiber targets and weekly budget — plus the shopping list to make
them.

## Stack

- **java-backend** — Spring Boot 3.3.4 (Java 21) REST API. Owns receipts, inventory, nutrition,
  spending, recipes, ingredient prices, meal plans, and grocery lists. Talks to the USDA FoodData
  Central API for nutrition lookups, to Gemini for receipt OCR / price-tag OCR / a last-resort
  nutrition estimate, and to `ml-service` for recipe recommendations.
- **ml-service** — Python / FastAPI. Hosts `POST /recommendations`: ranks candidate recipes by
  sentence-transformers embedding similarity to the user's logged meal history. Run directly with
  `uvicorn` — not containerized yet. See `docs/ml-service.md`.
- **Postgres 16** — primary datastore, schema-managed via the SQL scripts in `db/init`.

## Project layout

```
java-backend/   Spring Boot API (see src/main/java/com/yourname/mealassistant)
ml-service/     Python / FastAPI — recipe recommendation endpoint
db/init/        Postgres schema, applied on first container start
docs/           Architecture and setup notes
```

## Setup

1. Create a `.env` file in the repo root:

   ```
   USDA_API_KEY=your_usda_api_key_here
   OCR_API_KEY=your_gemini_api_key_here
   ```

   `USDA_API_KEY` is a FoodData Central key (get one at https://fdc.nal.usda.gov/api-key-signup, or use
   `DEMO_KEY` for light manual testing — it's rate-limited to a handful of requests per hour).
   `OCR_API_KEY` is a Gemini API key, used for receipt parsing, shelf price-tag OCR, and the
   fallback nutrition estimate when USDA has no match for an ingredient.

2. Start Postgres:

   ```
   docker-compose up -d postgres
   ```

   This applies every script in `db/init/` (`001_init_schema.sql` through
   `007_user_preference_mealprep.sql`) on first boot. If you're reusing an existing `pantry_pg_data`
   volume from before one of these tables existed, its init script won't rerun automatically — apply
   the new ones by hand against the running container (see `docs/setup.md`), or recreate the volume
   with `docker-compose down -v`.

3. Add a recipe dataset at `java-backend/src/main/resources/data/recipes.csv` (one row per
   ingredient — see `RecipeDataLoader.java` for the exact column format). The app boots without it,
   but recipe search and meal-plan generation have nothing to work with until it's present. It loads
   automatically on the next backend startup, once (won't reload or duplicate on later restarts —
   clear the `recipes` / `recipe_ingredients` tables to re-import). Recipes can also be added and
   edited at runtime via `POST` / `PUT /api/recipes`.

4. Run the backend from `java-backend/`:

   ```
   mvn spring-boot:run
   ```

   Reads `USDA_API_KEY` and `OCR_API_KEY` from the environment (export them, or use an env-file
   runner). Defaults to `jdbc:postgresql://localhost:5432/pantrydb` and port 8080 — override the
   port with `SERVER_PORT` if 8080 is taken locally.

## API

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/receipts/upload` | POST (multipart `file`) | Upload a receipt image; OCR'd and parsed asynchronously into inventory + ingredient prices |
| `/api/inventory` | GET / POST | List / add inventory items |
| `/api/nutrition/log` | POST | Log a food item + quantity (grams) eaten by a user |
| `/api/nutrition/log-recipe` | POST | Log a recipe eaten (`recipeId`, `servings`) — one nutrition log row per ingredient, scaled by servings |
| `/api/nutrition/summary` | GET (`userId`, `from`, `to` as ISO datetimes) | Aggregated macros over a date range |
| `/api/nutrition/calendar` | GET (`start`, `end` as ISO dates) | Per-day macro breakdown, one entry per calendar day (zero-log days included) |
| `/api/spending/summary` | GET (`userId`, `from`, `to` as ISO dates) | Total receipt spend over a date range |
| `/api/dashboard/summary` | GET (`userId`, `from`, `to` as ISO dates) | Combined nutrition + spending summary |
| `/api/preferences` | GET / PUT | Read / update daily calorie-protein-fiber targets, weekly budget, and meal-prep batch size (single-user, one row) |
| `/api/recipes/search` | GET (`ingredients`, repeated; optional `rankBy=mealHistory`) | Recipes fully makeable from the given ingredients — every ingredient the recipe needs must be in the list. `rankBy=mealHistory` re-ranks by meal-history similarity via `ml-service`, falling back to the default order if it's unavailable |
| `/api/recipes` | GET / POST | List all recipes with ingredients / create a recipe |
| `/api/recipes/{id}` | GET / PUT | Fetch / edit a recipe (PUT replaces the ingredient list wholesale) |
| `/api/prices` | GET / POST | List the ingredient price catalog / add a price manually (`itemName`, `price`, `pricingMode` `PER_ITEM`\|`PER_KG`, optional `gramsPerItem`) |
| `/api/prices/from-photo` | POST (multipart `file`) | OCR a shelf price tag and add it to the catalog |
| `/api/mealplan/generate` | POST (`weekStartDate`, optional `days` / `mealTypes` / `servingsPerMeal`) | Generate a plan for the week that fits the stored targets and budget |
| `/api/mealplan` | GET | Plan history, newest first |
| `/api/mealplan/{id}` | GET | One plan with its slots |
| `/api/mealplan/{id}/slots/{slotId}/replace` | POST | Swap one meal for another that still fits the rest of the plan |
| `/api/mealplan/{id}/select` | POST (`weekStartDate`) | Reuse a past plan for a week — clones it and re-checks it against current targets |
| `/api/grocerylist/generate` | POST (`mealPlanId`) | Aggregate a plan's ingredients, subtract inventory, price the rest |
| `/api/grocerylist/{mealPlanId}` | GET | The current grocery list for a plan |
| `/api/grocerylist/items/{itemId}` | PATCH (`purchased`) | Check an item off while shopping |

## Meal planning

`POST /api/mealplan/generate` builds a week of slots (breakfast / lunch / dinner by default) from
the recipe pool. `MealPlanOptimizer` picks recipes so each day's calories land within ±100 kcal of
`dailyCalorieTarget`, each day clears ~90% of the protein and fiber targets, and the whole plan
stays under `weeklyBudget`. Per-recipe nutrition comes from `NutritionService` (USDA-backed, with a
Gemini fallback for ingredients USDA can't match); per-recipe cost comes from the `ingredient_price`
catalog. Where a figure can't be resolved the plan is returned anyway, flagged
`nutritionIncomplete` / `costIncomplete`, with warnings listing which days are off.

Set `mealPrepBatchSize` (via `PUT /api/preferences`) to 2 or 3 to let one cooked recipe cover that
many consecutive same-meal-type slots — cook once, eat it a few times.

`POST /api/grocerylist/generate` then turns a chosen plan into a shopping list: it sums every
ingredient across the plan's recipes, drops anything already in inventory (name match only — no
unit reconciliation), and prices the remainder from the catalog.

## Testing

Unit tests (mocked repositories / API clients, no external services needed):

```
cd java-backend
mvn test
```

`ml-service` tests (needs its venv + `pip install -r requirements.txt`; first run downloads the
embedding model):

```
cd ml-service
pytest -q
```

Manual / integration check against a real stack:

1. Start Postgres and the backend as above.
2. Log an item and confirm real macros come back:
   ```
   curl -X POST http://localhost:8080/api/nutrition/log \
     -H "Content-Type: application/json" \
     -d '{"userId":1,"itemName":"banana","quantityGrams":118}'
   ```
3. Log the same item again, then check `nutrition_info` has a single row for it (cache hit, no
   repeat USDA call) while `nutrition_log` has two entries.
4. Log a noisy name (e.g. `"Milk 1L Almond"`) and confirm it normalizes to a sane cache key (`milk
   almond`) instead of creating a duplicate `nutrition_info` row per variant.
5. Seed a few prices (`POST /api/prices`, or upload a receipt), then
   `POST /api/mealplan/generate` with a `weekStartDate` and check the returned slots, totals, and
   warnings. Then `POST /api/grocerylist/generate` with the plan id.
6. Connect to Postgres directly (`psql -U postgres -d pantrydb`) and inspect the tables to confirm
   persistence, not just 200 responses.

Note: USDA's `DEMO_KEY` is shared and rate-limited (roughly 30 requests/hour); a burst of manual
testing can trip a `502 Bad Gateway` ("USDA API Rate Limit Exceeded") — that's the app's
rate-limit handling working as intended, not a bug. Use a personal API key for anything beyond
light spot-checking.
