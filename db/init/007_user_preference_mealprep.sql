-- Meal-prep batch size: how many consecutive same-meal-type slots one cooked recipe may cover
-- in a generated plan (1 = no batching). See preference/UserPreference.java and MealPlanOptimizer.
-- The DEFAULT backfills the single existing user_preference row (id=1) seeded by 004.

ALTER TABLE user_preference ADD COLUMN meal_prep_batch_size INTEGER NOT NULL DEFAULT 1;
