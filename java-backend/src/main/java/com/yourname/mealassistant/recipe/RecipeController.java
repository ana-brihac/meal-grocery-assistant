package com.yourname.mealassistant.recipe;

import com.yourname.mealassistant.common.dto.ApiResponse;
import com.yourname.mealassistant.recipe.dto.RecipeSearchRequest;
import com.yourname.mealassistant.recipe.dto.RecipeSearchResponse;
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
}
