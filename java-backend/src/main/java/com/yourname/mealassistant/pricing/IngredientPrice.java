package com.yourname.mealassistant.pricing;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// The current known price for one ingredient/product. This is the pricing catalog the
// meal-plan optimizer and the grocery list draw on for cost estimates.
//
// Fed three ways:
//   1. weekly receipt uploads — ReceiptService upserts each parsed line here (source RECEIPT);
//   2. manual entry — POST /api/prices (source MANUAL), e.g. to bootstrap before any receipts;
//   3. a photo of a shelf price tag — POST /api/prices/from-photo, OCR'd via OcrClient
//      (source PRICE_TAG_PHOTO).
//
// pricingMode is "PER_ITEM" or "PER_KG". Produce (fruit/veg) is typically PER_KG; most packaged
//   goods are PER_ITEM. Kept a plain String, not an enum, matching the repo's low-ceremony style.
//   - PER_KG  -> cost of a recipe ingredient = price * (quantityGrams / 1000).
//   - PER_ITEM -> recipe_ingredients.quantity is in GRAMS, so converting to a number of items
//     needs gramsPerItem (e.g. "1 egg = 50 g"). See the open note below.
// Price-change tracking is just previousPrice + priceChangedAt on this row — no separate
//   price_history table. When a RECEIPT/PHOTO upsert sees a different price for a name already
//   present, it moves the old value into previousPrice and stamps priceChangedAt.
//
// OPEN: PER_ITEM cost when gramsPerItem is unknown. Options: (a) assume the recipe needs 1 unit
//   of that item regardless of its gram quantity (rough, cheap); (b) leave the ingredient's cost
//   unknown so the whole recipe becomes costIncomplete. Not chosen — drives
//   IngredientPriceService.estimateIngredientCost.
// DECISION: name key. item_name is stored normalized via ItemNameNormalizer (same key
//   space as nutrition_info) and is UNIQUE. Confirm normalization is the right key given
//   receipt-OCR'd names are messy and a normalized collision would merge two real products.
@Entity
@Table(name = "ingredient_price")
public class IngredientPrice {

    // pricing_mode values (plain strings, validated in IngredientPriceService).
    public static final String MODE_PER_ITEM = "PER_ITEM";
    public static final String MODE_PER_KG = "PER_KG";

    // source values.
    public static final String SOURCE_RECEIPT = "RECEIPT";
    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_PRICE_TAG_PHOTO = "PRICE_TAG_PHOTO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Normalized (ItemNameNormalizer) — the lookup key. UNIQUE constraint is declared in
    // db/init/006_pricing.sql (not on the annotation, matching how other entities here
    // keep uniqueness in the schema only).
    @Column(name = "item_name")
    private String itemName;

    // Money -> BigDecimal / NUMERIC.
    private BigDecimal price;

    @Column(name = "pricing_mode")
    private String pricingMode;

    // Only meaningful for PER_ITEM — grams in one sold unit, so a grams-based recipe quantity can
    // be converted to a count. Nullable.
    @Column(name = "grams_per_item")
    private Double gramsPerItem;

    // "RECEIPT" | "MANUAL" | "PRICE_TAG_PHOTO".
    private String source;

    @Column(name = "previous_price")
    private BigDecimal previousPrice;

    @Column(name = "price_changed_at")
    private LocalDateTime priceChangedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public IngredientPrice() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public String getPricingMode() { return pricingMode; }
    public void setPricingMode(String pricingMode) { this.pricingMode = pricingMode; }

    public Double getGramsPerItem() { return gramsPerItem; }
    public void setGramsPerItem(Double gramsPerItem) { this.gramsPerItem = gramsPerItem; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public BigDecimal getPreviousPrice() { return previousPrice; }
    public void setPreviousPrice(BigDecimal previousPrice) { this.previousPrice = previousPrice; }

    public LocalDateTime getPriceChangedAt() { return priceChangedAt; }
    public void setPriceChangedAt(LocalDateTime priceChangedAt) { this.priceChangedAt = priceChangedAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
