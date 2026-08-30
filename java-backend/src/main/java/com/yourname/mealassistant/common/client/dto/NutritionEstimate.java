package com.yourname.mealassistant.common.client.dto;

// Per-100g nutrition values returned by NutritionAiClient (the Gemini fallback used when USDA
// has no usable macros for a food). Any field may be null if the model didn't provide it.
public record NutritionEstimate(Double calories, Double protein, Double fiber, Double fats, Double carbs) {
}
