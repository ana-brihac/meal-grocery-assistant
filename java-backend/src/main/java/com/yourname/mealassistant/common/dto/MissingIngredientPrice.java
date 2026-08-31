package com.yourname.mealassistant.common.dto;

// One ingredient a grocery list (or meal plan) couldn't cost, plus why — surfaced in the
// response so the client can notify the user and let them fix it via POST /api/prices.
//
//   NO_PRICE_ON_FILE      - no ingredient_price row for this (normalized) name at all.
//   NEEDS_GRAMS_PER_ITEM  - a PER_ITEM price exists but has no gramsPerItem, so a grams-based
//                           recipe quantity can't be turned into a unit count.
//
// `reason` is a plain String (with the constants below), matching the repo's String-not-enum
// house style (see IngredientPrice.MODE_*, MealPlan.STATUS_*).
public record MissingIngredientPrice(String ingredientName, String reason) {

    public static final String REASON_NO_PRICE_ON_FILE = "NO_PRICE_ON_FILE";
    public static final String REASON_NEEDS_GRAMS_PER_ITEM = "NEEDS_GRAMS_PER_ITEM";
}
