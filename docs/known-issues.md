# Known issues / TODOs

## Recently fixed (2026-09-03)

- **Full stack is containerized.** `ml-service/Dockerfile` and `java-backend/Dockerfile` are real
  multi-stage builds, and `docker-compose.yml` now brings up all three services (`postgres`,
  `ml-service`, `java-backend`) with healthchecks and ordered startup (`java-backend` waits for
  `postgres` to pass `pg_isready`; it only waits for `ml-service` to *start*, since the ML ranking
  degrades gracefully). `USDA_API_KEY` / `OCR_API_KEY` come from a repo-root `.env`
  (`docker compose` substitutes them automatically; `.env.example` documents the shape). The
  `db/init/` mount and `pantry_pg_data` volume are unchanged. Verified by building the images and
  running the stack against real Postgres: `GET`/`POST /api/inventory` round-trip to Postgres, and
  `java-backend` reaches `ml-service` by service name (`POST /recommendations` returns 200 over the
  compose network). VM/reverse-proxy/HTTPS guide: `docs/deployment.md`.
- **`ml-service` image bakes the embedding model, and preloads it at startup.** `all-MiniLM-L6-v2`
  (~80 MB) is downloaded during `docker build`, so the container needs no network at run time. On
  top of that, `app/main.py`'s FastAPI `lifespan` calls `get_model()` during startup, so the model
  is in memory before the server accepts traffic — the first `/recommendations` call is no longer
  cold (that cold call used to blow past `MlServiceClient`'s 5s timeout and make the first
  `rankBy=mealHistory` after a restart fall back). The healthcheck `start_period` is 90s to cover
  the load on a small VM. Tradeoff (larger image, build-time network): see `ml-service/Dockerfile`;
  the running-by-hand path still downloads on first use.
- **`rankBy=mealHistory` now actually uses the ML signal.** Two bugs kept it permanently on the
  fallback ordering, both found while verifying the compose stack (both pre-existing, not caused by
  containerization):
  1. `MlServiceClient` built its `WebClient` with a bare `WebClient.builder()`, whose Jackson codec
     serialized `NutritionLog.loggedAt` (`LocalDateTime`) as a numeric array; `ml-service`'s
     Pydantic `datetime` field rejected it with `422`. Fixed by giving that client's codecs an
     `ObjectMapper` with `JavaTimeModule` registered and `WRITE_DATES_AS_TIMESTAMPS` disabled, so
     `logged_at` goes out as an ISO-8601 string. `MlServiceClientTest` now pins the request-body
     format.
  2. The first `/recommendations` after an `ml-service` restart loaded the model lazily and
     exceeded the client's 5s timeout — addressed by the startup preload above.
  `RecipeService.rankByMealHistoryWithFallback` swallows any failure silently (by design — a search
  must not fail because the ML service is down), which is why both bugs were invisible. The
  empty-history path always worked.
- **`db/init/008_users_seed.sql` seeds the default user (id 1).** `nutrition_log.user_id` has a FK
  to `users(id)` and nothing created that row, so the documented `POST /api/nutrition/log
  {"userId": 1, ...}` failed with a foreign-key violation on a fresh database. Now seeded on first
  volume init, the same way `004_user_preference.sql` seeds its id-1 row. Existing volumes need it
  applied by hand (see `docs/setup.md`).
- **`java-backend` is published on loopback only.** `docker-compose.yml` now binds
  `127.0.0.1:8080:8080` instead of all interfaces — on a server the API is reached through the
  reverse proxy (`docs/deployment.md`), and `localhost:8080` still works for local dev.

## Recently fixed (2026-09-01)

- **Inconsistent API response shape — resolved.** The API now uniformly returns **raw DTOs**;
  the `ApiResponse<T> {success, data, error}` envelope and `common/dto/ApiResponse.java` were
  removed, along with `NutritionController#/calendar`'s wrapper. Errors are now RFC 9457
  `ProblemDetail` (`application/problem+json`) from `GlobalExceptionHandler`:
  `NotFoundException` → 404, `BadRequestException` → 400, `NutritionApiException` → 502,
  else → 500. The services' `orElseThrow(...)` sites now throw `NotFoundException` (unknown
  plan / recipe / slot / grocery-list item → 404) and the "required field" / bad-`pricingMode`
  validation throws `BadRequestException` (→ 400); a bare `IllegalArgumentException` is left as
  500 on purpose so a real internal bug isn't hidden as a client error. Status-code fixes that
  rode along: `POST /api/receipts/upload` → 202 (was 200), `POST /api/mealplan/generate` and
  `POST /api/grocerylist/generate` → 201 (were 200); an unreadable multipart upload on
  `/api/receipts/upload` and `/api/prices/from-photo` is now a 400. `ApiErrorMappingTest`
  (`@WebMvcTest`) covers the real status codes. See `docs/backend-api.md`.
