package com.yourname.mealassistant.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourname.mealassistant.common.client.OcrClient;
import com.yourname.mealassistant.common.util.ItemNameNormalizer;
import com.yourname.mealassistant.pricing.dto.AddPriceRequest;
import com.yourname.mealassistant.pricing.dto.PriceTagPhotoResponse;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

// Owns the ingredient_price catalog: upserts from receipts, manual entry, and OCR'd
// shelf price-tag photos, plus the cost lookup the meal-plan optimizer and grocery list use.
//
// Decided: item names are keyed by ItemNameNormalizer.normalize (same key space as
//   nutrition_info) on every read and write.
// Decided: a receipt/photo upsert updates the current `price` (with change tracking)
//   but never clobbers a `pricingMode` / `gramsPerItem` already on the row — those encode user
//   knowledge (produce is PER_KG, "1 egg = 50 g") that a receipt line can't carry. A brand-new
//   row from a receipt/photo defaults to PER_ITEM.
// Decided: on a price change, the old value moves to previousPrice and priceChangedAt
//   is stamped; an unchanged price only touches updatedAt. No history table.
// Decided: the price-tag photo path auto-saves (source PRICE_TAG_PHOTO) and returns
//   what it stored; the client can show it for review and re-POST corrections to /api/prices.
@Service
public class IngredientPriceService {

    private static final String PRICE_TAG_PROMPT =
            "This is a photo of a grocery store shelf price tag. Return ONLY a JSON object with keys: "
            + "\"name\" (the product name as printed), "
            + "\"price\" (the numeric price, no currency symbol), "
            + "\"unit\" (\"kg\" if the price is per kilogram, otherwise \"item\").";

    private final IngredientPriceRepository ingredientPriceRepository;
    private final OcrClient ocrClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IngredientPriceService(IngredientPriceRepository ingredientPriceRepository, OcrClient ocrClient) {
        this.ingredientPriceRepository = ingredientPriceRepository;
        this.ocrClient = ocrClient;
    }

    // Backs the optional "prices screen" list endpoint (PricingController).
    public List<IngredientPrice> findAll() {
        return ingredientPriceRepository.findAll();
    }

