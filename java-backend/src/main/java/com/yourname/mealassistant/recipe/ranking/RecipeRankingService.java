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
    // a parameter in case a future rule needs it.
    // Decided: this service stays PURELY structural. Budget/calorie/protein/fiber
    // constraint enforcement lives entirely in mealplan/optimizer/MealPlanOptimizer, which is the
    // single constraint engine — RecipeRankingService gets no UserPreference parameter and no
    // budget/calorie awareness, so there is zero constraint-logic duplication between the two.
    // (An older hook comment here predicted a
    // UserPreference overload here; that was resolved the other way.)
    public List<Recipe> rankRecipes(List<Recipe> candidates, List<InventoryItem> availableInventory) {
        return candidates.stream()
                .sorted(Comparator.comparingLong(recipe -> recipeIngredientRepository.countByRecipeId(recipe.getId())))
                .toList();
    }

    // Ranks candidates by embedding similarity to the user's meal history, via
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
}
