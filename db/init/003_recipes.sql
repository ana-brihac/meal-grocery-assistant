-- Decided: reuse/extend the `recipes` table already defined in db/init/001_init_schema.sql
-- (id, name, instructions, created_at) rather than creating a separately-named table. It's
-- missing only `source` (no prep_time_minutes — decided not needed). `meals`
-- (meals.recipe_id -> recipes(id), also from 001) isn't wired into this feature yet — left as-is.
ALTER TABLE recipes ADD COLUMN source VARCHAR(255);

-- Ingredients are modeled as a separate entity (decided — see recipe/RecipeIngredient.java).
CREATE TABLE recipe_ingredients (
    id                  BIGSERIAL PRIMARY KEY,
    recipe_id           BIGINT REFERENCES recipes(id),
    ingredient_name     VARCHAR(255) NOT NULL,
    quantity            DOUBLE PRECISION,
    unit                VARCHAR(50)
);

-- Decided: NutritionService.logRecipe links back to the source recipe via a real column on
-- nutrition_log (nullable — manual logMeal() entries have no recipe), not just at the DTO level.
ALTER TABLE nutrition_log ADD COLUMN recipe_id BIGINT REFERENCES recipes(id);

-- findRecipesMakeableFrom (RecipeRepository) filters on both columns per recipe.
CREATE INDEX idx_recipe_ingredients_recipe_id ON recipe_ingredients(recipe_id);
CREATE INDEX idx_recipe_ingredients_ingredient_name ON recipe_ingredients(ingredient_name);
