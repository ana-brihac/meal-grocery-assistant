# Architecture

## What this is

A grocery-receipt-to-nutrition pipeline with meal planning: upload a receipt photo, it gets OCR'd
and parsed into inventory items and ingredient prices; separately, a user logs what they ate and
the app looks up nutrition facts and tracks spending against their receipts. On top of that it
searches/edits recipes, and generates weekly meal plans that respect the user's calorie / protein
/ fiber targets and weekly budget, plus the grocery list to shop them.

## Services

```
                    ┌─────────────────┐
   receipt image →  │   java-backend   │ ──→ Gemini (OCR)
                     │  Spring Boot 3   │
   nutrition log →   │  Java 21, :8080  │ ──→ USDA FoodData Central (nutrition)
                     │                  │
   rankBy=          │                  │ ──→ ml-service (Python, :8000) — POST /recommendations,
   mealHistory      │                  │     see docs/ml-service.md
                     └────────┬─────────┘
                              │ JDBC
                              ▼
                     ┌──────────────────┐
                     │   Postgres 16    │
                     │   (pantrydb)     │
                     └──────────────────┘
```

- **java-backend** — the only service with real functionality. Owns all HTTP endpoints and all
  persistence. See `docs/backend-api.md`.
- **ml-service** — FastAPI, Python. Hosts `POST /recommendations`: it embeds candidate recipes and
  meal history with sentence-transformers (`all-MiniLM-L6-v2`) and returns candidates ranked by
  cosine similarity. No DB of its own — everything it needs is in the request body. Still serves
  the legacy `/ping`. Containerized (`ml-service/Dockerfile` bakes the model in and preloads it at
  startup); comes up with the rest of the stack via `docker compose`. See `docs/ml-service.md`.
- **Postgres** — schema is hand-written SQL in `db/init/`, applied only on first container start
  (no Flyway/Liquibase). See `docs/database.md`.

All three run together with `docker compose up --build` (see `docs/setup.md`); `java-backend`
publishes on `127.0.0.1:8080`. `docs/deployment.md` covers running this on a VM behind an nginx +
Let's Encrypt reverse proxy.

## Package layout (`java-backend/src/main/java/com/yourname/mealassistant`)

> Note: `com.yourname` is a leftover template package name, not a real org — nobody has renamed it.

