package com.yourname.mealassistant.pricing.dto;

import java.math.BigDecimal;

// What OCR pulled off a shelf price-tag photo (POST /api/prices/from-photo).
// Decided: this represents "already saved" — addFromPhoto auto-upserts. Fields are the
//   stored itemName / price / pricingMode, saved=true, and the raw OCR text so the client can
//   show what was read and let the user re-POST corrections to POST /api/prices.
public record PriceTagPhotoResponse(
        String itemName,
        BigDecimal price,
        String pricingMode,
        boolean saved,
        String rawOcrText) {
}
