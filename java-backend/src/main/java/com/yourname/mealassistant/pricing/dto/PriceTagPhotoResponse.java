package com.yourname.mealassistant.pricing.dto;

import java.math.BigDecimal;

// What OCR pulled off a shelf price-tag photo (POST /api/prices/from-photo).
// DECISION: exact fields, and whether this represents "already saved" or "parsed,
//   please confirm" (see IngredientPriceService.addFromPhoto). Sketched with the parsed values
//   plus a `saved` flag and the raw OCR text for the client to show alongside a confirm step.
public record PriceTagPhotoResponse(
        String itemName,
        BigDecimal price,
        String pricingMode,
        boolean saved,
        String rawOcrText) {
}