| Package | Owns |
|---|---|
| `receipt` | `Receipt` entity, upload endpoint, async OCR→parse→save pipeline; also upserts each parsed line's price into `ingredient_price` |
| `receipt.parser` | `ReceiptParser` — turns Gemini's JSON text into `InventoryItem` rows |
| `inventory` | `InventoryItem` entity, list/add endpoints |
| `nutrition` | Food logging (including recipe-based logging), USDA-backed nutrition lookup + cache (Gemini fallback on a miss), read-only per-recipe nutrition for planning, date-range summary, per-day calendar breakdown |
| `spending` | Date-range spend summary over `receipts` |
| `recipe` | `Recipe`/`RecipeIngredient` entities, `GET /api/recipes/search` (ingredient-coverage matching + ranking, optional `rankBy=mealHistory`), and recipe list/create/edit (`GET`/`POST` `/api/recipes`, `GET`/`PUT` `/api/recipes/{id}`) |
| `recipe.ranking` | `RecipeRankingService` — default: fewest ingredients first; `rankByMealHistorySimilarity`: calls `ml-service` `POST /recommendations` and reorders by returned score. Stays purely structural — all budget/calorie logic lives in `mealplan.optimizer` |
| `recipe.loader` | `RecipeDataLoader` — loads `recipes.csv` into `recipes`/`recipe_ingredients` on startup |
| `pricing` | `IngredientPrice` entity + catalog: upsert from receipts, manual add (`POST /api/prices`), shelf price-tag OCR (`POST /api/prices/from-photo`), and the cost lookup meal plans + grocery lists use |
| `mealplan` | `MealPlan`/`MealPlanSlot` entities, `MealPlanService` (generate, history, get, replace-slot, select-for-week), REST controller under `/api/mealplan` |
| `mealplan.optimizer` | `MealPlanOptimizer` — the single constraint engine: weighted-score greedy fill respecting the per-day calorie band, protein/fiber floors, weekly budget, and meal-prep batching |
| `grocerylist` | `GroceryListItem` entity, `GroceryListService` (generate from a plan, fetch, check-off), REST controller under `/api/grocerylist` |
| `dashboard` | Combines nutrition + spending summaries into one response |
| `preference` | `UserPreference` entity (daily calorie/protein/fiber targets, weekly budget, meal-prep batch size), single-row read/upsert |
| `common.client` | External HTTP clients: `OcrClient` (Gemini — receipts + price tags), `NutritionApiClient` (USDA), `NutritionAiClient` (Gemini nutrition fallback), `MlServiceClient` (`ml-service` `POST /recommendations`, `WebClient`, 5s timeout) |
| `common.client.dto` | `RecommendationRequest`/`RecommendationResponse` (mirror `ml-service`'s Pydantic schemas), `NutritionEstimate` |
| `common.dto` | `MissingIngredientPrice` — `{ingredientName, reason}` line item for the grocery list's un-priced ingredients. (The old `ApiResponse<T>` envelope was removed — controllers return raw DTOs; see below.) |
| `common.exception` | `NotFoundException` (→ 404), `BadRequestException` (→ 400); `GlobalExceptionHandler` — `@RestControllerAdvice` that renders every error as an RFC 9457 `ProblemDetail`: `NotFoundException` → 404, `BadRequestException` → 400, `NutritionApiException` → 502, everything else → 500 (a bare `IllegalArgumentException` is left as 500 on purpose) |
| `common.util` | `ItemNameNormalizer` — lowercases + strips quantity tokens (`1L`, `200g`, ...) from food names |
| `config` | `WebClientConfig` (USDA WebClient bean), `AsyncConfig` (currently empty — see `docs/known-issues.md`) |

## Data flows

**Receipt → inventory** (fire-and-forget, no status endpoint):
`POST /api/receipts/upload` → `ReceiptService.processReceiptAsync` runs OCR, parsing, and the
inventory save on `CompletableFuture.supplyAsync`'s default pool (the common `ForkJoinPool`, *not*
a Spring-managed executor — `AsyncConfig` exists but is empty and unused). The HTTP response
returns immediately with "processing in background"; failures are only logged to stderr, never
surfaced to the caller.

**Nutrition log → summary**:
`POST /api/nutrition/log` normalizes the item name (`ItemNameNormalizer`), looks it up in
`nutrition_info` (cache), falls back to a USDA search on a miss, then — if USDA still yields no
calories — to a Gemini per-100g estimate (`NutritionAiClient`). The result is cached, including a
null-macro row if nothing resolves, so a bad lookup never gets retried (which also means: after
fixing a bad key, delete the null rows to force a re-fetch). `NutritionLog` rows are saved under
the *normalized* name so `GET /api/nutrition/summary` can join them back to `nutrition_info`
correctly. See `docs/third-party-integrations.md` for the USDA/Gemini integration details.

**Dashboard**:
`GET /api/dashboard/summary` just calls `NutritionService.getSummary` and
`SpendingService.getSpendingSummary` and wraps both in one response — no independent logic of
its own.

**Nutrition calendar**:
`GET /api/nutrition/calendar` (`NutritionService.getDailyBreakdown`) is a per-day version of the
same idea as `getSummary`, but iterates every calendar day in `[start, end]` and sums only that
day's logs into a `DailyNutritionSummary`, instead of summing the whole range into one total. Days
with no logs still come back as a zero-totals entry (empty `entries` list) rather than being
omitted, so a calendar UI never has to handle a missing day. It queries `nutrition_log` without a
`userId` filter — see the caveat in `docs/backend-api.md`.

