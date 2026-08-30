-- Meal-plan generation (budget / calorie / protein / fiber aware) plus grocery-list generation.
--
-- Decided: meal plans ARE persisted — the user browses plan history and clones a past
--   plan for an upcoming week. A plan is a header (meal_plan) + one row per meal (meal_plan_slot).
--   The `meals` table from 001_init_schema.sql stays unused; this is a separate model.
--
-- Type pairing (docs/database.md): nutrition targets/values -> DOUBLE PRECISION / Double;
-- money (budget, cost) -> NUMERIC / BigDecimal. Hibernate ddl-auto=validate will refuse to boot
-- on any mismatch with the entities.

CREATE TABLE meal_plan (
    id                          BIGSERIAL PRIMARY KEY,
    week_start_date             DATE,
    status                      VARCHAR(50),            -- 'DRAFT' | 'SELECTED' (enforced in app code)
    source_plan_id              BIGINT REFERENCES meal_plan(id),  -- set on clone-on-select; null otherwise
    -- target snapshot, copied from user_preference at generation time
    calorie_target_snapshot     DOUBLE PRECISION,
    protein_target_snapshot     DOUBLE PRECISION,
    fiber_target_snapshot       DOUBLE PRECISION,
    weekly_budget_snapshot      NUMERIC,
    created_at                  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE meal_plan_slot (
    id                  BIGSERIAL PRIMARY KEY,
    meal_plan_id        BIGINT REFERENCES meal_plan(id),
    slot_date           DATE,
    meal_type           VARCHAR(50),                    -- 'BREAKFAST' | 'LUNCH' | 'DINNER' | ... (plain string, not a DB enum, so the layout isn't locked)
    recipe_id           BIGINT REFERENCES recipes(id),
    servings            DOUBLE PRECISION,
    -- per-slot contribution snapshot (see mealplan/MealPlanSlot.java)
    estimated_calories  DOUBLE PRECISION,
    estimated_protein   DOUBLE PRECISION,
    estimated_fiber     DOUBLE PRECISION,
    estimated_cost      NUMERIC,
    cost_complete       BOOLEAN NOT NULL DEFAULT FALSE,  -- false if some ingredient had no ingredient_price row
    nutrition_complete  BOOLEAN NOT NULL DEFAULT FALSE   -- false if some ingredient's macros were still unknown after cache/USDA/AI
);

CREATE INDEX idx_meal_plan_slot_meal_plan_id ON meal_plan_slot(meal_plan_id);
CREATE INDEX idx_meal_plan_week_start_date ON meal_plan(week_start_date);

-- Grocery list: one row per ingredient still to buy for a given meal plan. Inventory subtraction
-- is name-match only -- if the ingredient name is on hand in any quantity it's left off the list;
-- amounts/units are not reconciled (inventory_items has no unit column, no conversion logic).
-- Grouped by meal_plan_id (no separate list header table).
CREATE TABLE grocery_list_item (
    id              BIGSERIAL PRIMARY KEY,
    meal_plan_id    BIGINT REFERENCES meal_plan(id),
    item_name       VARCHAR(255) NOT NULL,
    quantity        DOUBLE PRECISION,                   -- summed recipe requirement (grams), NOT reconciled
    unit            VARCHAR(50),
    estimated_cost  NUMERIC,                            -- null if the ingredient had no ingredient_price row
    purchased       BOOLEAN NOT NULL DEFAULT FALSE,
    stale           BOOLEAN NOT NULL DEFAULT FALSE,     -- set true when a plan slot is swapped after this list was generated; user must regenerate
    generated_at    TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_grocery_list_item_meal_plan_id ON grocery_list_item(meal_plan_id);
