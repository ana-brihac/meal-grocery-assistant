package com.yourname.mealassistant.recipe;

import com.yourname.mealassistant.common.dto.ApiResponse;
import com.yourname.mealassistant.recipe.dto.RecipeDetailResponse;
import com.yourname.mealassistant.recipe.dto.RecipeSearchRequest;
import com.yourname.mealassistant.recipe.dto.RecipeSearchResponse;
import com.yourname.mealassistant.recipe.dto.RecipeUpsertRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/recipes")
public class RecipeController {

    private final RecipeService service;

    public RecipeController(RecipeService service) {
        this.service = service;
    }

    // Decided: no userId param, unlike NutritionController/SpendingController — this app is
    // single-tenant in practice today (UserPreference is a singleton row, no auth/multi-user
    // support), so RecipeService looks up "the" inventory rather than a per-user one.
    // rankBy=mealHistory switches to Phase 5's meal-history-similarity ranking instead of the
    // default fewest-ingredients rule; omitted/anything else keeps the default (unchanged from
    // Phase 4). See RecipeService.searchRecipes for the fallback behavior if ml-service is down.
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<RecipeSearchResponse>> search(
            @RequestParam List<String> ingredients,
            @RequestParam(required = false) String rankBy) {
        RecipeSearchRequest request = new RecipeSearchRequest(ingredients, rankBy);
        return ResponseEntity.ok(ApiResponse.ok(service.searchRecipes(request)));
    }

    // --- recipe management: add / edit recipes from the app ---

    @GetMapping("")
    public ResponseEntity<ApiResponse<List<RecipeDetailResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(service.listRecipes()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<RecipeDetailResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getRecipe(id)));
    }

    @PostMapping("")
    public ResponseEntity<ApiResponse<RecipeDetailResponse>> create(@RequestBody RecipeUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.createRecipe(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<RecipeDetailResponse>> update(@PathVariable Long id,
                                                                    @RequestBody RecipeUpsertRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.updateRecipe(id, request)));
    }

    // DECISION: no DELETE endpoint yet. A recipe is referenced by meal_plan_slot,
    //   nutrition_log.recipe_id and the unused `meals` table, so a hard delete would fail on the
    //   FK. Needs a decision: block-if-referenced, soft-delete (a `deleted` flag + filter it out
    //   of search/list/planning), or null the references. Not chosen here.
}