**User preferences**:
`UserPreferenceService` treats `user_preference` as a single-row table — `savePreferences` always
upserts against `id=1` rather than creating a new row per call. `getPreferences` falls back to
hardcoded defaults (2000 cal / 100g protein / 30g fiber / 100 budget / batch size 1) if the row
doesn't exist, which matters because `db/init/004_user_preference.sql` seeds that same row at
schema-init time — the code fallback only kicks in if that seed is ever skipped or the row is
deleted. `007_user_preference_mealprep.sql` adds `meal_prep_batch_size` (default 1); a PUT that
omits it leaves the stored value alone.

**Recipe search → ranking**:
`GET /api/recipes/search?ingredients=...` lower-cases the given ingredient names and calls
`RecipeRepository.findRecipesMakeableFrom` — a custom `@Query` that returns only recipes where
*every* ingredient is covered by the given list (not "any overlap"), and excludes recipes with zero
ingredient rows. Candidates then go through `RecipeRankingService`, which currently just sorts by
fewest total ingredients (a `RecipeIngredientRepository.countByRecipeId` call per candidate — no
caching, so this is one query per candidate per search). No `userId`, no ranking metadata exposed
in the response — see `docs/backend-api.md`.

**Recipe search → meal-history ranking**:
`GET /api/recipes/search?ingredients=...&rankBy=mealHistory` runs the same
`findRecipesMakeableFrom` query as above, then instead of the fewest-ingredients sort it calls
`RecipeRankingService.rankByMealHistorySimilarity`. That builds a `RecommendationRequest` —
candidates (id + name + ingredient rows from `RecipeIngredientRepository`) and meal history
(**all** `nutrition_log` rows, no `userId`, via `findAll()`) — and POSTs it to `ml-service`
`/recommendations` through `MlServiceClient`. `ml-service` embeds both sides with
sentence-transformers and returns candidates scored by cosine similarity; the Java side reorders
its `Recipe` list by that score. If `MlServiceClient` throws (connection refused, the 5s timeout,
or a 5xx), `RecipeService.rankByMealHistoryWithFallback` swallows it and returns the
fewest-ingredients ordering instead — the search never fails because `ml-service` is down. Any
other `rankBy` value (or none) keeps the default behaviour. `ml-service` details:
`docs/ml-service.md`.

**Recipe data loading**:
`RecipeDataLoader` runs as a `CommandLineRunner` on every app startup. It's a no-op if
`recipes` already has rows (idempotency guard) or if
`src/main/resources/data/recipes.csv` doesn't exist — the app boots fine either way. When present,
it parses the CSV with Apache Commons CSV, groups consecutive rows by `recipe_name` into a `Recipe`
+ its `RecipeIngredient`s, and `saveAll`s both in batches of 200. See `RecipeDataLoader.java` for
the exact expected column format.

**Recipe-based nutrition logging**:
`NutritionService.logRecipe(recipeId, servings)` doesn't write one aggregated log row — it writes
one `NutritionLog` row *per ingredient* in the recipe (same `getOrFetchNutritionInfo` cache-or-fetch
path `logMeal` uses), each scaled by `ingredient.quantity * servings` and tagged with the same
`recipeId`. This reuses `getSummary`/`getDailyBreakdown`'s existing per-item multiplier logic
unchanged. It has no `userId` parameter, so those rows carry `user_id = null` — they show up in
`getDailyBreakdown` (unfiltered) but not `getSummary` (filtered by `userId`). Exposed via
`POST /api/nutrition/log-recipe`.

