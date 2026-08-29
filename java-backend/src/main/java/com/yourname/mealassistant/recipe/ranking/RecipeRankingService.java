package com.yourname.mealassistant.recipe.ranking;

import com.yourname.mealassistant.common.client.MlServiceClient;
import com.yourname.mealassistant.common.client.dto.RecommendationRequest;
import com.yourname.mealassistant.common.client.dto.RecommendationResponse;
import com.yourname.mealassistant.inventory.InventoryItem;
import com.yourname.mealassistant.nutrition.NutritionLogRepository;
import com.yourname.mealassistant.recipe.Recipe;
import com.yourname.mealassistant.recipe.RecipeIngredientRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class RecipeRankingService {

    private final RecipeIngredientRepository recipeIngredientRepository;
    private final MlServiceClient mlServiceClient;
    private final NutritionLogRepository nutritionLogRepository;

    public RecipeRankingService(RecipeIngredientRepository recipeIngredientRepository,
                                 MlServiceClient mlServiceClient,
                                 NutritionLogRepository nutritionLogRepository) {
        this.recipeIngredientRepository = recipeIngredientRepository;
        this.mlServiceClient = mlServiceClient;
        this.nutritionLogRepository = nutritionLogRepository;
    }

    // Decided: rank by fewest total ingredients needed (simpler recipes first) — not surfaced to
    // the app, just determines result order. availableInventory is unused by this rule but stays
    // a parameter for the Phase 6 hook below and in case a future rule needs it.
    public List<Recipe> rankRecipes(List<Recipe> candidates, List<InventoryItem> availableInventory) {
        return candidates.stream()
                .sorted(Comparator.comparingLong(recipe -> recipeIngredientRepository.countByRecipeId(recipe.getId())))
                .toList();
    }

    // Phase 5: ranks candidates by embedding similarity to the user's meal history, via
    // ml-service's POST /recommendations (MlServiceClient). A separate method rather than folding
    // into rankRecipes — the two rules are independent signals (structural vs. taste-based), and
    // this one isn't wired into RecipeController/RecipeService yet, so nothing calls it yet.
    // Meal history is built from ALL NutritionLog rows — no userId, matching this app's
    // single-tenant "the" user convention used elsewhere (e.g. RecipeController's ingredient search).
    public List<Recipe> rankByMealHistorySimilarity(List<Recipe> candidates) {
        List<RecommendationRequest.RecipeCandidate> candidateDtos = candidates.stream()
                .map(this::toCandidateDto)
                .toList();

        List<RecommendationRequest.MealHistoryEntry> mealHistory = nutritionLogRepository.findAll().stream()
                .map(log -> new RecommendationRequest.MealHistoryEntry(log.getItemName(), log.getLoggedAt()))
                .toList();

        RecommendationResponse response = mlServiceClient.getRecommendations(
                new RecommendationRequest(candidateDtos, mealHistory));

        Map<Long, Recipe> byId = candidates.stream().collect(Collectors.toMap(Recipe::getId, recipe -> recipe));

        return response.getResults().stream()
                .sorted(Comparator.comparingDouble(RecommendationResponse.RecipeScore::getScore).reversed())
                .map(score -> byId.get(score.getId()))
                .filter(Objects::nonNull)
                .toList();
    }

    private RecommendationRequest.RecipeCandidate toCandidateDto(Recipe recipe) {
        List<RecommendationRequest.RecipeIngredient> ingredients = recipeIngredientRepository.findByRecipeId(recipe.getId())
                .stream()
                .map(ingredient -> new RecommendationRequest.RecipeIngredient(
                        ingredient.getIngredientName(), ingredient.getQuantity(), ingredient.getUnit()))
                .toList();

        return new RecommendationRequest.RecipeCandidate(recipe.getId(), recipe.getName(), ingredients);
    }

    // TODO (Phase 6): UserPreference (see preference/UserPreference.java — daily calorie/protein/
    // fiber targets, weekly budget) will start factoring into ranking/filtering once budget- and
    // calorie-aware meal plans land. Not needed yet — this hook is just so it isn't forgotten:
    // rankRecipes will likely need an additional UserPreference (or relevant subset) parameter, or
    // a second overload, at that point.
}