- **CI added.** `.github/workflows/ci.yml` runs `mvn test` (java-backend) and `pytest`
  (ml-service) on every pull request and on pushes to `main` and `ana-brihac/**` branches.
  The `all-MiniLM-L6-v2` model is restored from an `actions/cache` keyed by model name so only
  the first run pays the ~80 MB Hugging Face download. Needs a PAT with the `workflow` scope
  to push changes to the workflow file.
- **Test coverage filled in.** The previously-empty `InventoryServiceTest` / `ReceiptParserTest`
  are implemented (ReceiptParser weighted toward malformed-Gemini-output cases). New:
  `UserPreferenceServiceTest`, `MealPlanServiceTest` (orchestration), `getDailyBreakdown` cases
  in `NutritionServiceTest`, `GroceryListService` persistence-path cases, a `*ControllerTest`
  per controller, `ApiErrorMappingTest` (`@WebMvcTest` — real status codes), and
  `ml-service/tests/test_recommendations_endpoint.py` (`TestClient` for `POST /recommendations`).
  `mvn test`: 195 pass; `pytest`: 16 pass; both wired into CI.

## Recently fixed (2026-08-31)

- **Hardcoded API keys were in git history.** Early commits carried a real Gemini key and USDA key
  in `java-backend/src/main/resources/application.yml` (and a `target/classes/` copy). The keys
  have been rotated and history was rewritten (`git filter-repo`) to strip them and to remove
  `java-backend/target/` from every commit. `application.yml` now uses `${OCR_API_KEY}` /
  `${USDA_API_KEY}` placeholders; `.env` is gitignored and untracked. `target/` is no longer
  tracked (`git rm --cached`).

## Recently fixed (2026-08-23, commit `d694799`)

These were found by actually running the full stack against real Postgres + USDA, not just
`mvn test` — worth knowing about since the same class of issue (schema/entity drift, mocked tests
passing while the real thing is broken) can easily recur:

- **App couldn't start at all against a real database.** `db/init/002_nutrition_spending.sql`
  declared nutrition columns as `DECIMAL`/`NUMERIC`; the JPA entities use `Double`. With
  `ddl-auto: validate`, Hibernate refused to boot. Fixed by aligning the SQL to `DOUBLE PRECISION`.
  See `docs/database.md` for the type-pairing convention to keep going forward.
- **Silent data loss in nutrition summaries.** `NutritionService.logMeal` saved the *raw* item name
  into `nutrition_log`, while `nutrition_info` is keyed by the *normalized* name. Any input that
  normalization actually changed (different case, or containing a quantity token like `1L`/`200g`)
  would log successfully but silently contribute zero macros to `/api/nutrition/summary`, because
  the join by item name never matched — no exception, no error, just missing numbers. Fixed by
  writing the normalized name to the log row too.
- **500 on common nutrition lookups.** Spring WebClient's default 256KB in-memory buffer was too
  small for USDA's default 50-result search page. Fixed by requesting `pageSize=5` (only the first
  result is ever used anyway) plus a larger buffer as a backstop.

## Open

- **USDA match quality**: the nutrition lookup takes USDA's first search result with no filtering
  by `dataType` — a query like "banana" can match a branded/processed product instead of a generic
  raw food, giving plausible-but-wrong macros. See `docs/third-party-integrations.md`. Fixing this
  would mean preferring `Foundation`/`SR Legacy` data types, or re-ranking somehow — a product
  decision as much as a bug fix, so left alone for now.
- **`AsyncConfig.java` is empty.** The receipt-upload pipeline uses raw
  `CompletableFuture.supplyAsync(...)` (the default common `ForkJoinPool`), not `@Async` with a
  managed executor — so this config class does nothing. Either implement it (a dedicated executor
  bean, sized appropriately, wired via `@Async`) or delete it so it doesn't look like unfinished
  wiring.
- **Running `ml-service` by hand still needs network on first use.** The *container* bakes the
  model in (see "Recently fixed" above), but if you run `ml-service` directly with `uvicorn` (or
  run `pytest`), `sentence-transformers` downloads `all-MiniLM-L6-v2` (~80 MB) from Hugging Face on
  first use and caches it under `~/.cache/huggingface`. A fully offline first run that way fails.
