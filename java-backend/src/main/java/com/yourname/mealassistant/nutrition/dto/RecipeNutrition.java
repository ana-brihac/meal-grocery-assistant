package com.yourname.mealassistant.nutrition.dto;

// Serving-scaled nutrition totals for one recipe, computed read-only for meal planning
// by NutritionService.computeRecipeNutrition — it does NOT write nutrition_log rows.
// `complete` is false if any ingredient's calories were still unknown after the
// cache -> USDA -> AI lookup chain.
public record RecipeNutrition(double calories, double protein, double fiber, boolean complete) {
}
