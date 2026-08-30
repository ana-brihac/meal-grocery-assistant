package com.yourname.mealassistant.recipe.dto;

import java.util.List;

// `ingredients` = what you have available; a recipe matches if all of its required ingredients
// are somewhere in this list (decided — see RecipeRepository.findRecipesMakeableFrom). No userId
// field (decided — see RecipeController).
// `rankBy` = "mealHistory" to rank by meal-history similarity (RecipeRankingService.
// rankByMealHistorySimilarity) instead of the default fewest-ingredients rule; null/anything else
// keeps the default. Kept as a plain String (not an enum) to match the low-ceremony style of the
// rest of this record.
// TODO: revisit if a result limit/pagination is ever needed.
public record RecipeSearchRequest(List<String> ingredients, String rankBy) {

    public RecipeSearchRequest(List<String> ingredients) {
        this(ingredients, null);
    }
}
