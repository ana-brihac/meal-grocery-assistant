package com.yourname.mealassistant.mealplan;

import com.yourname.mealassistant.common.dto.ApiResponse;
import com.yourname.mealassistant.mealplan.dto.MealPlanRequest;
import com.yourname.mealassistant.mealplan.dto.MealPlanResponse;
import com.yourname.mealassistant.mealplan.dto.SelectPlanRequest;
import com.yourname.mealassistant.mealplan.dto.SlotReplacementRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Wraps every response in ApiResponse<T>, following RecipeController /
// InventoryController / UserPreferenceController (not the raw-DTO style of
// NutritionController#/summary or DashboardController).
// Endpoints: generate, history, get-one, replace-slot, select-for-week.
@RestController
@RequestMapping("/api/mealplan")
public class MealPlanController {

    private final MealPlanService service;

    public MealPlanController(MealPlanService service) {
        this.service = service;
    }

    // Generate a fresh plan for a week. Body carries the week start + layout (see MealPlanRequest).
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<MealPlanResponse>> generate(@RequestBody MealPlanRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.generatePlan(request)));
    }

    // History — newest first.
    @GetMapping("")
    public ResponseEntity<ApiResponse<List<MealPlanResponse>>> history() {
        return ResponseEntity.ok(ApiResponse.ok(service.getHistory()));
    }

    @GetMapping("/{planId}")
    public ResponseEntity<ApiResponse<MealPlanResponse>> getOne(@PathVariable Long planId) {
        return ResponseEntity.ok(ApiResponse.ok(service.getPlan(planId)));
    }

    // Swap a single slot (e.g. Friday lunch) for a replacement that still fits the plan. Body is
    // optional — it only carries extra recipe ids to exclude from the suggestion.
    @PostMapping("/{planId}/slots/{slotId}/replace")
    public ResponseEntity<ApiResponse<MealPlanResponse>> replaceSlot(@PathVariable Long planId,
                                                                     @PathVariable Long slotId,
                                                                     @RequestBody(required = false) SlotReplacementRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.replaceSlot(planId, slotId, request)));
    }

    // Pick a historical plan to use for a week — clones it (see MealPlanService.selectForWeek).
    @PostMapping("/{planId}/select")
    public ResponseEntity<ApiResponse<MealPlanResponse>> select(@PathVariable Long planId,
                                                                @RequestBody SelectPlanRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.selectForWeek(planId, request)));
    }
}
