package com.yourname.mealassistant.nutrition.dto;

// TODO: once recipe-logging exists (Phase 4+), add an optional recipeId/recipeName field here so
// recipe-based entries can be distinguished from manually logged ones. Not added yet — NutritionLog
// itself has no recipe link this phase, so every entry here is inherently manual.
public record LoggedMealEntry(String itemName, Double quantityGrams, Double calories, Double protein, Double fiber) {
}