    // Called by ReceiptService for each parsed receipt line. Receipt lines carry a price but no
    // mode / grams-per-item, so those are left to whatever's already on the row (or defaulted for
    // a new row). Lines with no usable price (ReceiptParser defaults a missing price to 0) are
    // skipped.
    public void upsertFromReceipt(String rawItemName, BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            return;
        }
        upsertPrice(rawItemName, price, IngredientPrice.SOURCE_RECEIPT, null, null);
    }

    // POST /api/prices — manual add/edit. This is where pricingMode ("PER_KG" for produce,
    // "PER_ITEM" otherwise) and gramsPerItem actually get set properly.
    public IngredientPrice addManualPrice(AddPriceRequest request) {
        String mode = normalizeMode(request.pricingMode());
        return upsertPrice(request.itemName(), request.price(), IngredientPrice.SOURCE_MANUAL,
                mode, request.gramsPerItem());
    }

    // POST /api/prices/from-photo — OCR a shelf price tag (synchronous), parse it, and upsert.
    public PriceTagPhotoResponse addFromPhoto(byte[] imageBytes, String contentType) {
        String rawOcrText;
        try {
            rawOcrText = ocrClient.extractTextFromImage(imageBytes, contentType, PRICE_TAG_PROMPT);
        } catch (IOException e) {
            throw new RuntimeException("Price-tag OCR call failed", e);
        }

        ParsedTag tag = parseTag(rawOcrText);
        IngredientPrice saved = upsertPrice(tag.name(), tag.price(), IngredientPrice.SOURCE_PRICE_TAG_PHOTO,
                tag.mode(), null);

        return new PriceTagPhotoResponse(saved.getItemName(), saved.getPrice(), saved.getPricingMode(),
                true, rawOcrText);
    }

    // Why an ingredient couldn't be priced (NONE = it was). Drives the user-facing
    // "add a price for X" notification on grocery lists / meal plans.
    public enum PriceGap { NONE, NO_PRICE_ON_FILE, NEEDS_GRAMS_PER_ITEM }

    // Cost plus the reason it's absent when it is. `cost` is empty iff `gap != NONE`.
    public record CostEstimate(Optional<BigDecimal> cost, PriceGap gap) {
    }

    // Cost of `quantityGrams` of an ingredient, or empty if it can't be determined:
    //   - no ingredient_price row (or a null price) -> empty
    //   - PER_KG            -> price * (grams / 1000)
    //   - PER_ITEM w/ gramsPerItem -> price * ceil(grams / gramsPerItem)
    //   - PER_ITEM w/o gramsPerItem -> empty (can't turn grams into a unit count)
    // Callers flag the recipe / list costIncomplete on empty; they do NOT drop the ingredient.
    // Thin wrapper over estimateCost(...) — kept for callers that don't need the gap reason.
    public Optional<BigDecimal> estimateIngredientCost(String rawIngredientName, double quantityGrams) {
        return estimateCost(rawIngredientName, quantityGrams).cost();
    }

    // Same lookup as estimateIngredientCost, but also reports WHY the cost is missing so the
    // caller can tell the user what to add (a price, or a gramsPerItem for an existing one).
    public CostEstimate estimateCost(String rawIngredientName, double quantityGrams) {
        IngredientPrice ip = ingredientPriceRepository
                .findByItemName(ItemNameNormalizer.normalize(rawIngredientName))
                .orElse(null);
        if (ip == null || ip.getPrice() == null) {
            return new CostEstimate(Optional.empty(), PriceGap.NO_PRICE_ON_FILE);
        }

        if (IngredientPrice.MODE_PER_KG.equals(ip.getPricingMode())) {
            BigDecimal kg = BigDecimal.valueOf(quantityGrams).divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
            return new CostEstimate(
                    Optional.of(ip.getPrice().multiply(kg).setScale(2, RoundingMode.HALF_UP)), PriceGap.NONE);
        }

        Double gramsPerItem = ip.getGramsPerItem();
        if (gramsPerItem == null || gramsPerItem <= 0) {
            return new CostEstimate(Optional.empty(), PriceGap.NEEDS_GRAMS_PER_ITEM);
        }
        long units = (long) Math.ceil(quantityGrams / gramsPerItem);
        return new CostEstimate(
                Optional.of(ip.getPrice().multiply(BigDecimal.valueOf(units)).setScale(2, RoundingMode.HALF_UP)),
                PriceGap.NONE);
    }

    // --- internals ---

    private IngredientPrice upsertPrice(String rawItemName, BigDecimal price, String source,
                                       String modeOrNull, Double gramsPerItemOrNull) {
        String key = ItemNameNormalizer.normalize(rawItemName);

        IngredientPrice ip = ingredientPriceRepository.findByItemName(key).orElseGet(() -> {
            IngredientPrice fresh = new IngredientPrice();
            fresh.setItemName(key);
            return fresh;
        });

        boolean priceChanged = ip.getPrice() != null && price != null && ip.getPrice().compareTo(price) != 0;
        if (priceChanged) {
            ip.setPreviousPrice(ip.getPrice());
            ip.setPriceChangedAt(LocalDateTime.now());
        }
        if (price != null) {
            ip.setPrice(price);
        }

        // Explicit mode (manual entry) wins; otherwise default a brand-new row to PER_ITEM and
        // leave an existing row's mode alone.
        if (modeOrNull != null) {
            ip.setPricingMode(modeOrNull);
        } else if (ip.getPricingMode() == null) {
            ip.setPricingMode(IngredientPrice.MODE_PER_ITEM);
        }
        if (gramsPerItemOrNull != null) {
            ip.setGramsPerItem(gramsPerItemOrNull);
        }

        ip.setSource(source);
        ip.setUpdatedAt(LocalDateTime.now());

        return ingredientPriceRepository.save(ip);
    }

    private String normalizeMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return IngredientPrice.MODE_PER_ITEM;
        }
        String v = raw.trim().toUpperCase();
        if (v.equals(IngredientPrice.MODE_PER_ITEM) || v.equals(IngredientPrice.MODE_PER_KG)) {
            return v;
        }
        throw new IllegalArgumentException("pricingMode must be PER_ITEM or PER_KG, got: " + raw);
    }

    private ParsedTag parseTag(String rawOcrText) {
        String cleanJson = rawOcrText == null ? "" :
                rawOcrText.replaceAll("(?s)```(?:json)?\\s*(.*?)\\s*```", "$1").trim();
        try {
            JsonNode node = objectMapper.readTree(cleanJson);
            String name = node.hasNonNull("name") ? node.get("name").asText() : null;
            BigDecimal price = node.hasNonNull("price") ? new BigDecimal(node.get("price").asText().trim()) : null;
            String unit = node.hasNonNull("unit") ? node.get("unit").asText() : null;

            if (name == null || name.isBlank() || price == null) {
                throw new IllegalStateException("no usable name/price");
            }
            String mode = unit != null && unit.trim().equalsIgnoreCase("kg")
                    ? IngredientPrice.MODE_PER_KG
                    : IngredientPrice.MODE_PER_ITEM;
            return new ParsedTag(name, price, mode);
        } catch (Exception e) {
            throw new RuntimeException("Could not parse price-tag OCR response: " + cleanJson, e);
        }
    }

    private record ParsedTag(String name, BigDecimal price, String mode) {
    }
}
