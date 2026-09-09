package com.yourname.mealassistant.nutrition;

import com.yourname.mealassistant.common.client.NutritionAiClient;
import com.yourname.mealassistant.common.client.NutritionApiClient;
import com.yourname.mealassistant.common.client.dto.NutritionEstimate;
import com.yourname.mealassistant.common.exception.NotFoundException;
import com.yourname.mealassistant.common.util.ItemNameNormalizer;
import com.yourname.mealassistant.nutrition.dto.DailyNutritionSummary;
import com.yourname.mealassistant.nutrition.dto.LoggedMealEntry;
import com.yourname.mealassistant.nutrition.dto.NutritionSummaryResponse;
import com.yourname.mealassistant.nutrition.dto.RecipeNutrition;
import com.yourname.mealassistant.recipe.Recipe;
import com.yourname.mealassistant.recipe.RecipeIngredient;
import com.yourname.mealassistant.recipe.RecipeIngredientRepository;
import com.yourname.mealassistant.recipe.RecipeRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class NutritionService {

    private final NutritionApiClient nutritionApiClient;
    private final NutritionAiClient nutritionAiClient;
    private final NutritionInfoRepository nutritionInfoRepository;
    private final NutritionLogRepository nutritionLogRepository;
    private final RecipeRepository recipeRepository;
    private final RecipeIngredientRepository recipeIngredientRepository;

    public NutritionService(NutritionApiClient nutritionApiClient,
                            NutritionAiClient nutritionAiClient,
                            NutritionInfoRepository nutritionInfoRepository,
                            NutritionLogRepository nutritionLogRepository,
                            RecipeRepository recipeRepository,
                            RecipeIngredientRepository recipeIngredientRepository) {
        this.nutritionApiClient = nutritionApiClient;
        this.nutritionAiClient = nutritionAiClient;
        this.nutritionInfoRepository = nutritionInfoRepository;
        this.nutritionLogRepository = nutritionLogRepository;
        this.recipeRepository = recipeRepository;
        this.recipeIngredientRepository = recipeIngredientRepository;
    }

    public NutritionInfo getOrFetchNutritionInfo(String foodName) {
        String normalized = ItemNameNormalizer.normalize(foodName);
        return nutritionInfoRepository.findById(normalized).orElseGet(() -> {

            var searchResponse = nutritionApiClient.searchFoodByName(normalized);

            NutritionInfo newInfo = new NutritionInfo();

            newInfo.setItemName(normalized);
            newInfo.setBaseQuantity(100.0);

            if (searchResponse != null && searchResponse.getFoods() != null && !searchResponse.getFoods().isEmpty()) {
                var firstFood = searchResponse.getFoods().get(0);

                if (firstFood.getFoodNutrients() != null) {
                    for (var nutrient : firstFood.getFoodNutrients()) {
                        String name = nutrient.getNutrientName();
                        if (name == null) continue;

                        if (name.equalsIgnoreCase("Energy") || name.contains("Calories")) {
                            newInfo.setCalories(nutrient.getValue());
                        } else if (name.equalsIgnoreCase("Protein")) {
                            newInfo.setProtein(nutrient.getValue());
                        } else if (name.contains("Fiber")) {
                            newInfo.setFibers(nutrient.getValue());
                        } else if (name.contains("Total lipid (fat)") || name.equalsIgnoreCase("Fat")) {
                            newInfo.setFats(nutrient.getValue());
                        } else if (name.contains("Carbohydrate")) {
                            newInfo.setCarbs(nutrient.getValue());
                        }
                    }
                }
            }

            // USDA had no usable calorie figure — last-resort AI estimate. Cached like
            // any other lookup (including as a null-macro row if the AI also can't help), so this
            // runs at most once per food.
            if (newInfo.getCalories() == null) {
                NutritionEstimate estimate = nutritionAiClient.estimateNutrition(normalized);
                if (estimate != null) {
                    newInfo.setCalories(estimate.calories());
                    newInfo.setProtein(estimate.protein());
                    newInfo.setFibers(estimate.fiber());
                    newInfo.setFats(estimate.fats());
                    newInfo.setCarbs(estimate.carbs());
                }
            }

            return nutritionInfoRepository.save(newInfo);
        });
    }

    // Read-only serving-scaled nutrition for one recipe, for meal planning. Sums each
    // ingredient's macros through the same cache-or-fetch path logMeal/logRecipe use (now with an
    // AI fallback), scaled by ingredient grams * servings. Does NOT write nutrition_log.
    // `complete` is false if any ingredient still had no calorie data after that whole chain.
    // Note: on a cold nutrition_info cache the first call for a recipe can be slow (a USDA — and
    // occasionally an AI — round trip per new ingredient); results are cached so later calls are
    // cheap.
    public RecipeNutrition computeRecipeNutrition(Long recipeId, Double servings) {
        Recipe recipe = recipeRepository.findById(recipeId)
                .orElseThrow(() -> new NotFoundException("Recipe not found: " + recipeId));

        double totalCalories = 0;
        double totalProtein = 0;
        double totalFiber = 0;
        boolean complete = true;

        for (RecipeIngredient ingredient : recipeIngredientRepository.findByRecipeId(recipe.getId())) {
            NutritionInfo info = getOrFetchNutritionInfo(ingredient.getIngredientName());
            double grams = ingredient.getQuantity() * servings;
            double multiplier = grams / info.getBaseQuantity();

            if (info.getCalories() == null) {
                complete = false;
                continue;
            }
            totalCalories += info.getCalories() * multiplier;
            if (info.getProtein() != null) totalProtein += info.getProtein() * multiplier;
            if (info.getFibers() != null) totalFiber += info.getFibers() * multiplier;
        }

        return new RecipeNutrition(totalCalories, totalProtein, totalFiber, complete);
    }

    public void logMeal(Long userId, String foodName, Double quantityGrams) {
        NutritionInfo info = getOrFetchNutritionInfo(foodName);

        NutritionLog logEntry = new NutritionLog();

        logEntry.setUserId(userId);
        logEntry.setItemName(info.getItemName());
        logEntry.setQuantityGrams(quantityGrams);

        nutritionLogRepository.save(logEntry);
    }

    // Logs a recipe as one NutritionLog row per ingredient (each tagged with recipeId), rather
    // than a single aggregated row — reuses getSummary/getDailyBreakdown's existing per-item
    // nutrition_info lookup and multiplier logic as-is, with no changes needed there. Each
    // ingredient's quantity is treated as grams (decided), scaled by `servings`.
    // Note: unlike logMeal, this takes no userId (per the original method signature), so these
    // rows are saved with userId == null — they won't appear in getSummary(userId, ...), which
    // filters by userId, but they do appear in getDailyBreakdown, which doesn't filter by user.
    public void logRecipe(Long recipeId, Double servings) {
        Recipe recipe = recipeRepository.findById(recipeId)
                .orElseThrow(() -> new NotFoundException("Recipe not found: " + recipeId));

        List<RecipeIngredient> ingredients = recipeIngredientRepository.findByRecipeId(recipe.getId());

        for (RecipeIngredient ingredient : ingredients) {
            NutritionInfo info = getOrFetchNutritionInfo(ingredient.getIngredientName());

            NutritionLog logEntry = new NutritionLog();
            logEntry.setItemName(info.getItemName());
            logEntry.setQuantityGrams(ingredient.getQuantity() * servings);
            logEntry.setRecipeId(recipe.getId());

            nutritionLogRepository.save(logEntry);
        }
    }

    public NutritionSummaryResponse getSummary(Long userId, LocalDateTime from, LocalDateTime to) {
        var summary = new NutritionSummaryResponse();

        List<NutritionLog> logs = nutritionLogRepository.findByUserIdAndLoggedAtBetween(userId, from, to);

        for (NutritionLog log : logs) {
            nutritionInfoRepository.findById(log.getItemName()).ifPresent(info -> {
                double multiplier = log.getQuantityGrams() / info.getBaseQuantity();

                if (info.getCalories() != null) summary.addCalories(info.getCalories() * multiplier);
                if (info.getProtein() != null) summary.addProtein(info.getProtein() * multiplier);
                if (info.getCarbs() != null) summary.addCarbs(info.getCarbs() * multiplier);
                if (info.getFats() != null) summary.addFats(info.getFats() * multiplier);
                if (info.getFibers() != null) summary.addFibers(info.getFibers() * multiplier);
            });
        }

        return summary;
    }

    public List<DailyNutritionSummary> getDailyBreakdown(LocalDate start, LocalDate end) {
        List<NutritionLog> logs = nutritionLogRepository.findAll();
        List<DailyNutritionSummary> dailyBreakdown = new ArrayList<>();
        Map<Long, String> recipeNameCache = new HashMap<>();

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            double totalCalories = 0;
            double totalProtein = 0;
            double totalFiber = 0;
            List<LoggedMealEntry> entries = new ArrayList<>();

            for (NutritionLog log : logs) {
                if (!log.getLoggedAt().toLocalDate().isEqual(date)) continue;

                NutritionInfo info = nutritionInfoRepository.findById(log.getItemName()).orElse(null);
                if (info == null) continue;

                double multiplier = log.getQuantityGrams() / info.getBaseQuantity();

                double calories;
                if (info.getCalories() != null) {
                    calories = info.getCalories() * multiplier;
                } else {
                    calories = 0;
                }

                double protein;
                if (info.getProtein() != null) {
                    protein = info.getProtein() * multiplier;
                } else {
                    protein = 0;
                }

                double fiber;
                if (info.getFibers() != null) {
                    fiber = info.getFibers() * multiplier;
                } else {
                    fiber = 0;
                }

                totalCalories += calories;
                totalProtein += protein;
                totalFiber += fiber;

                Long recipeId = log.getRecipeId();
                String recipeName = null;
                if (recipeId != null) {
                    recipeName = recipeNameCache.computeIfAbsent(recipeId,
                            id -> recipeRepository.findById(id).map(Recipe::getName).orElse(null));
                }

                entries.add(new LoggedMealEntry(log.getItemName(), log.getQuantityGrams(), calories, protein, fiber,
                        recipeId, recipeName));
            }

            // Zero-log days still get a zero-totals entry here (empty entries list) instead of
            // being skipped, so the calendar view has no missing days.
            dailyBreakdown.add(new DailyNutritionSummary(date, totalCalories, totalProtein, totalFiber, entries));
        }

        return dailyBreakdown;
    }
}
