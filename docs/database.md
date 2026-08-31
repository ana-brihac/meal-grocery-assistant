# Database

Postgres 16, single database `pantrydb`. No migration tool (no Flyway/Liquibase) — schema is
hand-written SQL under `db/init/`, applied by the official Postgres image's
`docker-entrypoint-initdb.d` mechanism **on first container start only**. See the caveat in
`docs/setup.md` about reusing an old volume.

- `db/init/001_init_schema.sql` — `users`, `recipes`, `meals`, `receipts`, `inventory_items`
- `db/init/002_nutrition_spending.sql` — `nutrition_info`, `nutrition_log`, plus an index on
  `receipts.receipt_date`
- `db/init/003_recipes.sql` — extends `recipes` with `source`, adds the `recipe_ingredients` table
  and `nutrition_log.recipe_id`, plus indexes on `recipe_ingredients`
- `db/init/004_user_preference.sql` — `user_preference`, seeded with one default row (`id=1`)
- `db/init/005_mealplan_grocerylist.sql` — `meal_plan`, `meal_plan_slot`, `grocery_list_item`
- `db/init/006_pricing.sql` — `ingredient_price`
- `db/init/007_user_preference_mealprep.sql` — adds `user_preference.meal_prep_batch_size` (INTEGER,
  default 1)

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
| `recipes` | `id`, `name`, `instructions`, `source` (added by `003_recipes.sql`) | Read by `GET /api/recipes/search` via `RecipeRepository.findRecipesMakeableFrom` |
| `recipe_ingredients` | `recipe_id` → `recipes`, `ingredient_name`, `quantity` (**grams**, not unit-converted — see note below), `unit` (display-only) | Populated by `RecipeDataLoader` from `src/main/resources/data/recipes.csv` (see that class for the expected CSV format) on startup |
| `meals` | `user_id` → `users`, `recipe_id` → `recipes`, `planned_date` | Legacy from `001`, not exposed by any endpoint — the meal-plan feature uses `meal_plan` / `meal_plan_slot` instead, not this table |
| `receipts` | `user_id` → `users`, `store_name`, `total_amount` (numeric), `receipt_date` | Populated by the async receipt-upload pipeline; `spending` summary reads from here |
| `inventory_items` | `receipt_id` → `receipts` (nullable), `name`, `quantity`, `price`, `expiry_date` | Also populated directly via `POST /api/inventory` (no receipt link in that case) |
| `nutrition_info` | `item_name` (PK, **normalized** food name), `base_quantity`, `calories`, `protein`, `fibers`, `fats`, `carbs` | Cache of nutrition lookups, one row per normalized item name. Filled from USDA, or — on a USDA miss — from a Gemini per-100g estimate. Nullable macros mean neither could resolve it; that row is kept so the lookup isn't retried, so **delete null-macro rows** if you want a re-fetch (e.g. after fixing a bad API key) |
| `nutrition_log` | `user_id` → `users`, `item_name` (normalized, matches `nutrition_info.item_name`), `quantity_grams`, `logged_at`, `recipe_id` → `recipes` (nullable, added by `003_recipes.sql`) | One row per logged meal; `getSummary` joins this to `nutrition_info` by `item_name` — **must** stay normalized on write or the join silently drops rows. `NutritionService.logRecipe` writes one row per recipe ingredient (not one aggregated row), all sharing the same `recipe_id`, with `user_id` left `null` (see the note on `logRecipe` in `NutritionService.java` — it currently has no `userId` param, so these rows won't show up in `getSummary`, which filters by `user_id`, only in `getDailyBreakdown`, which doesn't) |
| `user_preference` | `id`, `daily_calorie_target`, `daily_protein_target`, `daily_fiber_target` (all `DOUBLE PRECISION`), `weekly_budget` (`NUMERIC`), `meal_prep_batch_size` (`INTEGER`, default 1, from `007`) | Single-row table in practice — `UserPreferenceService` always upserts `id=1`. Seeded with one default row by `004_user_preference.sql` |
| `ingredient_price` | `item_name` (unique, **normalized**), `price` (`NUMERIC`), `pricing_mode` (`PER_ITEM`\|`PER_KG`), `grams_per_item` (`DOUBLE PRECISION`, nullable), `source`, `previous_price` (`NUMERIC`, nullable), `price_changed_at` (`TIMESTAMP`, nullable) | The price catalog. Upserted by `ReceiptService` (per parsed line), `POST /api/prices`, and `POST /api/prices/from-photo`. Read by meal-plan cost estimation and the grocery list |
| `meal_plan` | `id`, `week_start_date`, `status` (`DRAFT`\|`SELECTED`), `source_plan_id` → `meal_plan` (nullable), target snapshot columns (`calorie_target_snapshot` / `protein_target_snapshot` / `fiber_target_snapshot` as `DOUBLE PRECISION`, `weekly_budget_snapshot` as `NUMERIC`), `created_at` | One generated plan. Targets are snapshot at generation time so history stays meaningful after preferences change. At most one `SELECTED` plan per `week_start_date`, enforced in `MealPlanService` only |
| `meal_plan_slot` | `meal_plan_id` → `meal_plan`, `slot_date`, `meal_type` (free string), `recipe_id` → `recipes`, `servings` (`DOUBLE PRECISION`), per-slot contribution snapshot (`estimated_calories`/`estimated_protein`/`estimated_fiber` as `DOUBLE PRECISION`, `estimated_cost` as `NUMERIC`), `cost_complete` / `nutrition_complete` (`BOOLEAN`) | One meal in a plan. The `*_complete` flags are false when an ingredient's price / macros couldn't be resolved, so the estimate is partial |
| `grocery_list_item` | `meal_plan_id` → `meal_plan`, `item_name` (normalized), `quantity` (`DOUBLE PRECISION`, summed recipe grams — **not** reconciled against inventory amounts), `unit`, `estimated_cost` (`NUMERIC`, nullable), `purchased` / `stale` (`BOOLEAN`), `generated_at` | One line of the shopping list for a plan. `stale` is set when a plan slot is swapped after the list was generated |

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
- `java-backend/target/` is `.gitignore`d and no longer tracked (the old tracked build-status
  files were removed with `git rm --cached`). If you see `target/` show up in `git status` again,
  something re-added it — don't commit it.
- `recipe_ingredients.quantity` is treated as grams everywhere (`RecipeDataLoader`,
  `RecipeRankingService`, `NutritionService.logRecipe`) — there is no unit-conversion logic
  anywhere in this app (cups/tbsp/etc. aren't converted to grams). `unit` is a display-only label;
  if a recipe's CSV row has a non-gram `quantity`, nutrition math for that ingredient will be wrong.
  Decided deliberately for simplicity — see `recipe/loader/RecipeDataLoader.java`.
