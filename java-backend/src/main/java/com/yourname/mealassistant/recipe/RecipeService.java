package com.yourname.mealassistant.recipe;

import com.yourname.mealassistant.recipe.dto.RecipeDetailResponse;
import com.yourname.mealassistant.recipe.dto.RecipeSearchRequest;
import com.yourname.mealassistant.recipe.dto.RecipeSearchResponse;
import com.yourname.mealassistant.common.exception.NotFoundException;
import com.yourname.mealassistant.recipe.dto.RecipeUpsertRequest;
import com.yourname.mealassistant.recipe.ranking.RecipeRankingService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class RecipeService {

    private final RecipeRepository recipeRepository;
    private final RecipeIngredientRepository recipeIngredientRepository;
    private final RecipeRankingService recipeRankingService;

    public RecipeService(RecipeRepository recipeRepository,
                         RecipeIngredientRepository recipeIngredientRepository,
                         RecipeRankingService recipeRankingService) {
        this.recipeRepository = recipeRepository;
        this.recipeIngredientRepository = recipeIngredientRepository;
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

    // --- CRUD: add / edit recipes from the app, not just the startup CSV ---

    public List<RecipeDetailResponse> listRecipes() {
        return recipeRepository.findAll().stream().map(this::toDetail).toList();
    }

    public RecipeDetailResponse getRecipe(Long id) {
        Recipe recipe = recipeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Recipe not found: " + id));
        return toDetail(recipe);
    }

    public RecipeDetailResponse createRecipe(RecipeUpsertRequest request) {
        Recipe recipe = new Recipe();
        recipe.setName(request.name());
        recipe.setInstructions(request.instructions());
        recipe.setSource(request.source());
        recipe = recipeRepository.save(recipe);

        replaceIngredients(recipe.getId(), request);
        return toDetail(recipe);
    }

    // Edit: name/instructions/source are overwritten; the ingredient list is replaced wholesale.
    // Past meal-plan slots keep their snapshot figures, so an edit doesn't rewrite history — it
    // only affects plans generated afterwards.
    public RecipeDetailResponse updateRecipe(Long id, RecipeUpsertRequest request) {
        Recipe recipe = recipeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Recipe not found: " + id));
        recipe.setName(request.name());
        recipe.setInstructions(request.instructions());
        recipe.setSource(request.source());
        recipeRepository.save(recipe);

        replaceIngredients(id, request);
        return toDetail(recipe);
    }

    private void replaceIngredients(Long recipeId, RecipeUpsertRequest request) {
        // deleteAll(Iterable) is transactional in SimpleJpaRepository; a derived deleteBy... isn't.
        recipeIngredientRepository.deleteAll(recipeIngredientRepository.findByRecipeId(recipeId));

        if (request.ingredients() == null) return;
        List<RecipeIngredient> rows = new ArrayList<>();
        for (RecipeUpsertRequest.IngredientInput in : request.ingredients()) {
            RecipeIngredient ri = new RecipeIngredient();
            ri.setRecipeId(recipeId);
            ri.setIngredientName(in.ingredientName());
            ri.setQuantity(in.quantity());
            ri.setUnit(in.unit());
            rows.add(ri);
        }
        recipeIngredientRepository.saveAll(rows);
    }

    private RecipeDetailResponse toDetail(Recipe recipe) {
        List<RecipeDetailResponse.IngredientView> ingredients =
                recipeIngredientRepository.findByRecipeId(recipe.getId()).stream()
                        .map(ri -> new RecipeDetailResponse.IngredientView(
                                ri.getId(), ri.getIngredientName(), ri.getQuantity(), ri.getUnit()))
                        .toList();
        return new RecipeDetailResponse(recipe.getId(), recipe.getName(), recipe.getInstructions(),
                recipe.getSource(), ingredients);
    }
}
