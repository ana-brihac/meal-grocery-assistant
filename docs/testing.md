# Testing

## Unit tests

```
cd java-backend
mvn test
```

All tests are JUnit 5 + Mockito, no Spring context loaded (fast, no Postgres needed).

**What's actually covered:**

- `NutritionServiceTest` (~13 tests) — cache hit/miss on `getOrFetchNutritionInfo` (incl. the
  Gemini fallback filling macros on a USDA miss), `logMeal` saves a log row, `logRecipe` saves one
  log row per ingredient scaled by servings and tagged with `recipeId` (plus the unknown-recipe-id
  error case), `computeRecipeNutrition` sums ingredient macros scaled by servings and flags
  `complete=false` when an ingredient has no calories, `getDailyBreakdown` populating
  `recipeId`/`recipeName`, `getSummary` totals (zero-logs case + a real multiplier calculation).
- `SpendingServiceTest` (3 tests) — zero receipts, summing multiple receipts, skipping a receipt
  with a null `totalAmount`.
- `RecipeServiceTest` (8 tests) — ingredients get lower-cased before querying, no-match returns an
  empty result (not null), result order follows whatever `RecipeRankingService` returns,
  `rankBy=mealHistory` routes to `rankByMealHistorySimilarity` with a fallback when it throws, and
  the CRUD path: `createRecipe` saves the recipe then the ingredient rows with the new id,
  `updateRecipe` replaces the ingredient list wholesale, unknown id throws.
- `RecipeRankingServiceTest` (2 tests) — fewest-ingredients-first ordering, empty candidates list.
  (`rankByMealHistorySimilarity` is exercised via `RecipeServiceTest` and `MlServiceClientTest`.)
- `MlServiceClientTest` (2 tests) — spins up a real `com.sun.net.httpserver.HttpServer` on a random
  port (no wiremock/mockwebserver dependency): one asserts a real `POST /recommendations`
  round-trip serializes the request and parses `{"results":[...]}`; the other asserts the 5-second
  timeout fires when the server hangs (so this class takes ~5s to run).
- `MealPlanOptimizerTest` (8 tests) — pure, no Spring. Every slot filled with distinct recipes and
  no warnings when a day is in band; weekly-budget cutoff skips an unaffordable recipe; a day that
  can't reach the calorie band comes back with a warning; empty candidates → empty plan + warnings;
  `mealPrepBatchSize=3` reuses one recipe across three consecutive same-meal slots;
  `selectReplacement` picks in-room / not-excluded, falls back to closest + warning, throws when
  every candidate is excluded.
- `IngredientPriceServiceTest` (15 tests) — receipt upsert: new row defaults to `PER_ITEM`/RECEIPT,
  name normalized before lookup, unchanged price doesn't touch change-tracking, changed price
  records `previousPrice` + `priceChangedAt`, a receipt upsert doesn't clobber a manual
  `pricing_mode`, null/zero price is skipped; `addManualPrice` sets mode + `gramsPerItem`, invalid
  mode throws; `estimateIngredientCost` for `PER_KG`, `PER_ITEM` with/without `gramsPerItem`, and
  no row; `addFromPhoto` OCRs with the price-tag prompt, parses, upserts, and rejects garbage.

**What's not covered — these test files exist but are empty stubs, not placeholders with `@Disabled`
or a TODO, just genuinely empty:**

- `InventoryServiceTest.java`
- `ReceiptParserTest.java`

If you're picking up work in inventory or receipt parsing, there is currently **zero** automated
coverage — don't assume a green `mvn test` says anything about those areas. `ReceiptParser` in
particular has non-trivial logic (markdown-fence stripping, handling both bare-array and
`{"items": [...]}` shapes, defaulting missing fields) that would benefit from tests given it's
parsing untrusted LLM output.

