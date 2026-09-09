package com.yourname.mealassistant.mealplan;

import com.yourname.mealassistant.mealplan.dto.MealPlanRequest;
import com.yourname.mealassistant.mealplan.dto.MealPlanResponse;
import com.yourname.mealassistant.mealplan.dto.SelectPlanRequest;
import com.yourname.mealassistant.mealplan.dto.SlotReplacementRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Returns raw DTOs (the project-wide convention — see docs/backend-api.md). Errors come back as
// RFC 9457 ProblemDetail via GlobalExceptionHandler.
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
    public ResponseEntity<MealPlanResponse> generate(@RequestBody MealPlanRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.generatePlan(request));
    }

    // History — newest first.
    @GetMapping("")
    public ResponseEntity<List<MealPlanResponse>> history() {
        return ResponseEntity.ok(service.getHistory());
    }

    @GetMapping("/{planId}")
    public ResponseEntity<MealPlanResponse> getOne(@PathVariable Long planId) {
        return ResponseEntity.ok(service.getPlan(planId));
    }

    // Swap a single slot (e.g. Friday lunch) for a replacement that still fits the plan. Body is
    // optional — it only carries extra recipe ids to exclude from the suggestion.
    @PostMapping("/{planId}/slots/{slotId}/replace")
    public ResponseEntity<MealPlanResponse> replaceSlot(@PathVariable Long planId,
                                                        @PathVariable Long slotId,
                                                        @RequestBody(required = false) SlotReplacementRequest request) {
        return ResponseEntity.ok(service.replaceSlot(planId, slotId, request));
    }

    // Pick a historical plan to use for a week — clones it (see MealPlanService.selectForWeek).
    @PostMapping("/{planId}/select")
    public ResponseEntity<MealPlanResponse> select(@PathVariable Long planId,
                                                   @RequestBody SelectPlanRequest request) {
        return ResponseEntity.ok(service.selectForWeek(planId, request));
    }
}
