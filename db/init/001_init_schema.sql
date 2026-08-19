-- Phase 1: inventory_items only.
-- Phase 2 will ALTER TABLE to add receipt_id (FK) once receipts table exists.
-- Phase 1-later will add a users table + user_id FK once multi-user matters.

CREATE TABLE inventory_items (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    quantity      NUMERIC,
    expiry_date    DATE,
    added_at      TIMESTAMP NOT NULL DEFAULT now()
);