package com.yourname.mealassistant.recipe.dto;

import com.yourname.mealassistant.recipe.Recipe;

import java.util.List;

// Decided: bare List<Recipe>, no ranking metadata (score, ingredient counts, etc.) exposed to the
// app — RecipeRankingService still determines result order server-side, it just isn't surfaced.
public record RecipeSearchResponse(List<Recipe> results) {
}
