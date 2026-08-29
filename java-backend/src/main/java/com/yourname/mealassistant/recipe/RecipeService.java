package com.yourname.mealassistant.recipe;

import com.yourname.mealassistant.recipe.dto.RecipeSearchRequest;
import com.yourname.mealassistant.recipe.dto.RecipeSearchResponse;
import com.yourname.mealassistant.recipe.ranking.RecipeRankingService;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class RecipeService {

    private final RecipeRepository recipeRepository;
    private final RecipeRankingService recipeRankingService;

    public RecipeService(RecipeRepository recipeRepository, RecipeRankingService recipeRankingService) {
        this.recipeRepository = recipeRepository;
        this.recipeRankingService = recipeRankingService;
    }

    public RecipeSearchResponse searchRecipes(RecipeSearchRequest request) {
        List<String> normalizedIngredients = request.ingredients().stream()
                .map(String::toLowerCase)
                .toList();

        List<Recipe> candidates = recipeRepository.findRecipesMakeableFrom(normalizedIngredients);

        // No userId (decided — see RecipeController), and the decided scoring rule (fewest
        // ingredients needed) doesn't use inventory, so this passes an empty list rather than
        // resolving a per-user inventory.
        List<Recipe> ranked = "mealHistory".equals(request.rankBy())
                ? rankByMealHistoryWithFallback(candidates)
                : recipeRankingService.rankRecipes(candidates, Collections.emptyList());

        return new RecipeSearchResponse(ranked);
    }

    // A search request shouldn't fail just because the ML ranking signal is unavailable — falls
    // back to the same default (fewest-ingredients) ranking used when rankBy isn't set at all.
    private List<Recipe> rankByMealHistoryWithFallback(List<Recipe> candidates) {
        try {
            return recipeRankingService.rankByMealHistorySimilarity(candidates);
        } catch (RuntimeException e) {
            return recipeRankingService.rankRecipes(candidates, Collections.emptyList());
        }
    }
}
