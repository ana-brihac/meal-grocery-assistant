package com.yourname.mealassistant.grocerylist.dto;

import java.math.BigDecimal;
import java.util.List;

// The shopping list for a meal plan: what to buy after inventory was subtracted, plus a running
// cost estimate.
// Decided: costIncomplete is true when at least one line had no ingredient_price
//   row — estimatedTotalCost is then a lower bound.
// Decided: stale is true when the plan's slots changed after this list was
//   generated — the user should regenerate.
// DECISION: exact fields otherwise — does each Item echo the persisted id (needed for
//   the check-off PATCH) and the `purchased` flag? Sketched yes.
public record GroceryListResponse(
        Long mealPlanId,
        List<Item> items,
        BigDecimal estimatedTotalCost,
        boolean costIncomplete,
        boolean stale) {

    public record Item(
            Long id,
            String itemName,
            Double quantity,
            String unit,
            BigDecimal estimatedCost,
            boolean purchased) {
    }
}
