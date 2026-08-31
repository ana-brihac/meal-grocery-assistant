package com.yourname.mealassistant.mealplan.dto;

import java.util.List;

// Body for POST /api/mealplan/{planId}/slots/{slotId}/replace. The slot to swap is in the path;
// the replacement pool already excludes recipes used elsewhere in the plan.
// Decided: keep an optional body carrying only excludeRecipeIds — "not this recipe again"
//   after the user rejects a suggestion. The endpoint takes @RequestBody(required = false), so
//   callers may omit it entirely. No servings override: a swap keeps the slot's existing servings.
public record SlotReplacementRequest(List<Long> excludeRecipeIds) {
}