**Ingredient prices**:
`IngredientPriceService` keys `ingredient_price` by normalized name. A row has a `price`, a
`pricing_mode` (`PER_ITEM` or `PER_KG`), an optional `grams_per_item` (needed to turn a
grams-based recipe quantity into a unit count for `PER_ITEM`), a `source`
(`RECEIPT`/`MANUAL`/`PRICE_TAG_PHOTO`), and `previous_price` + `price_changed_at` for
change tracking. A receipt/photo upsert updates `price` (recording the change) but never clobbers a
`pricing_mode`/`grams_per_item` already set by a manual entry; a brand-new row from a receipt
defaults to `PER_ITEM`. `estimateIngredientCost(name, grams)` returns empty when there's no row, or
when a `PER_ITEM` row lacks `grams_per_item` — callers then flag the recipe/list cost-incomplete
rather than dropping it. `estimateCost(name, grams)` is the same lookup but also returns *why* the
cost is missing (`PriceGap`: `NO_PRICE_ON_FILE` / `NEEDS_GRAMS_PER_ITEM` / `NONE`); the grocery list
uses it to build its `missingPrices` notification.

**Meal plan generation**:
`POST /api/mealplan/generate` → `MealPlanService.generatePlan`: pull all recipes
(`RecipeRepository.findAll`), attach per-recipe nutrition (`NutritionService.computeRecipeNutrition`,
which is the cache-or-fetch path without writing log rows) and estimated cost
(`IngredientPriceService`), snapshot the current `UserPreference` targets + budget onto a
`meal_plan` row, and hand a `RecipeCandidate` list to `MealPlanOptimizer.selectPlan`. The optimizer
fills the slot grid (BREAKFAST/LUNCH/DINNER × N days by default) with a weighted-score greedy pass,
one meal type at a time so meal-prep batches can forward-fill consecutive same-meal days. Hard
rules: a day's calories must sum within ±100 kcal of `dailyCalorieTarget` and cost-complete spend
must stay under `weeklyBudget`; soft: each day should clear ~90% of the protein/fiber targets.
Anything that can't be satisfied is returned anyway with `warnings` and `costIncomplete` /
`nutritionIncomplete` flags. Slots persist as `meal_plan_slot` rows with their contribution
snapshot. `replaceSlot` swaps one slot for a fitting alternative (and marks any existing grocery
list stale); `selectForWeek` clones a past plan into a new week and re-runs the optimizer on days
that no longer fit the current targets.

**Grocery list generation**:
`POST /api/grocerylist/generate` → `GroceryListService.generateGroceryList(mealPlanId)`: sum every
ingredient's grams across the plan's slots (scaled by each slot's servings), normalize names, drop
any whose name matches an inventory item (name match only — `inventory_items` has no unit column,
so amounts aren't reconciled), price the remainder via `IngredientPriceService`, delete the
previous list for that plan and insert a fresh `grocery_list_item` set. `PATCH
/api/grocerylist/items/{id}` toggles `purchased`. The response's `missingPrices` list names every
ingredient that couldn't be costed and why (`NO_PRICE_ON_FILE` / `NEEDS_GRAMS_PER_ITEM`) — it's
reclassified on every grocery-list response, so it clears as the user adds prices and refetches.

- **Response shape is uniform: raw DTOs, no envelope.** Every controller returns its DTO (or
  entity) directly — `ResponseEntity<T>` / `ResponseEntity<Void>`. The old `ApiResponse<T>`
  `{success, data, error}` wrapper and `ApiResponse.java` are gone. Errors are RFC 9457
  `ProblemDetail` from `GlobalExceptionHandler`. Create/async endpoints carry the status in the
  HTTP code: `POST /api/mealplan/generate` and `POST /api/grocerylist/generate` → 201,
  `POST /api/receipts/upload` → 202. See `docs/backend-api.md`.
- Services are plain constructor-injected `@Service`/`@Component` beans, no interfaces, no
  builders — straightforward to read and extend.
- `UserPreference` enforces its single-row assumption only in `UserPreferenceService` logic
  (pinning `id=1` on insert) — nothing at the DB or JPA layer actually prevents a second row from
  being inserted through some other path (e.g. `userPreferenceRepository.save(new UserPreference())`
  called directly with no id set, relying on `IDENTITY` generation).
