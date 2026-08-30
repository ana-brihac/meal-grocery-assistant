package com.yourname.mealassistant.grocerylist.dto;

// Body for POST /api/grocerylist/generate. Decided: a meal plan is persisted, so the
// list is generated from its id (not a loose recipe-id list).
// DECISION: anything else? e.g. a flag to include/exclude items already marked
//   purchased on a prior list for this plan, or to force-replace a stale list.
public record GroceryListRequest(Long mealPlanId) {
}
