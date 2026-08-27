package com.yourname.mealassistant.nutrition.dto;

// recipeId/recipeName are null for manually logged entries (NutritionService.logMeal) and set for
// recipe-sourced entries (NutritionService.logRecipe), so the calendar view can tell them apart.
public record LoggedMealEntry(String itemName, Double quantityGrams, Double calories, Double protein, Double fiber,
                               Long recipeId, String recipeName) {
}
