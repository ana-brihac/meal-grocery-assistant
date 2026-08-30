package com.yourname.mealassistant.recipe.dto;

import java.util.List;

// A recipe plus its ingredients, for the recipe-list / recipe-detail / after-save views
// (RecipeController create / update / get / list).
public record RecipeDetailResponse(
        Long id,
        String name,
        String instructions,
        String source,
        List<IngredientView> ingredients) {

    public record IngredientView(Long id, String ingredientName, Double quantity, String unit) {
    }
}
