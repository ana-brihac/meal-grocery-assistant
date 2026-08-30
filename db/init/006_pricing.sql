-- Ingredient price catalog. Feeds meal-plan cost estimates and the grocery list.
-- Fed by receipt uploads, manual entry, and OCR'd shelf price-tag photos (see
-- pricing/IngredientPrice.java).
--
-- Type pairing (docs/database.md): money -> NUMERIC / BigDecimal; grams -> DOUBLE PRECISION.

CREATE TABLE ingredient_price (
    id                  BIGSERIAL PRIMARY KEY,
    item_name           VARCHAR(255) NOT NULL UNIQUE,   -- normalized food name (ItemNameNormalizer), the lookup key
    price               NUMERIC,
    -- 'PER_ITEM' (e.g. a price per egg / per jar) or 'PER_KG' (typical for loose produce).
    -- Plain string, not a DB enum/CHECK -- IngredientPriceService validates the value.
    pricing_mode        VARCHAR(20),
    -- Only meaningful when pricing_mode = 'PER_ITEM'. Recipe ingredient quantities are in grams,
    -- so costing a per-item ingredient needs the grams in one unit (e.g. "1 egg = 50 g") to turn
    -- "150 g egg" into 3 eggs. NULL for PER_KG, or when the per-item weight isn't known.
    grams_per_item      DOUBLE PRECISION,
    source              VARCHAR(30),                    -- how this row was created: 'RECEIPT' | 'MANUAL' | 'PRICE_TAG_PHOTO'
    -- Price-change tracking is just these two columns (no separate price_history table). When a
    -- receipt/photo scan finds a different price for an item already here, the old value is
    -- copied into previous_price and price_changed_at is stamped.
    previous_price      NUMERIC,                        -- nullable: last price before the most recent change
    price_changed_at    TIMESTAMP,                      -- nullable: when previous_price was superseded
    updated_at          TIMESTAMP NOT NULL DEFAULT now()
);