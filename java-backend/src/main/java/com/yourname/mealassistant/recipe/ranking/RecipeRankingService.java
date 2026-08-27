package com.yourname.mealassistant.recipe.ranking;

import com.yourname.mealassistant.inventory.InventoryItem;
import com.yourname.mealassistant.recipe.Recipe;
import com.yourname.mealassistant.recipe.RecipeIngredientRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class RecipeRankingService {

    private final RecipeIngredientRepository recipeIngredientRepository;

    public RecipeRankingService(RecipeIngredientRepository recipeIngredientRepository) {
        this.recipeIngredientRepository = recipeIngredientRepository;
    }

    // Decided: rank by fewest total ingredients needed (simpler recipes first) — not surfaced to
    // the app, just determines result order. availableInventory is unused by this rule but stays
    // a parameter for the Phase 6 hook below and in case a future rule needs it.
    public List<Recipe> rankRecipes(List<Recipe> candidates, List<InventoryItem> availableInventory) {
        return candidates.stream()
                .sorted(Comparator.comparingLong(recipe -> recipeIngredientRepository.countByRecipeId(recipe.getId())))
                .toList();
    }

    // TODO (Phase 6): UserPreference (see preference/UserPreference.java — daily calorie/protein/
    // fiber targets, weekly budget) will start factoring into ranking/filtering once budget- and
    // calorie-aware meal plans land. Not needed yet — this hook is just so it isn't forgotten:
    // rankRecipes will likely need an additional UserPreference (or relevant subset) parameter, or
    // a second overload, at that point.
}
