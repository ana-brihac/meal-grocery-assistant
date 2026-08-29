# Architecture

## What this is

A grocery-receipt-to-nutrition pipeline: upload a receipt photo, it gets OCR'd and parsed into
inventory items; separately, a user logs what they ate and the app looks up nutrition facts and
tracks spending against their receipts.

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
- **ml-service** — FastAPI, Python. Phase 5 added `POST /recommendations`: it embeds candidate
  recipes and meal history with sentence-transformers (`all-MiniLM-L6-v2`) and returns candidates
  ranked by cosine similarity. No DB of its own — everything it needs is in the request body. Still
  serves the legacy `/ping`. Not containerized (empty `Dockerfile`, no compose service). See
  `docs/ml-service.md`.
- **Postgres** — schema is hand-written SQL in `db/init/`, applied only on first container start
  (no Flyway/Liquibase). See `docs/database.md`.

## Package layout (`java-backend/src/main/java/com/yourname/mealassistant`)

> Note: `com.yourname` is a leftover template package name, not a real org — nobody has renamed it.

| Package | Owns |
|---|---|
| `receipt` | `Receipt` entity, upload endpoint, async OCR→parse→save pipeline |
| `receipt.parser` | `ReceiptParser` — turns Gemini's JSON text into `InventoryItem` rows |
| `inventory` | `InventoryItem` entity, list/add endpoints |
| `nutrition` | Food logging (including recipe-based logging), USDA-backed nutrition lookup + cache, date-range summary, per-day calendar breakdown |
| `spending` | Date-range spend summary over `receipts` |
| `recipe` | `Recipe`/`RecipeIngredient` entities, `GET /api/recipes/search` (ingredient-coverage matching + ranking, optional `rankBy=mealHistory`) |
| `recipe.ranking` | `RecipeRankingService` — default: fewest ingredients first; `rankByMealHistorySimilarity` (Phase 5): calls `ml-service` `POST /recommendations` and reorders by returned score |
| `recipe.loader` | `RecipeDataLoader` — loads `recipes.csv` into `recipes`/`recipe_ingredients` on startup |
| `dashboard` | Combines nutrition + spending summaries into one response |
| `preference` | `UserPreference` entity (daily calorie/protein/fiber targets, weekly budget), single-row read/upsert |
| `common.client` | External HTTP clients: `OcrClient` (Gemini), `NutritionApiClient` (USDA), `MlServiceClient` (`ml-service` `POST /recommendations`, `WebClient`, 5s timeout) |
| `common.client.dto` | `RecommendationRequest`/`RecommendationResponse` — mirror `ml-service`'s Pydantic schemas |
| `common.dto` | `ApiResponse<T>` — success/data/error envelope (only used by some controllers, see below) |
| `common.exception` | `GlobalExceptionHandler` — catches `NutritionApiException` → 502, everything else → 500 |
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
`nutrition_info` (cache), falls back to a USDA search on a miss, and caches the result — including
a null-macro row if USDA has no match, so a bad lookup never gets retried. `NutritionLog` rows are
saved under the *normalized* name so `GET /api/nutrition/summary` can join them back to
`nutrition_info` correctly. See `docs/third-party-integrations.md` for the USDA integration
details and its rough edges.

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
hardcoded defaults (2000 cal / 100g protein / 30g fiber / 100 budget) if the row doesn't exist,
which matters because `db/init/004_user_preference.sql` seeds that same row at schema-init time —
the code fallback only kicks in if that seed is ever skipped or the row is deleted.

**Recipe search → ranking** (Phase 4):
`GET /api/recipes/search?ingredients=...` lower-cases the given ingredient names and calls
`RecipeRepository.findRecipesMakeableFrom` — a custom `@Query` that returns only recipes where
*every* ingredient is covered by the given list (not "any overlap"), and excludes recipes with zero
ingredient rows. Candidates then go through `RecipeRankingService`, which currently just sorts by
fewest total ingredients (a `RecipeIngredientRepository.countByRecipeId` call per candidate — no
caching, so this is one query per candidate per search). No `userId`, no ranking metadata exposed
in the response — see `docs/backend-api.md`.

**Recipe search → meal-history ranking** (Phase 5):
`GET /api/recipes/search?ingredients=...&rankBy=mealHistory` runs the same
`findRecipesMakeableFrom` query as above, then instead of the fewest-ingredients sort it calls
`RecipeRankingService.rankByMealHistorySimilarity`. That builds a `RecommendationRequest` —
candidates (id + name + ingredient rows from `RecipeIngredientRepository`) and meal history
(**all** `nutrition_log` rows, no `userId`, via `findAll()`) — and POSTs it to `ml-service`
`/recommendations` through `MlServiceClient`. `ml-service` embeds both sides with
sentence-transformers and returns candidates scored by cosine similarity; the Java side reorders
its `Recipe` list by that score. If `MlServiceClient` throws (connection refused, the 5s timeout,
or a 5xx), `RecipeService.rankByMealHistoryWithFallback` swallows it and returns the Phase 4
fewest-ingredients ordering instead — the search never fails because `ml-service` is down. Any
other `rankBy` value (or none) keeps the Phase 4 behaviour unchanged. `ml-service` details:
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

## Conventions worth knowing

- `ApiResponse<T>` (`{success, data, error}`) is used by `ReceiptController`, `InventoryController`,
  `UserPreferenceController`, `RecipeController`, and `NutritionController`'s `/calendar` endpoint,
  but **not** by `NutritionController`'s `/summary`/`/log`, `SpendingController`, or
  `DashboardController`, which return raw DTOs or `ResponseEntity<Void>`. There's still no single
  consistent response envelope across the API — check the specific endpoint you're calling, not
  just the controller.
- Services are plain constructor-injected `@Service`/`@Component` beans, no interfaces, no
  builders — straightforward to read and extend.
- `UserPreference` enforces its single-row assumption only in `UserPreferenceService` logic
  (pinning `id=1` on insert) — nothing at the DB or JPA layer actually prevents a second row from
  being inserted through some other path (e.g. `userPreferenceRepository.save(new UserPreference())`
  called directly with no id set, relying on `IDENTITY` generation).
