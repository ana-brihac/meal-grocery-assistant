# Testing

## Unit tests

```
cd java-backend
mvn test
```

All tests are JUnit 5 + Mockito, no Spring context loaded (fast, no Postgres needed).

**What's actually covered:**

- `NutritionServiceTest` (8 tests) — cache hit/miss on `getOrFetchNutritionInfo`, `logMeal` saves a
  log row, `logRecipe` saves one log row per ingredient scaled by servings and tagged with
  `recipeId` (plus the unknown-recipe-id error case), `getDailyBreakdown` populating
  `recipeId`/`recipeName` on a recipe-sourced entry, `getSummary` totals (including the zero-logs
  case and a real multiplier calculation).
- `SpendingServiceTest` (3 tests) — zero receipts, summing multiple receipts, skipping a receipt
  with a null `totalAmount`.
- `RecipeServiceTest` (3 tests) — ingredients get lower-cased before querying, no-match returns an
  empty result (not null), result order follows whatever `RecipeRankingService` returns.
- `RecipeRankingServiceTest` (2 tests) — fewest-ingredients-first ordering, empty candidates list.

**What's not covered — these test files exist but are empty stubs, not placeholders with `@Disabled`
or a TODO, just genuinely empty:**

- `InventoryServiceTest.java`
- `MlServiceClientTest.java`
- `ReceiptParserTest.java`

If you're picking up work in inventory, the ml-service client, or receipt parsing, there is
currently **zero** automated coverage — don't assume a green `mvn test` says anything about those
areas. `ReceiptParser` in particular has non-trivial logic (markdown-fence stripping, handling both
bare-array and `{"items": [...]}` shapes, defaulting missing fields) that would benefit from tests
given it's parsing untrusted LLM output.

Also uncovered, and with no empty stub file even flagging it: `UserPreferenceService` (defaults
fallback, single-row upsert) and `NutritionService.getSummary`'s recipe-sourced-entries gap (see
`docs/database.md`'s note on `logRecipe` leaving `user_id` null) — both worth real tests given the
day-boundary, null-macro, and null-`user_id` edge cases involved. `RecipeDataLoader` and
`RecipeRepository.findRecipesMakeableFrom` also have no automated coverage — both need a real
Postgres instance to test meaningfully (custom `@Query` JPQL, file/classpath reading), which is
outside this repo's current no-Spring-context unit test setup.

There is no CI configured (no `.github/workflows` or equivalent) — `mvn test` only runs when someone
runs it locally.

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

4. **Log an item USDA won't match** (e.g. a nonsense string) — expect `201` with a
   `nutrition_info` row saved with null macros, not a 500. (If USDA itself errors — 429/5xx — expect
   a clean `502` instead, via `NutritionApiException`.)

5. **Hit the summary endpoints** and check the totals against what's actually in `nutrition_log` /
   `receipts` — the multiplier per log row is `quantity_grams / nutrition_info.base_quantity`.

6. **Check Postgres directly**:
   ```
   docker exec -it <postgres-container> psql -U postgres -d pantrydb -c 'select * from nutrition_info;'
   ```
   to confirm real persistence, not just 200-status theater.

## Recipes (Phase 4)

`RecipeDataLoader` needs `java-backend/src/main/resources/data/recipes.csv` to exist — it isn't
checked into the repo yet (see `RecipeDataLoader.java` for the expected column format: one row per
ingredient, `recipe_name,instructions,source,ingredient_name,quantity,unit`, `quantity` in grams).
Without that file, the app boots fine and `/api/recipes/search` just returns an empty list.

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

## A note on the USDA DEMO_KEY

If you're testing with `USDA_API_KEY=DEMO_KEY` rather than a personal key, expect two kinds of
noise that are environmental, not bugs:

- Occasional `404`s or an HTML response instead of JSON — DEMO_KEY traffic is served from a
  multi-edge CDN and one edge has been observed serving stale/wrong responses; retrying usually
  clears it.
- A `502` / `"USDA API Rate Limit Exceeded"` after a burst of requests — DEMO_KEY is capped around
  30 requests/hour, shared across everyone using it. Get a personal key for anything beyond light
  spot-checking.
