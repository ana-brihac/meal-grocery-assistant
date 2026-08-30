package com.yourname.mealassistant.mealplan.dto;

import java.util.List;

// Body for POST /api/mealplan/{planId}/slots/{slotId}/replace. The slot to swap is in the path;
// the replacement pool already excludes recipes used elsewhere in the plan.
// DECISION: is a body needed at all? Sketched with an optional extra exclusion list
//   (e.g. "not this recipe again" after the user rejects a suggestion). Could also carry a
//   servings override for the new slot, or be dropped entirely and the endpoint take no body.
public record SlotReplacementRequest(List<Long> excludeRecipeIds) {
}
