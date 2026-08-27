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
        List<Recipe> ranked = recipeRankingService.rankRecipes(candidates, Collections.emptyList());

        return new RecipeSearchResponse(ranked);
    }
}
