package com.yourname.mealassistant.recipe.dto;

import java.util.List;

// Body for POST /api/recipes (create) and PUT /api/recipes/{id} (edit). On edit, the ingredient
// list REPLACES the recipe's existing ingredients wholesale (simpler than granular add/remove).
// quantity is grams, matching recipe_ingredients everywhere else; unit is a display-only label.
public record RecipeUpsertRequest(
        String name,
        String instructions,
        String source,
        List<IngredientInput> ingredients) {

    public record IngredientInput(String ingredientName, Double quantity, String unit) {
    }
}
