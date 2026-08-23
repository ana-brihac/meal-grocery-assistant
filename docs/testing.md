# Testing

## Unit tests

```
cd java-backend
mvn test
```

All tests are JUnit 5 + Mockito, no Spring context loaded (fast, no Postgres needed).

**What's actually covered:**

- `NutritionServiceTest` (5 tests) — cache hit/miss on `getOrFetchNutritionInfo`, `logMeal` saves a
  log row, `getSummary` totals (including the zero-logs case and a real multiplier calculation).
- `SpendingServiceTest` (3 tests) — zero receipts, summing multiple receipts, skipping a receipt
  with a null `totalAmount`.

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
fallback, single-row upsert) and `NutritionService.getDailyBreakdown` (per-day grouping, zero-log-day
backfill) — both added for the preferences/nutrition-calendar feature, both worth real tests given
the day-boundary and null-macro edge cases involved.

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

## A note on the USDA DEMO_KEY

If you're testing with `USDA_API_KEY=DEMO_KEY` rather than a personal key, expect two kinds of
noise that are environmental, not bugs:

- Occasional `404`s or an HTML response instead of JSON — DEMO_KEY traffic is served from a
  multi-edge CDN and one edge has been observed serving stale/wrong responses; retrying usually
  clears it.
- A `502` / `"USDA API Rate Limit Exceeded"` after a burst of requests — DEMO_KEY is capped around
  30 requests/hour, shared across everyone using it. Get a personal key for anything beyond light
  spot-checking.
