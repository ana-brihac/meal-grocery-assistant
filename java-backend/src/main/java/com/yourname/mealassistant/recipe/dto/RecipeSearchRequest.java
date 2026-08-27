package com.yourname.mealassistant.recipe.dto;

import java.util.List;

// `ingredients` = what you have available; a recipe matches if all of its required ingredients
// are somewhere in this list (decided — see RecipeRepository.findRecipesMakeableFrom). No userId
// field (decided — see RecipeController).
// TODO: revisit if a result limit/pagination is ever needed.
public record RecipeSearchRequest(List<String> ingredients) {
}
