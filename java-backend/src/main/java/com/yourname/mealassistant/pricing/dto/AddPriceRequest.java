package com.yourname.mealassistant.pricing.dto;

import java.math.BigDecimal;

// Body for POST /api/prices — manual price add/edit.
// DECISION: exact fields. Sketched as name + price + pricingMode ("PER_ITEM" /
//   "PER_KG") + optional gramsPerItem (only relevant for PER_ITEM, so a grams-based recipe
//   quantity can be converted to a count — see IngredientPrice). Open: validate pricingMode
//   against an allowed set here or in the service; is gramsPerItem ever required?
public record AddPriceRequest(
        String itemName,
        BigDecimal price,
        String pricingMode,
        Double gramsPerItem) {
}
