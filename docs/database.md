# Database

Postgres 16, single database `pantrydb`. No migration tool (no Flyway/Liquibase) — schema is
hand-written SQL under `db/init/`, applied by the official Postgres image's
`docker-entrypoint-initdb.d` mechanism **on first container start only**. See the caveat in
`docs/setup.md` about reusing an old volume.

- `db/init/001_init_schema.sql` — `users`, `recipes`, `meals`, `receipts`, `inventory_items`
- `db/init/002_nutrition_spending.sql` — `nutrition_info`, `nutrition_log`, plus an index on
  `receipts.receipt_date`
- `db/init/004_user_preference.sql` — `user_preference`, seeded with one default row (`id=1`)

Hibernate is configured with `ddl-auto: validate` (`application.yml`) — it will refuse to start the
app if the live schema doesn't match the JPA entities, **including column types**. This bit hard
once already: the SQL originally declared `DECIMAL(10,2)`/`NUMERIC` for nutrition columns while the
entities use `Double`, which Hibernate rejects (`float8` expected, `numeric` found) — fixed by
switching those columns to `DOUBLE PRECISION`. If you add a new entity field, double check the SQL
column type matches what Hibernate will expect for that Java type, or the app won't boot — nothing
short of actually starting the app against a real Postgres instance will catch this (unit tests
with mocked repositories won't).

## Tables

| Table | Key columns | Notes |
|---|---|---|
| `users` | `id`, `username` (unique), `email` (unique) | Referenced by `meals`, `receipts`, `nutrition_log` |
| `recipes` | `id`, `name`, `instructions` | Not currently exposed by any endpoint |
| `meals` | `user_id` → `users`, `recipe_id` → `recipes`, `planned_date` | Not currently exposed by any endpoint |
| `receipts` | `user_id` → `users`, `store_name`, `total_amount` (numeric), `receipt_date` | Populated by the async receipt-upload pipeline; `spending` summary reads from here |
| `inventory_items` | `receipt_id` → `receipts` (nullable), `name`, `quantity`, `price`, `expiry_date` | Also populated directly via `POST /api/inventory` (no receipt link in that case) |
| `nutrition_info` | `item_name` (PK, **normalized** food name), `base_quantity`, `calories`, `protein`, `fibers`, `fats`, `carbs` | Cache of USDA lookups, one row per normalized item name; nullable macros mean "USDA had no match" |
| `nutrition_log` | `user_id` → `users`, `item_name` (normalized, matches `nutrition_info.item_name`), `quantity_grams`, `logged_at` | One row per logged meal; `getSummary` joins this to `nutrition_info` by `item_name` — **must** stay normalized on write or the join silently drops rows |
| `user_preference` | `id`, `daily_calorie_target`, `daily_protein_target`, `daily_fiber_target` (all `DOUBLE PRECISION`), `weekly_budget` (`NUMERIC`) | Single-row table in practice — `UserPreferenceService` always upserts `id=1`. Seeded with one default row by `004_user_preference.sql` |

## Things to know before changing the schema

- All monetary/receipt amounts use `NUMERIC`/`BigDecimal` — keep that pairing (don't mix in
  `Double`/`DOUBLE PRECISION` there).
- All nutrition macro/quantity columns use `DOUBLE PRECISION`/`Double` — keep that pairing too.
- `user_preference.weekly_budget` is `NUMERIC`/`BigDecimal`, following the monetary pairing above
  (not `DOUBLE PRECISION`/`Double`, unlike the three calorie/protein/fiber target columns in the
  same table) since it represents money, not a nutrition macro.
- `user_preference` seeding `id=1` explicitly in `004_user_preference.sql` means the `BIGSERIAL`
  sequence backing it never actually advances past its start value. If that row is ever deleted and
  a new one is inserted relying on `IDENTITY` generation (rather than `UserPreferenceService`
  explicitly setting `id=1`), Postgres may try to hand out `id=1` again and collide. Not yet hit in
  practice — same class of "only shows up against a real database" issue as the schema/entity drift
  noted above.
- `nutrition_info.item_name` is the primary key and is always the *normalized* form
  (`ItemNameNormalizer.normalize`, lowercased + quantity tokens stripped) — never the raw
  user-entered string. `nutrition_log.item_name` must be written as the same normalized value
  (`NutritionService.logMeal` does this via `info.getItemName()`) or summary aggregation breaks
  silently (no error, just missing macros for that log entry).
- `java-backend/target/` is `.gitignore`d but a stale set of build-status files under
  `java-backend/target/maven-status/` are still tracked in git from before the ignore rule was
  added — expect noisy, meaningless diffs there if you build on a different machine/OS; don't
  commit them.
