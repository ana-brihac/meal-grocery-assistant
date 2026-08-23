# Known issues / TODOs

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
- **ml-service isn't actually runnable via Docker.** Both `ml-service/Dockerfile` and
  `ml-service/requirements.txt` are empty files. See `docs/ml-service.md`.
- **Zero test coverage for three classes.** `InventoryServiceTest.java`, `MlServiceClientTest.java`,
  and `ReceiptParserTest.java` all exist as files but are completely empty — not stubs with a TODO,
  just empty. `mvn test` passes cleanly with no signal about any of these areas. `ReceiptParser` in
  particular is parsing untrusted LLM output and would benefit most from real tests.
- **No CI.** No `.github/workflows` or equivalent — tests only run when someone remembers to run
  them locally.
- **Receipt upload has no failure feedback.** `POST /api/receipts/upload` returns success
  immediately, before OCR/parsing even runs. If Gemini fails, returns unparseable JSON, or the
  parse throws, the failure is only printed to stderr server-side — the client (and the user) has
  no way to know the upload didn't actually produce any inventory items. No status endpoint, no
  webhook, nothing to poll.
- **`ReceiptParser` swallows parse failures silently.** Catches `Exception` broadly, logs to
  stderr, returns an empty list — combined with the point above, a malformed Gemini response just
  results in "0 items added" with no visible error anywhere.
- **Inconsistent API response shape.** `ReceiptController`/`InventoryController`/
  `UserPreferenceController` wrap responses in `ApiResponse<T>`; `SpendingController`/
  `DashboardController` return raw DTOs. `NutritionController` is now inconsistent with *itself*:
  `/summary` and `/log` return raw DTOs, `/calendar` (added alongside `preference`) returns
  `ApiResponse<T>`. No documented convention for which new endpoints should use — see
  `docs/backend-api.md`.
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
- **No test coverage for the new preferences/nutrition-calendar work**: `UserPreferenceService` and
  `NutritionService.getDailyBreakdown` have no tests at all (not even empty stub files). See
  `docs/testing.md`.
- **`java-backend/target/` build artifacts are tracked in git** despite `target/` being in
  `.gitignore` — leftover from before the ignore rule was added (`git rm --cached` was never run).
  Produces noisy, meaningless diffs when built on a different machine or OS.
- **Package name `com.yourname.mealassistant`** is a leftover Spring Initializr placeholder, never
  renamed to something real.
- **`GET /api/inventory` has no pagination** — returns every row, unbounded.
- **`docs/architecture.md` and `docs/setup.md` were empty placeholders** until this handoff pass
  (2026-08-23) — if you're reading an older checkout, they may still be blank.
- **`receipttest.png`** sits at the repo root with no README/reference to it — looks like a manual
  test asset that never got moved into a fixtures directory or `.gitignore`d.
