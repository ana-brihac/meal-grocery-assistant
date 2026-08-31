package com.yourname.mealassistant.grocerylist.dto;

import com.yourname.mealassistant.common.dto.MissingIngredientPrice;

import java.math.BigDecimal;
import java.util.List;

// The shopping list for a meal plan: what to buy after inventory was subtracted, plus a running
// cost estimate.
// Decided: costIncomplete is true when at least one line had no ingredient_price
//   row — estimatedTotalCost is then a lower bound.
// Decided: stale is true when the plan's slots changed after this list was
//   generated — the user should regenerate.
// Decided: each Item echoes its persisted id (needed for the check-off PATCH) and its
//   `purchased` flag, plus itemName / quantity / unit / estimatedCost.
// Decided: missingPrices lists every ingredient on the list that couldn't be costed
//   (name + reason), so the client can prompt the user to add the price via POST /api/prices.
//   It's recomputed on every response (generate / get / check-off), so it clears as prices are
//   added and the list is refetched — no regenerate needed just to see it shrink.
public record GroceryListResponse(
        Long mealPlanId,
        List<Item> items,
        BigDecimal estimatedTotalCost,
        boolean costIncomplete,
        boolean stale,
        List<MissingIngredientPrice> missingPrices) {

    public record Item(
            Long id,
            String itemName,
            Double quantity,
            String unit,
            BigDecimal estimatedCost,
            boolean purchased) {
    }
}
