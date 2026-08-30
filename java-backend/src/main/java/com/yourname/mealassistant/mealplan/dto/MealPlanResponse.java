package com.yourname.mealassistant.mealplan.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// A generated / stored plan, with enough per-slot detail for the UI to explain WHY each recipe
// is there and how the plan sits against the snapshot targets.
//
// `warnings` are free-form strings from MealPlanOptimizer (days outside the calorie band, macro
// shortfalls, over budget, "couldn't fully match targets after reuse", ...). They are NOT
// persisted — getHistory / getPlan return an empty list; only generate / replaceSlot /
// selectForWeek populate it, from the run that just happened.
// `costIncomplete` / `nutritionIncomplete` are true when any slot's cost / nutrition figure is
// partial (an ingredient had no price / no macros even after the AI fallback).
public record MealPlanResponse(
        Long planId,
        String status,
        LocalDate weekStartDate,
        Long sourcePlanId,
        Targets targets,
        List<PlannedSlot> slots,
        BigDecimal totalEstimatedCost,
        boolean costIncomplete,
        boolean nutritionIncomplete,
        List<String> warnings) {

    // The snapshot targets the plan was built against (from MealPlan).
    public record Targets(
            Double dailyCalorieTarget,
            Double dailyProteinTarget,
            Double dailyFiberTarget,
            BigDecimal weeklyBudget) {
    }

    // One meal. The contribution fields are this recipe's serving-scaled share, so the client can
    // render "picked because it adds ~520 kcal / 34 g protein / ~$3.10". costComplete /
    // nutritionComplete = false means that figure is partial. The client can group slots by
    // `date` to show the per-day roll-up against the targets.
    public record PlannedSlot(
            Long slotId,
            LocalDate date,
            String mealType,
            Long recipeId,
            String recipeName,
            Double servings,
            Double calorieContribution,
            Double proteinContribution,
            Double fiberContribution,
            BigDecimal costContribution,
            boolean costComplete,
            boolean nutritionComplete) {
    }
}