Also uncovered, and with no empty stub file even flagging it: `UserPreferenceService` (defaults
fallback, single-row upsert), `NutritionService.getSummary`'s recipe-sourced-entries gap, and the
meal-plan / grocery-list orchestration — `MealPlanService` and `GroceryListService` (aggregation,
inventory subtraction, delete-then-insert) have no tests; only `MealPlanOptimizer` and
`IngredientPriceService` are covered directly. `RecipeDataLoader` and
`RecipeRepository.findRecipesMakeableFrom` also have no automated coverage — both need a real
Postgres instance to test meaningfully (custom `@Query` JPQL, file/classpath reading), which is
outside this repo's current no-Spring-context unit test setup.

There is no CI configured (no `.github/workflows` or equivalent) — `mvn test` only runs when someone
runs it locally.

## ml-service unit tests

```
cd ml-service
python -m venv .venv && source .venv/bin/activate   # if not already set up
pip install -r requirements.txt
pytest -q
```

`tests/test_recommendation_service.py` — 3 real tests: embedding output shape is consistent across
recipes of different length (this one loads the real `all-MiniLM-L6-v2` model), `recommend()` ranks
a candidate pointing the same direction as the history vector above one pointing the opposite way
(asserts both order and the cosine values), and an empty candidate list returns `[]`.

First run is **slow** — cold `torch`/`transformers` import plus a one-time ~80 MB model download
from Hugging Face (needs network). After that it's a few seconds.

There are no FastAPI-level (`TestClient`) tests and no CI for `ml-service` either.

## Integration / manual verification

There's no automated integration test suite (no Testcontainers, no `@SpringBootTest` against a real
Postgres). To verify the nutrition/spending stack actually works end-to-end, start the full stack
(see `docs/setup.md`) and:

1. **Log a real item and sanity-check the numbers**:
   ```
   curl -X POST http://localhost:8080/api/nutrition/log \
     -H "Content-Type: application/json" \
     -d '{"userId":1,"itemName":"banana","quantityGrams":118}'
   ```
   Expect `201`. Note: USDA returns the first search match, which is sometimes a branded/processed
   product rather than the generic food — see `docs/third-party-integrations.md`. Don't be alarmed
   by an implausible-looking calorie count for a raw ingredient; it's a data-quality quirk, not
   necessarily a broken request.

2. **Log the same item again** and check `nutrition_info` has exactly one row for it while
   `nutrition_log` has two — confirms caching (no repeat USDA call on the second request).

3. **Log a noisy name** (e.g. `"Milk 1L Almond"`) and confirm it normalizes to the same cache key
   regardless of casing/quantity tokens in the input (`milk almond`), and that repeated noisy
   variants don't create duplicate `nutrition_info` rows.

4. **Log an item nothing can match** (e.g. a nonsense string) — expect `201`. The lookup tries
   USDA, then a Gemini per-100g estimate; if both come back empty the `nutrition_info` row is
   saved with null macros, not a 500. (If USDA itself errors — 429/5xx — expect a clean `502`
   instead, via `NutritionApiException`.) A row cached with null macros is not retried — delete it
   to force a re-fetch.

5. **Hit the summary endpoints** and check the totals against what's actually in `nutrition_log` /
   `receipts` — the multiplier per log row is `quantity_grams / nutrition_info.base_quantity`.

6. **Check Postgres directly**:
   ```
   docker exec -it <postgres-container> psql -U postgres -d pantrydb -c 'select * from nutrition_info;'
   ```
   to confirm real persistence, not just 200-status theater.

## Recipes

`RecipeDataLoader` needs `java-backend/src/main/resources/data/recipes.csv` (see
`RecipeDataLoader.java` for the expected column format: one row per ingredient,
`recipe_name,instructions,source,ingredient_name,quantity,unit`, `quantity` in grams). Without that
file the app boots fine and `/api/recipes/search` just returns an empty list. The loader is a
no-op if `recipes` already has rows — clear `recipes` + `recipe_ingredients` to re-import. Recipes
can also be added/edited at runtime via `POST` / `PUT /api/recipes`.

Once the CSV is in place and the app has been started against a real Postgres (so
`RecipeDataLoader` has actually run):

1. **Search for recipes you can fully make**:
   ```
   curl "http://localhost:8080/api/recipes/search?ingredients=pasta&ingredients=olive+oil&ingredients=garlic&ingredients=tomatoes"
   ```
   Expect only recipes whose *entire* ingredient list is covered by the given list — a recipe
   needing one ingredient not listed here should NOT appear, even if the rest match (decided match
   rule, see `RecipeRepository.findRecipesMakeableFrom`).