- **Meal-history ranking ignores recency.** `embed_meal_history` mean-pools every `nutrition_log`
  entry's name embedding with equal weight; `logged_at` is sent and parsed but unused. Also, like
  the rest of the recipe/nutrition endpoints, it's unscoped by `userId` (`findAll()`).
- **Receipt upload has no failure feedback.** `POST /api/receipts/upload` returns success
  immediately, before OCR/parsing even runs. If Gemini fails, returns unparseable JSON, or the
  parse throws, the failure is only printed to stderr server-side — the client (and the user) has
  no way to know the upload didn't actually produce any inventory items. No status endpoint, no
  webhook, nothing to poll.
- **`ReceiptParser` swallows parse failures silently.** Catches `Exception` broadly, logs to
  stderr, returns an empty list — combined with the point above, a malformed Gemini response just
  results in "0 items added" with no visible error anywhere.
- **`/api/nutrition/calendar` has no `userId` param**, unlike every other nutrition/spending
  endpoint. `NutritionService.getDailyBreakdown` queries all `nutrition_log` rows in the date range
  across all users via `findAll()`, not scoped to one user. Harmless for a single-user app, but a
  real correctness gap (and an unindexed full-table scan) if multi-user support ever happens.
- **`user_preference` single-row invariant is enforced only in application code**, not the DB or
  JPA layer. `UserPreferenceService.savePreferences` pins new rows to `id=1`, but nothing stops a
  second row from being inserted through a different code path (e.g. calling
  `userPreferenceRepository.save(new UserPreference(...))` directly with no id set). Also,
  `004_user_preference.sql` seeds `id=1` explicitly, so the `BIGSERIAL` sequence backing it never
  advances — if that row is ever deleted and a fresh insert relies on `IDENTITY` generation instead
  of the pinned id, Postgres could try to reuse `id=1` and collide. See `docs/database.md`.
- **Package name `com.yourname.mealassistant`** is a leftover Spring Initializr placeholder, never
  renamed to something real.
- **`GET /api/inventory` has no pagination** — returns every row, unbounded.
- **`docs/architecture.md` and `docs/setup.md` were empty placeholders** until this handoff pass
  (2026-08-23) — if you're reading an older checkout, they may still be blank.
- **`receipttest.png`** sits at the repo root with no README/reference to it — looks like a manual
  test asset that never got moved into a fixtures directory or `.gitignore`d.

### Meal planning / grocery lists / pricing

- **The optimizer's scoring weights are untuned.** `MealPlanOptimizer.W_CALORIE / W_PROTEIN /
  W_FIBER / W_COST` and `MIN_MACRO_FRACTION` (0.90) are hand-picked constants, never validated
  against real recipe data. The greedy fill is single-pass with no local-repair — infeasible days
  come back with a warning rather than a better arrangement. Good enough to demo; revisit with a
  real recipe set.
- **A thin recipe pool starves slot replacement.** `replaceSlot` / `selectForWeek` exclude every
  recipe already in the plan, so with N recipes and a 21-slot plan only N−21 candidates remain for
  a swap. Small `recipes.csv` → frequent "No alternative recipe available" warnings.
- **First plan on a cold `nutrition_info` cache is slow.** `computeRecipeNutrition` runs
  cache-or-fetch per ingredient of every recipe, so the first `generate` triggers a USDA (and
  occasionally Gemini) round-trip per new ingredient. Cached afterward.
- **`PER_ITEM` prices with no `grams_per_item` can't be applied.** `estimateIngredientCost` returns
  empty for them (recipe quantities are grams; without a per-unit weight there's no conversion) —
  the recipe/list is flagged `costIncomplete`. Add `gramsPerItem` via `POST /api/prices` to fix.
  The grocery-list response now also names these (and price-less ingredients) in `missingPrices`
  with a `reason`, so the client can prompt the user directly; it's recomputed per response.
- **Regenerating a grocery list drops `purchased` ticks.** It's delete-then-insert per
  `meal_plan_id`, so any items already checked off are lost on regenerate.
- **Plan `warnings` aren't persisted.** `GET /api/mealplan` and `GET /api/mealplan/{id}` return an
  empty `warnings` list — only `generate` / `replaceSlot` / `selectForWeek` populate it, from the
  run that just happened.
- **No `DELETE /api/recipes/{id}`.** A recipe is FK-referenced by `meal_plan_slot`,
  `nutrition_log.recipe_id`, and the unused `meals` table, so a hard delete would fail. Needs a
  decision: block-if-referenced, soft-delete flag, or null the references.
