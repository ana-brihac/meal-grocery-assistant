package com.yourname.mealassistant.pricing.dto;

import java.math.BigDecimal;

// Body for POST /api/prices — manual price add/edit.
// Decided: fields are itemName + price + pricingMode ("PER_ITEM" / "PER_KG") + optional
//   gramsPerItem (only relevant for PER_ITEM, to convert a grams-based recipe quantity to a
//   count — see IngredientPrice). pricingMode is validated in IngredientPriceService
//   (normalizeMode throws on anything else). gramsPerItem is never required: omitting it just
//   leaves PER_ITEM costs unknown (costIncomplete) until it's supplied.
public record AddPriceRequest(
        String itemName,
        BigDecimal price,
        String pricingMode,
        Double gramsPerItem) {
}