2. **Log a recipe**:
   ```
   curl -X POST http://localhost:8080/api/nutrition/log-recipe \
     -H "Content-Type: application/json" \
     -d '{"recipeId":1,"servings":2.0}'
   ```
   Expect `201`. One `nutrition_log` row per `recipe_ingredients` row for that recipe, each with
   `quantity_grams` = that ingredient's `quantity` × servings, all sharing the same `recipe_id`,
   and `user_id` left `null` (see `docs/database.md`).

3. **Check the calendar view picks up the recipe link**:
   ```
   curl "http://localhost:8080/api/nutrition/calendar?start=2026-08-01&end=2026-08-31"
   ```
   Entries from step 2 should have `recipeId`/`recipeName` populated on that day; manually-logged
   entries (via `/api/nutrition/log`) should have both `null`.

## Meal planning + grocery lists

Needs `recipes.csv` loaded and at least a few `ingredient_price` rows for the budget constraint to
mean anything.

1. **Seed prices** — `POST /api/prices` with `{"itemName":"chicken breast","price":8.5,
   "pricingMode":"PER_KG"}`, or upload a receipt.
2. **Generate a plan**:
   ```
   curl -X POST http://localhost:8080/api/mealplan/generate \
     -H "Content-Type: application/json" -d '{"weekStartDate":"2026-09-01"}'
   ```
   Expect a plan id, one slot per (day, meal type), per-slot calorie/protein/fiber/cost
   contributions, `totalEstimatedCost` vs `budget`, and `warnings` for any day outside the calorie
   band or below the macro floors. `nutritionIncomplete` / `costIncomplete` flag partial data.
3. **Swap a slot** — `POST /api/mealplan/{id}/slots/{slotId}/replace`. The replacement should keep
   that day within its remaining calorie room; a grocery list already generated for the plan flips
   to `stale`.
4. **Reuse a plan** — `POST /api/mealplan/{id}/select` with a new `weekStartDate`. A new `SELECTED`
   plan appears in `GET /api/mealplan`; days that no longer fit the current targets get re-optimised.
5. **Grocery list** — `POST /api/grocerylist/generate` with `{"mealPlanId": <id>}`. Ingredients
   already in `inventory_items` (by name) are dropped; the rest are priced. `PATCH
   /api/grocerylist/items/{itemId}?purchased=true` checks one off.

## Recipe recommendations

Needs `ml-service` running (`cd ml-service && uvicorn app.main:app --port 8000`) in addition to the
backend, plus some `nutrition_log` history to rank against (log a few meals first).

1. **Default ranking** — `GET /api/recipes/search?ingredients=...` with no `rankBy` returns
   fewest-ingredients-first.

2. **`rankBy=mealHistory` re-orders by taste** — add `&rankBy=mealHistory`. Recipes closer to what's
   in `nutrition_log` should rise to the top. The response shape is identical (no score field is
   exposed) — only the order changes.

3. **Fallback when `ml-service` is down** — stop `uvicorn`, repeat the `rankBy=mealHistory` request.
   It should still return `200` with results in the default fewest-ingredients order (within ~5s —
   the `MlServiceClient` timeout), **not** a 502 or a hang.

4. **Hit `ml-service` directly** — see `docs/ml-service.md` for a `POST /recommendations` curl and
   the expected ranked-list response.

## A note on the USDA DEMO_KEY

If you're testing with `USDA_API_KEY=DEMO_KEY` rather than a personal key, expect two kinds of
noise that are environmental, not bugs:

- Occasional `404`s or an HTML response instead of JSON — DEMO_KEY traffic is served from a
  multi-edge CDN and one edge has been observed serving stale/wrong responses; retrying usually
  clears it.
- A `502` / `"USDA API Rate Limit Exceeded"` after a burst of requests — DEMO_KEY is capped around
  30 requests/hour, shared across everyone using it. Get a personal key for anything beyond light
  spot-checking.
