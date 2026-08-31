package com.yourname.mealassistant.grocerylist.dto;

// Body for POST /api/grocerylist/generate. Decided: a meal plan is persisted, so the
// list is generated from its id (not a loose recipe-id list).
// Decided: mealPlanId is the only field. Generate always full-replaces any existing
//   list for the plan (delete-then-insert); there is no carry-over flag for prior `purchased`
//   ticks and no separate force-replace flag.
public record GroceryListRequest(Long mealPlanId) {
}
