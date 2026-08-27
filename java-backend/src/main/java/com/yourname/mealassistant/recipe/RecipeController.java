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
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<RecipeSearchResponse>> search(@RequestParam List<String> ingredients) {
        RecipeSearchRequest request = new RecipeSearchRequest(ingredients);
        return ResponseEntity.ok(ApiResponse.ok(service.searchRecipes(request)));
    }
}
