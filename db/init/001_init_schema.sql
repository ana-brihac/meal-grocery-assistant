CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(255) NOT NULL UNIQUE,
    email         VARCHAR(255) NOT NULL UNIQUE,
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE recipes (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    instructions  TEXT,
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE meals (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT REFERENCES users(id),
    recipe_id     BIGINT REFERENCES recipes(id),
    planned_date  DATE,
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE receipts (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT REFERENCES users(id),
    store_name    VARCHAR(255),
    total_amount  NUMERIC,
    receipt_date  DATE,
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE inventory_items (
    id            BIGSERIAL PRIMARY KEY,
    receipt_id    BIGINT REFERENCES receipts(id),
    name          VARCHAR(255) NOT NULL,
    quantity      NUMERIC,
    expiry_date   DATE,
    added_at      TIMESTAMP NOT NULL DEFAULT now()
);
